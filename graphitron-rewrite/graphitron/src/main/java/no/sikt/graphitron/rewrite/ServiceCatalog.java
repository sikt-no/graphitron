package no.sikt.graphitron.rewrite;

import graphql.schema.GraphQLInputObjectType;
import graphql.schema.GraphQLInputType;
import graphql.schema.GraphQLList;
import graphql.schema.GraphQLNonNull;
import graphql.schema.GraphQLScalarType;
import graphql.schema.GraphQLType;
import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.TypeName;
import no.sikt.graphitron.rewrite.model.CallSiteExtraction;
import no.sikt.graphitron.rewrite.model.ColumnRef;
import no.sikt.graphitron.rewrite.model.GraphitronType.TableBackedType;
import no.sikt.graphitron.rewrite.model.JoinStep;
import no.sikt.graphitron.rewrite.model.JoinStep.FkJoin;
import no.sikt.graphitron.rewrite.model.LoaderRegistration;
import no.sikt.graphitron.rewrite.model.MethodRef;
import no.sikt.graphitron.rewrite.model.ParamSource;
import no.sikt.graphitron.rewrite.model.Rejection;
import no.sikt.graphitron.rewrite.model.SourceKey;
import no.sikt.graphitron.rewrite.model.TableRef;
import no.sikt.graphitron.rewrite.model.TypeNames;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Handles all reflection-based and jOOQ-catalog-based lookups: resolving Java service methods,
 * resolving tables and columns from the jOOQ catalog, and classifying SOURCES parameter types.
 *
 * <p>This is the mirror of {@link JooqCatalog} for the service layer: it wraps the catalog and
 * adds the Java-reflection logic needed to introspect service classes at build time.
 */
class ServiceCatalog {

    private static final Logger LOGGER = LoggerFactory.getLogger(ServiceCatalog.class);

    private final BuildContext ctx;
    /** Ensures the -parameters warning is emitted at most once per build. */
    private boolean parametersWarningEmitted = false;

    ServiceCatalog(BuildContext ctx) {
        this.ctx = ctx;
    }

    // ===== Table and column resolution =====

    Optional<TableRef> resolveTable(String sqlName) {
        return ctx.catalog.findTable(sqlName).asEntry().map(e -> e.toTableRef(sqlName));
    }

    Optional<TableRef> resolveTableByRecordClass(Class<?> recordClass) {
        return ctx.catalog.findTableByRecordClass(recordClass)
            .map(e -> e.toTableRef(e.table().getName()));
    }

    Optional<ColumnRef> resolveKeyColumn(String colName, String tableSqlName) {
        return ctx.catalog.findColumn(tableSqlName, colName)
            .map(e -> new ColumnRef(e.sqlName(), e.javaName(), e.columnClass()));
    }

    Optional<ColumnRef> resolveColumn(String columnName, TableBackedType tableType) {
        return resolveColumnInTable(columnName, tableType.table().tableName());
    }

    Optional<ColumnRef> resolveColumnForReference(String columnName, List<JoinStep> path, TableBackedType sourceType) {
        return resolveColumnForReference(columnName, path, sourceType.table().tableName());
    }

    Optional<ColumnRef> resolveColumnForReference(String columnName, List<JoinStep> path, String startSqlTableName) {
        String terminal = terminalTableSqlName(path, startSqlTableName);
        if (terminal == null) return Optional.empty();
        return resolveColumnInTable(columnName, terminal);
    }

    /**
     * Walks the FK join path from {@code startSqlTableName} and returns the terminal table SQL
     * name. Returns {@code null} when any path step is not a {@link FkJoin} (i.e. the path
     * contains a condition-only step whose target table is unknown at build time).
     */
    String terminalTableSqlName(List<JoinStep> path, String startSqlTableName) {
        String current = startSqlTableName;
        for (var step : path) {
            if (!(step instanceof FkJoin fk)) return null;
            current = fk.targetTable().tableName();
        }
        return current;
    }

    /**
     * Walks the FK join path to compute the terminal table SQL name. Returns {@code null} when any
     * path step is not a {@link FkJoin} (i.e. the path contains a condition-only step).
     */
    String terminalTableSqlNameForReference(List<JoinStep> path, TableBackedType sourceType) {
        return terminalTableSqlName(path, sourceType.table().tableName());
    }

    Optional<ColumnRef> resolveColumnInTable(String columnName, String tableSqlName) {
        return ctx.catalog.findColumn(tableSqlName, columnName)
            .map(e -> new ColumnRef(e.sqlName(), e.javaName(), e.columnClass()));
    }

    /**
     * Returns the SQL table name for a GraphQL type name when the type is table-backed, or
     * {@code null} when the type has no associated table.
     */
    String getTableSqlNameForType(String typeName) {
        var type = ctx.types.get(typeName);
        if (type instanceof TableBackedType tbt) return tbt.table().tableName();
        return null;
    }

    // ===== Service method reflection =====

    /**
     * Loads the service class and method via reflection and classifies each parameter.
     *
     * <p>{@code argBindings} maps each Java parameter name that should bind to a GraphQL argument
     * to that argument's name. Constructed by the caller via {@link ArgBindingMap#of}; identity
     * entries cover the no-override case, override entries carry the {@code argMapping} override.
     *
     * <p>Parameters whose name appears as a key in the binding map get {@link ParamSource.Arg}
     * with {@link ParamSource.Arg#graphqlArgName()} set to the corresponding value; parameters
     * whose name matches a context key get {@link ParamSource.Context}; all others are classified
     * by {@link #classifySourcesType}.
     *
     * <p>If a key in the binding map that constitutes an explicit override ({@code key != value})
     * does not appear among the resolved method's parameter names, the method fails with a
     * typo-guard message naming the directive site, the override target, and the available
     * parameter names.
     *
     * <p>{@code parentPkColumns} is the primary-key column list of the parent type's table.
     * Pass {@link List#of()} when the parent is a root operation type or has no backing table.
     *
     * <p>If the compiler was not invoked with {@code -parameters}, any parameter may lack a name.
     * A warning is logged proactively as soon as any nameless parameter is detected.
     *
     * <p>{@code expectedReturnType} (when non-null) is the structured javapoet
     * {@link TypeName} the method's generic return type must equal exactly (e.g.
     * {@code Result<FilmRecord>} for a List-cardinality {@code @table}-bound return).
     * Mismatched return types fail classification with a message naming the expected vs
     * actual type. Pass {@code null} for cases where strict validation isn't applicable
     * (e.g. {@code ScalarReturnType} or {@code ResultReturnType} with no backing class).
     * Comparison is via {@link TypeName#equals(Object)} so it is whitespace-tolerant and
     * structurally exact (a wildcard {@code ? extends Foo} is not equal to {@code Foo}).
     * The captured return type stored on the resulting {@link MethodRef.Service} is always
     * the parameterised form so emitters can declare matching fetcher return types
     * directly without parsing a string.
     */
    ServiceReflectionResult reflectServiceMethod(String className, String methodName,
            ArgBindingMap argBindings, Set<String> ctxKeys, List<ColumnRef> parentPkColumns,
            TypeName expectedReturnType) {
        return reflectServiceMethod(className, methodName, argBindings, ctxKeys,
            parentPkColumns, expectedReturnType, Map.of());
    }

    /**
     * Suggestion-aware overload: identical to the 6-arg version, but accepts
     * {@code slotTypes} so a parameter-mismatch rejection can pre-fill an unambiguous
     * reachable path under one of the available slots in its argMapping suggestion.
     *
     * <p>The 6-arg overload delegates here with {@code Map.of()}; tests that don't care about
     * the prefilled-path hint stay on the simpler form. The single production caller
     * ({@link ServiceDirectiveResolver}) threads the real slot types from
     * {@link FieldBuilder#argSlotTypes(graphql.schema.GraphQLFieldDefinition)} so that the
     * suggestion message rendered to schema authors carries a copy-pasteable path when one
     * is unambiguously reachable.
     */
    ServiceReflectionResult reflectServiceMethod(String className, String methodName,
            ArgBindingMap argBindings, Set<String> ctxKeys, List<ColumnRef> parentPkColumns,
            TypeName expectedReturnType, Map<String, GraphQLInputType> slotTypes) {
        var argByJavaName = argBindings.byJavaName();
        if (className == null || methodName == null) {
            return new ServiceReflectionResult(null, Rejection.structural("service reference is incomplete"));
        }
        try {
            Class<?> cls = Class.forName(className, false, ctx.codegenLoader());
            var methods = Arrays.stream(cls.getDeclaredMethods())
                .filter(m -> m.getName().equals(methodName))
                .toList();
            if (methods.isEmpty()) {
                var declaredMethodNames = Arrays.stream(cls.getDeclaredMethods())
                    .map(java.lang.reflect.Method::getName)
                    .distinct()
                    .toList();
                return new ServiceReflectionResult(null,
                    Rejection.unknownServiceMethod(
                        "method '" + methodName + "' not found in class '" + className + "'",
                        methodName, declaredMethodNames));
            }
            var javaMethod = methods.get(0);
            TypeName actualReturnType = TypeName.get(javaMethod.getGenericReturnType());
            if (expectedReturnType != null
                    && !actualReturnType.equals(expectedReturnType)) {
                return new ServiceReflectionResult(null,
                    Rejection.structural("method '" + methodName + "' in class '" + className
                    + "' must return '" + TypeNames.simple(expectedReturnType)
                    + "' to match the field's declared return type — got '" + TypeNames.simple(actualReturnType) + "'"));
            }
            boolean isStatic = java.lang.reflect.Modifier.isStatic(javaMethod.getModifiers());
            if (!isStatic) {
                String instanceRejection = checkServiceInstanceHolderShape(cls, methodName, className);
                if (instanceRejection != null) {
                    return new ServiceReflectionResult(null, Rejection.structural(instanceRejection));
                }
            }
            if (Arrays.stream(javaMethod.getParameters()).anyMatch(p -> !p.isNamePresent())) {
                emitParametersWarning();
            }
            String typoGuard = checkOverrideTargets(argByJavaName, javaMethod, methodName, className);
            if (typoGuard != null) {
                return new ServiceReflectionResult(null, Rejection.structural(typoGuard));
            }
            argByJavaName = inferBindingsByType(javaMethod, argByJavaName, ctxKeys, slotTypes);
            var params = new ArrayList<MethodRef.Param>();
            for (var p : javaMethod.getParameters()) {
                if (org.jooq.DSLContext.class.isAssignableFrom(p.getType())) {
                    String paramName = p.isNamePresent() ? p.getName() : "dsl";
                    params.add(new MethodRef.Param.Typed(paramName,
                        p.getParameterizedType().getTypeName(),
                        TypeName.get(p.getParameterizedType()),
                        new ParamSource.DslContext()));
                    continue;
                }
                String pName = p.isNamePresent() ? p.getName() : null;
                String displayName = pName != null ? pName : p.getType().getSimpleName();
                String typeName = p.getParameterizedType().getTypeName();
                TypeName javaType = TypeName.get(p.getParameterizedType());
                PathExpr resolvedPath = pName != null ? argByJavaName.get(pName) : null;
                if (resolvedPath != null) {
                    params.add(new MethodRef.Param.Typed(displayName, typeName, javaType,
                        new ParamSource.Arg(argExtraction(typeName, ctx.codegenLoader()), resolvedPath)));
                } else if (pName != null && ctxKeys.contains(pName)) {
                    params.add(new MethodRef.Param.Typed(displayName, typeName, javaType, new ParamSource.Context()));
                } else {
                    Optional<SourcesShape> sourcesShape = classifySourcesType(p.getParameterizedType(), parentPkColumns);
                    if (sourcesShape.isEmpty()) {
                        if (pName == null) {
                            return new ServiceReflectionResult(null,
                                Rejection.structural("parameter names not available for method '" + methodName + "' in class '" + className
                                + "' — compile with -parameters flag (see warning above for instructions)"));
                        }
                        // The discriminator is the parameter type axis, not the coordinate. The
                        // parameter must either match a GraphQL argument / context key (handled
                        // above), classify as SOURCES (handled by classifySourcesType), or land in
                        // one of the diagnostics below. parentPkColumns only gates which SOURCES
                        // outcomes are reachable at a given coordinate; it does not gate the
                        // name-mismatch diagnostic, which applies wherever the parameter is clearly
                        // not SOURCES-adjacent (root, DTO-parent child, or @table-parent child with
                        // a non-container type like LocalDate / String / Integer).
                        // Anonymous-key SOURCES shapes (List<RowN> / List<RecordN>) at root get the
                        // dedicated batch-at-root diagnostic — `@service at the root does not
                        // support List<Row>/List<Record> batch parameters`. List<TableRecord> at
                        // root is the canonical InputBeanResolver shape and falls through to the
                        // arg-mismatch arm if the parameter name doesn't bind (R185 narrowed
                        // looksLikeSourcesShape so TableRecord is excluded). classifySourcesType
                        // returns empty for parentPkColumns.isEmpty(), so the detection happens
                        // here on the parameter type directly.
                        if (parentPkColumns.isEmpty() && looksLikeSourcesShape(p.getParameterizedType())) {
                            return new ServiceReflectionResult(null,
                                Rejection.structural("@service at the root does not support "
                                + "List<Row>/List<Record> batch parameters — the root "
                                + "has no parent context to batch against"));
                        }
                        // DTO-shape parameters (List<DTO> / Set<DTO>) at child coordinates keep
                        // precedence over the name-mismatch arm: the @sourceRow lifter-directive
                        // hint is genuinely actionable here (DataLoader batching applies, and the
                        // missing piece is a DTO-to-key conversion). At root coordinates the gate
                        // flips: List<DTO> there has no batching context, so the arg-mismatch arm
                        // wins (pinned by dtoSources_onRootField_pointsAtArgCtxMismatch).
                        if (!parentPkColumns.isEmpty()) {
                            String dtoReason = dtoSourcesRejectionReason(p.getParameterizedType());
                            if (dtoReason != null) {
                                return new ServiceReflectionResult(null,
                                    Rejection.structural("parameter '" + displayName + "' in method '" + methodName + "': " + dtoReason));
                            }
                        }
                        // Non-SOURCES-adjacent parameter that didn't match any argument / context
                        // key: the only plausible diagnosis is a name mismatch (or a missing
                        // context key). Fires at any coordinate — root, DTO-parent child, or
                        // @table-parent child with a non-container type (R187).
                        if (!looksLikeSourcesShape(p.getParameterizedType())) {
                            String available = formatNameSet(argByJavaName.keySet());
                            String suggestion;
                            if (argByJavaName.isEmpty()) {
                                suggestion = " — this field declares no GraphQL arguments;"
                                    + " remove the Java parameter, add a matching GraphQL argument to the field,"
                                    + " or register a context key that supplies it";
                            } else {
                                String soleArg = argByJavaName.size() == 1
                                    ? argByJavaName.keySet().iterator().next()
                                    : "<argName>";
                                String reachablePath = unambiguousReachablePath(typeName, slotTypes);
                                String pathExample;
                                String pathTrailer;
                                if (reachablePath != null) {
                                    pathExample = "argMapping: \"" + displayName + ": " + reachablePath + "\"";
                                    pathTrailer = " — that path is the only field reachable from the available"
                                        + " arguments whose type matches '" + typeName + "', so the suggestion"
                                        + " is concrete";
                                } else {
                                    pathExample = "argMapping: \"" + displayName + ": " + soleArg + ".<fieldName>\"";
                                    pathTrailer = " when the parameter pulls one field out of a wrapper input"
                                        + " type";
                                }
                                suggestion = " — either rename the Java parameter to match one of the available argument names, or bind explicitly via the @service directive's argMapping field"
                                    + " (e.g. argMapping: \"" + displayName + ": " + soleArg + "\""
                                    + ", which reads as \"the Java parameter named '" + displayName
                                    + "' binds to the GraphQL argument named '" + soleArg + "'\")."
                                    + " The right-hand side may also be a dot-path into a nested"
                                    + " input field (e.g. " + pathExample + ")"
                                    + pathTrailer;
                            }
                            return new ServiceReflectionResult(null,
                                Rejection.structural("parameter '" + displayName + "' in method '" + methodName
                                + "' does not match any GraphQL argument or context key on this field"
                                + " — available GraphQL arguments: " + available
                                + "; available context keys: " + formatNameSet(ctxKeys)
                                + suggestion));
                        }
                        return new ServiceReflectionResult(null,
                            Rejection.structural("parameter '" + displayName + "' in method '" + methodName
                            + "' has an unrecognized sources type: '" + typeName + "'"));
                    }
                    SourcesShape shape = sourcesShape.get();
                    params.add(new MethodRef.Param.Sourced(
                        displayName, shape.wrap(), parentPkColumns, shape.container()));
                }
            }
            MethodRef.CallShape callShape;
            if (isStatic) {
                boolean needsDslLocal = params.stream()
                    .anyMatch(p -> p.source() instanceof ParamSource.DslContext);
                callShape = new MethodRef.CallShape.Static(needsDslLocal);
            } else {
                callShape = new MethodRef.CallShape.InstanceWithDslHolder();
            }
            return new ServiceReflectionResult(
                new MethodRef.Service(className, methodName, actualReturnType, List.copyOf(params),
                    declaredExceptionFqns(javaMethod), callShape),
                null);
        } catch (ClassNotFoundException e) {
            return new ServiceReflectionResult(null, Rejection.structural("class '" + className + "' could not be loaded"));
        }
    }

    /**
     * Validates that an instance {@code @service} method's enclosing class is a usable holder:
     * the class must be concrete (not abstract or an interface) and expose a public constructor
     * taking exactly one {@link org.jooq.DSLContext} parameter (matching the legacy generator's
     * {@code new Service(ctx)} pattern). Returns {@code null} when the shape is acceptable, or a
     * concrete actionable rejection message naming the option to either make the method
     * {@code static} or add the constructor.
     *
     * <p>The single-DSLContext-constructor contract mirrors the legacy generator's
     * {@code new ServiceName(_iv_transform.getCtx())} call site; widening to other constructor
     * shapes (no-arg, multi-param) is out of scope here and would need its own design pass.
     */
    private static String checkServiceInstanceHolderShape(Class<?> cls, String methodName, String className) {
        int classMods = cls.getModifiers();
        if (java.lang.reflect.Modifier.isAbstract(classMods) || cls.isInterface()) {
            return "method '" + methodName + "' in class '" + className
                + "' is an instance method, but its enclosing class is abstract or an interface"
                + " — make the method static, or move it to a concrete class with a public"
                + " constructor taking a single org.jooq.DSLContext parameter";
        }
        boolean hasDslCtor = Arrays.stream(cls.getDeclaredConstructors())
            .filter(c -> java.lang.reflect.Modifier.isPublic(c.getModifiers()))
            .anyMatch(c -> c.getParameterCount() == 1
                && org.jooq.DSLContext.class.isAssignableFrom(c.getParameterTypes()[0]));
        if (!hasDslCtor) {
            return "method '" + methodName + "' in class '" + className
                + "' is an instance method but the class has no public constructor taking a"
                + " single org.jooq.DSLContext parameter — add `public " + cls.getSimpleName()
                + "(DSLContext ctx)`, or make the method static";
        }
        return null;
    }

    /**
     * Captures the developer method's declared exception classes as FQNs, in source order.
     * Feeds {@link MethodRef#declaredExceptions()} so the classifier's match rule can
     * verify each declared exception is covered by an {@code @error} handler on the field's
     * channel. Returns the empty list when the method has no {@code throws} clause.
     */
    private static List<String> declaredExceptionFqns(java.lang.reflect.Method m) {
        return Arrays.stream(m.getExceptionTypes())
            .map(Class::getName)
            .toList();
    }

    /**
     * Post-reflection typo guard for {@code argMapping} overrides on argument sites.
     *
     * <p>Iterates {@code argByJavaName} entries that constitute explicit overrides
     * ({@code javaTarget != graphqlArgName}) and verifies each {@code javaTarget} is among the
     * resolved method's parameter names. Returns a failure message when any override target is
     * absent, naming the directive site (the GraphQL argument name), the override target, and
     * the actual parameter list. Returns {@code null} when every override target resolves.
     *
     * <p>Identity entries ({@code javaTarget == graphqlArgName}) skip this guard: an unresolved
     * identity entry produces the existing per-parameter "does not match any GraphQL argument"
     * error inside the main loop, which is already actionable.
     */
    private static String checkOverrideTargets(Map<String, PathExpr> argByJavaName,
                                               java.lang.reflect.Method javaMethod,
                                               String methodName, String className) {
        var paramNames = Arrays.stream(javaMethod.getParameters())
            .filter(java.lang.reflect.Parameter::isNamePresent)
            .map(java.lang.reflect.Parameter::getName)
            .collect(Collectors.toCollection(java.util.LinkedHashSet::new));
        for (var entry : argByJavaName.entrySet()) {
            String javaTarget = entry.getKey();
            PathExpr path = entry.getValue();
            if (path.isHead() && javaTarget.equals(path.headName())) continue;
            if (!paramNames.contains(javaTarget)) {
                return "argMapping entry '" + javaTarget + ": " + path.asString()
                    + "' references Java parameter '" + javaTarget
                    + "', but method '" + methodName + "' in class '" + className
                    + "' has parameters " + formatNameSet(paramNames);
            }
        }
        return null;
    }

    /**
     * Table-parameter policy for {@link #reflectTableMethod}, distinguishing the two callers.
     *
     * <ul>
     *   <li>{@link #REQUIRED} — {@code @condition}: the method's first slot is the parent
     *       {@code Table<?>}; reflection must find exactly one Table parameter and emit it as
     *       {@link ParamSource.Table}. argMapping entries targeting the Table slot are rejected
     *       (the reserved-slot typo guard).</li>
     *   <li>{@link #FORBIDDEN} — {@code @tableMethod} (after R43): the developer's method
     *       receives GraphQL field arguments and context values only; graphitron derives the
     *       target table from the method's return type. Any {@code Table<?>} parameter is
     *       rejected outright.</li>
     * </ul>
     */
    enum TableSlotPolicy { REQUIRED, FORBIDDEN }

    /**
     * Loads the table-method class and method via reflection and classifies each parameter.
     *
     * <p>Parameters whose name appears as a key in {@code argBindings} get {@link ParamSource.Arg}
     * with {@link ParamSource.Arg#graphqlArgName()} set to the corresponding value;
     * parameters whose name matches a context key get {@link ParamSource.Context}.
     * The {@link TableSlotPolicy} governs how {@code Table<?>}-typed parameters are handled:
     * {@code REQUIRED} (the {@code @condition} caller) treats them as the parent Table slot and
     * emits {@link ParamSource.Table}; {@code FORBIDDEN} (the {@code @tableMethod} caller after
     * R43) rejects them. Any other parameter shape is an error.
     *
     * <p>{@code argBindings} carries the Java-target → GraphQL-arg-name mapping per
     * {@link #reflectServiceMethod}. Override entries pointing at non-existent Java parameters are
     * rejected with a typo-guard message naming the directive site and the available parameter names.
     * Under {@code REQUIRED}, an override entry targeting the Table parameter is additionally
     * rejected by {@link #checkConditionOverrideTargets}.
     *
     * <p>If the compiler was not invoked with {@code -parameters}, any parameter may lack a name.
     * A warning is logged proactively as soon as any nameless parameter is detected — even if
     * type-based classification would otherwise succeed — so that the user is notified regardless
     * of whether all parameters happen to have distinct types.
     *
     * <p>The method's return type must match {@code expectedReturnClass} exactly (the
     * {@link ClassName} of the generated jOOQ table class for the field's {@code @table}-bound
     * return type). Wider return types like {@code Table<R>} are rejected; the emitter relies
     * on the strict type so the generated fetcher's local can carry the specific table class
     * (e.g. {@code Film table = Service.method(...)}) and feed it into
     * {@code FilmType.$fields(...)} without a downcast.
     */
    ServiceReflectionResult reflectTableMethod(String className, String methodName,
            ArgBindingMap argBindings, Set<String> ctxKeys, ClassName expectedReturnClass,
            TableSlotPolicy tableSlotPolicy) {
        return reflectTableMethod(className, methodName, argBindings, ctxKeys,
            expectedReturnClass, tableSlotPolicy, Map.of());
    }

    /**
     * Slot-types-aware overload of {@link #reflectTableMethod}. {@code slotTypes} carries the
     * GraphQL slots in scope at the directive site (single argument for argument-level
     * {@code @condition}, every field argument for field-level {@code @condition}), and feeds
     * {@link #inferBindingsByType} so an unbound Java parameter whose type uniquely matches a
     * single unclaimed slot binds positionally without requiring an {@code argMapping} entry.
     */
    ServiceReflectionResult reflectTableMethod(String className, String methodName,
            ArgBindingMap argBindings, Set<String> ctxKeys, ClassName expectedReturnClass,
            TableSlotPolicy tableSlotPolicy, Map<String, GraphQLInputType> slotTypes) {
        var argByJavaName = argBindings.byJavaName();
        if (className == null || methodName == null) {
            return new ServiceReflectionResult(null, Rejection.structural("table method reference is incomplete"));
        }
        try {
            Class<?> cls = Class.forName(className, false, ctx.codegenLoader());
            var methods = Arrays.stream(cls.getDeclaredMethods())
                .filter(m -> m.getName().equals(methodName))
                .toList();
            if (methods.isEmpty()) {
                var declaredMethodNames = Arrays.stream(cls.getDeclaredMethods())
                    .map(java.lang.reflect.Method::getName)
                    .distinct()
                    .toList();
                return new ServiceReflectionResult(null,
                    Rejection.unknownServiceMethod(
                        "method '" + methodName + "' not found in class '" + className + "'",
                        methodName, declaredMethodNames));
            }
            var javaMethod = methods.get(0);
            if (!java.lang.reflect.Modifier.isStatic(javaMethod.getModifiers())) {
                return new ServiceReflectionResult(null,
                    Rejection.structural("method '" + methodName + "' in class '" + className
                    + "' must be declared 'static' for @tableMethod — instance @tableMethod methods are not supported;"
                    + " the call site emits 'ClassName.method(...)' which requires a static method"));
            }
            ClassName actualReturnClass = ClassName.get(javaMethod.getReturnType());
            if (expectedReturnClass != null
                    && !actualReturnClass.equals(expectedReturnClass)) {
                return new ServiceReflectionResult(null,
                    Rejection.structural("method '" + methodName + "' in class '" + className
                    + "' must return the generated jOOQ table class '" + TypeNames.simple(expectedReturnClass)
                    + "' for @tableMethod with a @table-bound return type — got '"
                    + TypeNames.simple(actualReturnClass) + "'"));
            }
            if (Arrays.stream(javaMethod.getParameters()).anyMatch(p -> !p.isNamePresent())) {
                emitParametersWarning();
            }
            String tableTypoGuard = tableSlotPolicy == TableSlotPolicy.REQUIRED
                ? checkConditionOverrideTargets(argByJavaName, javaMethod, methodName, className)
                : checkOverrideTargets(argByJavaName, javaMethod, methodName, className);
            if (tableTypoGuard != null) {
                return new ServiceReflectionResult(null, Rejection.structural(tableTypoGuard));
            }
            argByJavaName = inferBindingsByType(javaMethod, argByJavaName, ctxKeys, slotTypes);
            var params = new ArrayList<MethodRef.Param>();
            boolean foundTable = false;
            for (var p : javaMethod.getParameters()) {
                if (org.jooq.Table.class.isAssignableFrom(p.getType())) {
                    if (tableSlotPolicy == TableSlotPolicy.FORBIDDEN) {
                        String paramName = p.isNamePresent() ? p.getName() : "<unnamed>";
                        return new ServiceReflectionResult(null,
                            Rejection.structural("parameter '" + paramName + "' in method '" + methodName
                            + "' in class '" + className + "' is a Table<?> — @tableMethod must not declare"
                            + " a Table parameter; graphitron derives the target table from the method's return type"
                            + " and parent-table filtering is handled via @reference"));
                    }
                    String paramName = p.isNamePresent() ? p.getName() : "table";
                    params.add(new MethodRef.Param.Typed(paramName,
                        p.getParameterizedType().getTypeName(),
                        TypeName.get(p.getParameterizedType()),
                        new ParamSource.Table()));
                    foundTable = true;
                    continue;
                }
                String pName = p.isNamePresent() ? p.getName() : null;
                if (pName == null) {
                    return new ServiceReflectionResult(null,
                        Rejection.structural("parameter names not available for method '" + methodName + "' in class '" + className
                        + "' — compile with -parameters flag (see warning above for instructions)"));
                }
                String typeName = p.getParameterizedType().getTypeName();
                TypeName javaType = TypeName.get(p.getParameterizedType());
                PathExpr resolvedPath = argByJavaName.get(pName);
                if (resolvedPath != null) {
                    params.add(new MethodRef.Param.Typed(pName, typeName, javaType,
                        new ParamSource.Arg(argExtraction(typeName, ctx.codegenLoader()), resolvedPath)));
                } else if (ctxKeys.contains(pName)) {
                    params.add(new MethodRef.Param.Typed(pName, typeName, javaType, new ParamSource.Context()));
                } else {
                    return new ServiceReflectionResult(null,
                        Rejection.structural("parameter '" + pName + "' in method '" + methodName
                        + "' is not a GraphQL argument and not a context key"));
                }
            }
            if (tableSlotPolicy == TableSlotPolicy.REQUIRED && !foundTable) {
                return new ServiceReflectionResult(null,
                    Rejection.structural("method '" + methodName + "' in class '" + className
                    + "' has no Table<?> parameter — the directive requires exactly one Table<?> parameter"));
            }
            return new ServiceReflectionResult(
                new MethodRef.StaticOnly(className, methodName,
                    ClassName.get(javaMethod.getReturnType()), List.copyOf(params),
                    declaredExceptionFqns(javaMethod)),
                null);
        } catch (ClassNotFoundException e) {
            return new ServiceReflectionResult(null, Rejection.structural("class '" + className + "' could not be loaded"));
        }
    }

    /**
     * Reflects on a developer-supplied {@code @externalField} method.
     *
     * <p>Contract: the method must be {@code public static}, take exactly one parameter
     * assignable from the parent's jOOQ {@code Table<?>} class, and return parameterised
     * {@code org.jooq.Field<X>}. The captured return TypeName preserves the parameterised
     * shape so the generated {@code $fields()} body compiles cleanly when projecting against
     * a {@code List<Field<?>>}.
     *
     * <p>Mirrors {@link #reflectTableMethod} but with a stricter return-type rule (must be
     * {@code Field}, not the wider {@code Table<?>} that {@code @tableMethod} accepts) and a
     * fixed param shape (exactly one Table<?>, no GraphQL args, no context args).
     *
     * <p>Both {@code className} and {@code methodName} are required: the {@code @externalField}
     * arm in {@code FieldBuilder} surfaces a targeted "missing className" error before this call
     * and defaults {@code methodName} to the GraphQL field name when the directive omits
     * {@code method:}. Empty-reference and named-reference-lookup-failure cases never reach this
     * method.
     */
    ServiceReflectionResult reflectExternalField(String className, String methodName,
            ClassName parentTableClass) {
        try {
            Class<?> cls = Class.forName(className, false, ctx.codegenLoader());
            var methods = Arrays.stream(cls.getDeclaredMethods())
                .filter(m -> m.getName().equals(methodName))
                .toList();
            if (methods.isEmpty()) {
                var declaredMethodNames = Arrays.stream(cls.getDeclaredMethods())
                    .map(java.lang.reflect.Method::getName)
                    .distinct()
                    .toList();
                return new ServiceReflectionResult(null,
                    Rejection.unknownServiceMethod(
                        "method '" + methodName + "' not found in class '" + className + "'",
                        methodName, declaredMethodNames));
            }
            var javaMethod = methods.get(0);
            int mods = javaMethod.getModifiers();
            if (!java.lang.reflect.Modifier.isStatic(mods) || !java.lang.reflect.Modifier.isPublic(mods)) {
                return new ServiceReflectionResult(null,
                    Rejection.structural("method '" + methodName + "' in class '" + className
                    + "' must be public static"));
            }
            if (javaMethod.getParameterCount() != 1) {
                return new ServiceReflectionResult(null,
                    Rejection.structural("method '" + methodName + "' in class '" + className
                    + "' must take exactly one Table<?> parameter — got "
                    + javaMethod.getParameterCount() + " parameter(s)"));
            }
            var p = javaMethod.getParameters()[0];
            if (!org.jooq.Table.class.isAssignableFrom(p.getType())) {
                return new ServiceReflectionResult(null,
                    Rejection.structural("method '" + methodName + "' in class '" + className
                    + "' parameter must be a jOOQ Table<?> subtype — got '"
                    + p.getType().getSimpleName() + "'"));
            }
            if (!org.jooq.Field.class.equals(javaMethod.getReturnType())) {
                return new ServiceReflectionResult(null,
                    Rejection.structural("method '" + methodName + "' in class '" + className
                    + "' must return org.jooq.Field<X> — got '"
                    + javaMethod.getReturnType().getSimpleName() + "'"));
            }
            var genericReturn = javaMethod.getGenericReturnType();
            if (!(genericReturn instanceof java.lang.reflect.ParameterizedType)) {
                return new ServiceReflectionResult(null,
                    Rejection.structural("method '" + methodName + "' in class '" + className
                    + "' must return parameterized Field<X>, not raw Field"));
            }
            if (!p.isNamePresent()) {
                emitParametersWarning();
            }
            String paramName = p.isNamePresent() ? p.getName() : "table";
            List<MethodRef.Param> params = List.of(new MethodRef.Param.Typed(
                paramName, p.getParameterizedType().getTypeName(),
                TypeName.get(p.getParameterizedType()),
                new ParamSource.Table()));
            TypeName returnTypeName = TypeName.get(genericReturn);
            return new ServiceReflectionResult(
                new MethodRef.StaticOnly(className, methodName, returnTypeName, params, List.of()),
                null);
        } catch (ClassNotFoundException e) {
            return new ServiceReflectionResult(null, Rejection.structural("class '" + className + "' could not be loaded"));
        }
    }

    /**
     * Override-target check for {@link TableSlotPolicy#REQUIRED} callers ({@code @condition}):
     * rejects argMapping entries that target the reserved {@code Table<?>} parameter slot, then
     * defers to {@link #checkOverrideTargets} for missing-parameter detection. Mirrors the legacy
     * {@code checkTableMethodOverrideTargets} that {@code @tableMethod} no longer needs after R43.
     */
    private static String checkConditionOverrideTargets(Map<String, PathExpr> argByJavaName,
                                                        java.lang.reflect.Method javaMethod,
                                                        String methodName, String className) {
        var tableParamNames = Arrays.stream(javaMethod.getParameters())
            .filter(p -> org.jooq.Table.class.isAssignableFrom(p.getType()))
            .filter(java.lang.reflect.Parameter::isNamePresent)
            .map(java.lang.reflect.Parameter::getName)
            .collect(Collectors.toCollection(java.util.LinkedHashSet::new));
        for (var entry : argByJavaName.entrySet()) {
            String javaTarget = entry.getKey();
            PathExpr path = entry.getValue();
            if (path.isHead() && javaTarget.equals(path.headName())) continue;
            if (tableParamNames.contains(javaTarget)) {
                return "argMapping entry '" + javaTarget + ": " + path.asString()
                    + "' targets the Table<?> parameter of method '" + methodName
                    + "' in class '" + className + "' — the Table<?> slot is reserved and cannot be"
                    + " bound to a GraphQL argument";
            }
        }
        return checkOverrideTargets(argByJavaName, javaMethod, methodName, className);
    }

    private void emitParametersWarning() {
        if (!parametersWarningEmitted) {
            parametersWarningEmitted = true;
            LOGGER.warn("Parameter names are not available — the class was compiled without the -parameters flag.\n"
                + "  To fix: set <parameters>true</parameters> on maven-compiler-plugin in your pom.xml:\n"
                + "    <plugin>\n"
                + "      <groupId>org.apache.maven.plugins</groupId>\n"
                + "      <artifactId>maven-compiler-plugin</artifactId>\n"
                + "      <configuration>\n"
                + "        <parameters>true</parameters>\n"
                + "      </configuration>\n"
                + "    </plugin>");
        }
    }

    /**
     * Returns the {@link CallSiteExtraction} for a GraphQL {@code Arg} parameter based on its
     * Java type. jOOQ-generated enums get {@link CallSiteExtraction.EnumValueOf}; all other
     * types default to {@link CallSiteExtraction.Direct}.
     *
     * <p>Text-mapped enums (GraphQL enum bound to a varchar column via {@code @field(name:)})
     * route through {@code Direct}: graphql-java translates the wire form to the runtime form
     * at the boundary via {@code GraphQLEnumValueDefinition.value(...)} (R229), so resolvers
     * receive the runtime string and no extra extraction step is needed.
     */
    static CallSiteExtraction argExtraction(String typeName, ClassLoader codegenLoader) {
        try {
            if (Class.forName(typeName, false, codegenLoader).isEnum()) {
                return new CallSiteExtraction.EnumValueOf(typeName);
            }
        } catch (ClassNotFoundException ignored) {}
        return new CallSiteExtraction.Direct();
    }

    /**
     * Returns true if the parameter type is a {@code List<X>} or {@code Set<X>} where {@code X}
     * is a {@code RowN} or {@code RecordN}. Used by the root-op diagnostic to detect the
     * anonymous-key SOURCES-shape parameters that {@link #classifySourcesType} cannot fully
     * classify because the parent has no PK to populate the source key. Concrete
     * {@code TableRecord} subclasses are intentionally excluded: at root, {@code List<XRecord>}
     * is the canonical {@code InputBeanResolver} shape ("list of input objects mapped to
     * records"), so it must fall through to the arg-mismatch diagnostic when the parameter
     * name doesn't bind to a GraphQL argument.
     */
    private static boolean looksLikeSourcesShape(java.lang.reflect.Type paramType) {
        var split = peelContainer(paramType, java.util.EnumSet.of(ContainerKind.LIST, ContainerKind.SET));
        if (split.isEmpty()) return false;
        java.lang.reflect.Type elementType = split.get().elementType();
        if (elementType instanceof java.lang.reflect.ParameterizedType ept
                && ept.getRawType() instanceof Class<?> rawClass) {
            String rawName = rawClass.getName();
            if (rawName.startsWith("org.jooq.Row")
                    && rawName.substring("org.jooq.Row".length()).matches("\\d+")) return true;
            if (rawName.startsWith("org.jooq.Record")
                    && rawName.substring("org.jooq.Record".length()).matches("\\d+")) return true;
        }
        return false;
    }

    /**
     * Source-shape classification result for a {@code @service} Java method's
     * {@code List<RowN<...>>} / {@code Set<RowN<...>>} / {@code List<RecordN<...>>} /
     * {@code Set<RecordN<...>>} / {@code List<X extends TableRecord<X>>} /
     * {@code Set<X extends TableRecord<X>>} SOURCES parameter. Carries the two axes the producer
     * needs to construct {@link MethodRef.Param.Sourced}: the per-row shape
     * ({@link SourceKey.Wrap}) and the container axis ({@link LoaderRegistration.Container}).
     * The columns axis is the caller's {@code parentPkColumns} input and so is not repeated here.
     */
    record SourcesShape(SourceKey.Wrap wrap, LoaderRegistration.Container container) {}

    /**
     * Classifies the element type of a {@code List<?>} or {@code Set<?>} SOURCES parameter into
     * a {@link SourcesShape}, or returns {@link Optional#empty()} when the type is not
     * recognised or when {@code parentPkColumns} is empty (root-op case: the diagnostic is
     * produced upstream by {@link #looksLikeSourcesShape}).
     *
     * <p>The container axis ({@code List} vs {@code Set}) maps onto
     * {@link LoaderRegistration.Container#POSITIONAL_LIST} vs
     * {@link LoaderRegistration.Container#MAPPED_SET}; the element-shape axis (one of
     * {@code RowN}, {@code RecordN}, or {@code X extends TableRecord}) maps onto
     * {@link SourceKey.Wrap.Row}, {@link SourceKey.Wrap.Record}, or
     * {@link SourceKey.Wrap.TableRecord} carrying the developer's typed
     * {@code TableRecord} subclass.
     */
    static Optional<SourcesShape> classifySourcesType(java.lang.reflect.Type paramType,
            List<ColumnRef> parentPkColumns) {
        if (parentPkColumns.isEmpty()) {
            return Optional.empty();
        }
        var split = peelContainer(paramType, java.util.EnumSet.of(ContainerKind.LIST, ContainerKind.SET));
        if (split.isEmpty()) {
            return Optional.empty();
        }
        boolean isSet = split.get().container() == ContainerKind.SET;
        LoaderRegistration.Container container = isSet
            ? LoaderRegistration.Container.MAPPED_SET
            : LoaderRegistration.Container.POSITIONAL_LIST;
        java.lang.reflect.Type elementType = split.get().elementType();

        if (elementType instanceof java.lang.reflect.ParameterizedType ept
                && ept.getRawType() instanceof Class<?> rawClass) {
            String rawName = rawClass.getName();
            if (rawName.startsWith("org.jooq.Row")) {
                String suffix = rawName.substring("org.jooq.Row".length());
                if (suffix.matches("\\d+")) {
                    return Optional.of(new SourcesShape(new SourceKey.Wrap.Row(), container));
                }
            }
            if (rawName.startsWith("org.jooq.Record")) {
                String suffix = rawName.substring("org.jooq.Record".length());
                if (suffix.matches("\\d+")) {
                    return Optional.of(new SourcesShape(new SourceKey.Wrap.Record(), container));
                }
            }
        } else if (elementType instanceof Class<?> elementClass
                && org.jooq.TableRecord.class.isAssignableFrom(elementClass)) {
            @SuppressWarnings("unchecked")
            Class<? extends org.jooq.TableRecord<?>> tableRecordClass =
                (Class<? extends org.jooq.TableRecord<?>>) elementClass;
            return Optional.of(new SourcesShape(
                new SourceKey.Wrap.TableRecord(ClassName.get(tableRecordClass)),
                container));
        }

        return Optional.empty();
    }

    /**
     * Container axis recognised by {@link #peelContainer}. {@code SINGLE} means the type is a
     * bare class without a {@code List} / {@code Set} wrapper; the accessor-side classifier
     * accepts this, the SOURCES classifier does not.
     */
    enum ContainerKind { SINGLE, LIST, SET }

    /**
     * Result of {@link #peelContainer}: the recognised container axis and the inner element
     * {@link java.lang.reflect.Type}. Element classification (jOOQ {@code TableRecord} subtype,
     * {@code RowN} / {@code RecordN} parameterised raw, etc.) is left to the caller; this helper
     * only handles the container shape.
     */
    record ContainerSplit(ContainerKind container, java.lang.reflect.Type elementType) {}

    /**
     * Peels the container layer off a {@link java.lang.reflect.Type}. Recognises {@code List<X>}
     * and {@code Set<X>} (returns {@code LIST} / {@code SET} with the inner type as
     * {@code elementType}), and a bare {@link Class} (returns {@code SINGLE} with the class as
     * {@code elementType}). Empty for anything else (raw types, wildcards, type variables,
     * unrecognised parameterised containers, or {@code List<X, Y>}-style ill-typed shapes).
     *
     * <p>Shared between {@link #classifySourcesType} (the SOURCES classifier in {@code @service}
     * parameter reflection; SINGLE is filtered out via {@code accept}) and
     * {@code FieldBuilder.classifyAccessorReturn} (the accessor-side classifier on
     * class-backed parents; all three kinds are accepted). Both call sites remain inside
     * parse-boundary classes per the {@code rewrite-design-principles.adoc} rule on holding
     * raw reflection types only inside {@code JooqCatalog} / {@code TypeBuilder} /
     * {@code FieldBuilder} / {@code ServiceCatalog}; the helper itself lives here to honour
     * the more-general SOURCES classifier as the natural home.
     *
     * @param type the type to peel
     * @param accept the container kinds the caller wants to accept; types whose container axis
     *               is not in this set return {@link Optional#empty()}
     */
    static Optional<ContainerSplit> peelContainer(java.lang.reflect.Type type,
                                                  java.util.Set<ContainerKind> accept) {
        if (type instanceof java.lang.reflect.ParameterizedType pt
                && pt.getRawType() instanceof Class<?> rawCls) {
            ContainerKind kind;
            if (rawCls == java.util.List.class) kind = ContainerKind.LIST;
            else if (rawCls == java.util.Set.class) kind = ContainerKind.SET;
            else return Optional.empty();
            if (!accept.contains(kind)) return Optional.empty();
            java.lang.reflect.Type[] typeArgs = pt.getActualTypeArguments();
            if (typeArgs.length != 1) return Optional.empty();
            return Optional.of(new ContainerSplit(kind, typeArgs[0]));
        }
        if (type instanceof Class<?> cls && accept.contains(ContainerKind.SINGLE)) {
            return Optional.of(new ContainerSplit(ContainerKind.SINGLE, cls));
        }
        return Optional.empty();
    }

    /**
     * Formats a set of names as a sorted bracketed list, or {@code (none)} when empty. Used by
     * the parameter-classification error message on root operation fields to enumerate the
     * GraphQL arguments and context keys the parameter could have matched.
     */
    private static String formatNameSet(Set<String> names) {
        return names.isEmpty()
            ? "(none)"
            : names.stream().sorted().collect(Collectors.joining(", ", "[", "]"));
    }

    /**
     * Returns a descriptive rejection reason when {@code paramType} is a {@code List<?>} or
     * {@code Set<?>} whose element is a plain class that is not a jOOQ
     * {@link org.jooq.TableRecord} subtype, indicating DTO-parent sources are unsupported.
     * Returns {@code null} for any other shape (not a DTO rejection; handled by the generic
     * unrecognized-sources path).
     */
    private static String dtoSourcesRejectionReason(java.lang.reflect.Type paramType) {
        if (!(paramType instanceof java.lang.reflect.ParameterizedType pt)) {
            return null;
        }
        boolean isList = pt.getRawType() == java.util.List.class;
        boolean isSet = pt.getRawType() == java.util.Set.class;
        if (!isList && !isSet) {
            return null;
        }
        java.lang.reflect.Type[] typeArgs = pt.getActualTypeArguments();
        if (typeArgs.length != 1 || !(typeArgs[0] instanceof Class<?> elementClass)) {
            return null;
        }
        if (org.jooq.TableRecord.class.isAssignableFrom(elementClass)) {
            return null;
        }
        return "sources type '" + elementClass.getName() + "' is not backed by a jOOQ TableRecord"
            + " — free-form DTO sources on @service SOURCES parameters are not supported."
            + " The @sourceRow directive solves the analogous case for child fields on @record"
            + " parents (not @service SOURCES)";
    }

    // ===== Suggestion-side path search =====

    /**
     * Walks every slot in {@code slotTypes} looking for a single nested input-object field
     * whose GraphQL type maps to {@code targetTypeName} (the Java type of an unmatched method
     * parameter). Returns the dotted path (e.g. {@code "input.kvotesporsmalId"}) when exactly
     * one such field exists across all slots; returns {@code null} when there is no match or
     * more than one (so the caller falls back to the floor's {@code <fieldName>} placeholder).
     *
     * <p>The search is conservative on purpose: it only descends through non-list
     * {@link GraphQLInputObjectType} intermediates and only matches scalar leaves whose
     * GraphQL kind maps to a standard Java type (Int → Integer, Float → Double, String/ID →
     * String, Boolean → Boolean, with {@code List<>} wraps propagated). Custom scalars,
     * enums and named input objects don't count as candidate leaves; the suggestion would
     * mislead users by pointing at a path whose runtime Java shape isn't guaranteed to match.
     *
     * <p>Matching is gated on {@code targetTypeName} (the parameter's
     * {@link java.lang.reflect.Parameter#getParameterizedType()} name) equalling the
     * mapped Java type literally. {@code java.util.List<java.lang.Integer>} matches {@code [Int!]!}
     * ; {@code java.lang.Integer} matches a non-list {@code Int!}. Mismatches drop the
     * candidate silently; a non-matching path simply doesn't get suggested.
     */
    private String unambiguousReachablePath(
            String targetTypeName, Map<String, GraphQLInputType> slotTypes) {
        if (slotTypes.isEmpty()) return null;
        var matches = new ArrayList<String>(2);
        for (var entry : slotTypes.entrySet()) {
            searchSlotForMatchingPath(entry.getKey(), entry.getValue(), targetTypeName,
                new ArrayList<>(), new HashSet<>(), matches);
            if (matches.size() > 1) return null;
        }
        return matches.size() == 1 ? matches.get(0) : null;
    }

    /**
     * Recursive search helper. {@code trail} is the path of names from the slot down to (but
     * not including) the current node; {@code visited} tracks input-type names already on the
     * path so a self-referential schema can't loop. Slot-level matches are skipped because the
     * floor's {@code soleArg} placeholder already covers the head case; only paths of length ≥2
     * (slot.field, slot.f1.f2, …) are recorded.
     */
    private void searchSlotForMatchingPath(String currentName, GraphQLInputType currentType,
            String targetTypeName, List<String> trail, Set<String> visited, List<String> matches) {
        GraphQLType walk = currentType;
        while (walk instanceof GraphQLNonNull nn) {
            walk = nn.getWrappedType();
        }
        // Skip list-shaped intermediates: the path expression for an intermediate-list segment
        // produces a List<X> Java parameter shape, which is fine for emit but harder to
        // present in a one-line suggestion that doesn't surprise users. Restrict to flat paths.
        if (!(walk instanceof GraphQLInputObjectType inputObj)) return;
        if (!visited.add(inputObj.getName())) return;
        trail.add(currentName);
        try {
            for (var field : inputObj.getFields()) {
                String fieldName = field.getName();
                GraphQLInputType fieldType = field.getType();
                String javaTypeName = mapToJavaTypeName(fieldType);
                if (javaTypeName != null && javaTypeName.equals(targetTypeName)) {
                    trail.add(fieldName);
                    matches.add(String.join(".", trail));
                    trail.remove(trail.size() - 1);
                    if (matches.size() > 1) return;
                    continue;
                }
                GraphQLType inner = fieldType;
                while (inner instanceof GraphQLNonNull nn) {
                    inner = nn.getWrappedType();
                }
                if (inner instanceof GraphQLInputObjectType) {
                    searchSlotForMatchingPath(fieldName, fieldType, targetTypeName,
                        trail, visited, matches);
                    if (matches.size() > 1) return;
                }
            }
        } finally {
            trail.remove(trail.size() - 1);
            visited.remove(inputObj.getName());
        }
    }

    /**
     * Augments {@code existing} with bindings inferred from a unique pairing between unbound
     * Java parameters and unclaimed GraphQL slots. Two layered rules:
     *
     * <ol>
     *   <li><b>Arity-unique:</b> when exactly one unbound Java parameter remains AND exactly one
     *       unclaimed GraphQL slot remains, bind them positionally. This handles the canonical
     *       case where the only possible mapping is the one the developer wrote, regardless of
     *       whether the slot's GraphQL type has a canonical Java mapping (named input objects,
     *       enums, etc. all qualify).</li>
     *   <li><b>Type-unique:</b> when multiple slots / parameters remain, for each Java type
     *       {@code T} that appears exactly once among unbound parameters AND exactly once among
     *       unclaimed slots (where the slot's GraphQL type maps to {@code T} via
     *       {@link #mapToJavaTypeName}), bind that pair. Asymmetric counts (two String params,
     *       one String slot) leave the pair unbound; the caller's per-parameter loop surfaces
     *       the existing name-mismatch diagnostic.</li>
     * </ol>
     *
     * <p>{@code Table<?>} and {@code DSLContext} parameters are skipped because they're
     * resolved by type elsewhere; parameters whose name matches a declared context key are
     * skipped because the existing name-based binding wins. Parameters with no compiler-set
     * name are skipped (the {@code -parameters} diagnostic still fires from the per-parameter
     * loop). Parameters whose Java type matches a recognised SOURCES shape
     * ({@link #couldBeSourcesShape}: {@code List<RowN>}, {@code List<RecordN>},
     * {@code List<TableRecord>} and Set equivalents) are skipped so the SOURCES classifier
     * downstream retains precedence at child coordinates.
     */
    private Map<String, PathExpr> inferBindingsByType(
            java.lang.reflect.Method javaMethod,
            Map<String, PathExpr> existing,
            Set<String> ctxKeys,
            Map<String, GraphQLInputType> slotTypes) {
        if (slotTypes == null || slotTypes.isEmpty()) return existing;

        var paramNames = Arrays.stream(javaMethod.getParameters())
            .filter(java.lang.reflect.Parameter::isNamePresent)
            .map(java.lang.reflect.Parameter::getName)
            .collect(Collectors.toCollection(HashSet::new));

        // A slot only counts as claimed when some Java parameter actually targets it. An
        // identity binding for a slot whose name doesn't match any parameter is a no-op
        // (left over from {@link ArgBindingMap#of} populating identity entries for every
        // slot in scope), and leaving it as "claimed" would suppress legitimate inference
        // for that slot.
        var claimedSlots = new HashSet<String>();
        for (var entry : existing.entrySet()) {
            if (paramNames.contains(entry.getKey())) {
                claimedSlots.add(entry.getValue().headName());
            }
        }

        var unclaimedSlotNames = new ArrayList<String>();
        for (var slotName : slotTypes.keySet()) {
            if (!claimedSlots.contains(slotName)) {
                unclaimedSlotNames.add(slotName);
            }
        }
        if (unclaimedSlotNames.isEmpty()) return existing;

        var unboundParams = new ArrayList<java.lang.reflect.Parameter>();
        for (var p : javaMethod.getParameters()) {
            if (org.jooq.Table.class.isAssignableFrom(p.getType())) continue;
            if (org.jooq.DSLContext.class.isAssignableFrom(p.getType())) continue;
            if (!p.isNamePresent()) continue;
            String pName = p.getName();
            if (existing.containsKey(pName)) continue;
            if (ctxKeys.contains(pName)) continue;
            if (couldBeSourcesShape(p.getParameterizedType())) continue;
            unboundParams.add(p);
        }
        if (unboundParams.isEmpty()) return existing;

        var augmented = new LinkedHashMap<>(existing);

        // Arity-unique branch: one unbound parameter, one unclaimed slot. Bind positionally
        // when the slot's GraphQL type has no canonical Java scalar mapping (named input
        // object, enum) AND the parameter's Java type is not a canonical scalar AND no
        // reachable nested field of the parameter's Java type exists inside the slot — that
        // covers the input-bean case the unambiguousReachablePath hint can't disambiguate.
        // When the slot does have a canonical mapping, fall through to the type-unique
        // branch so a real type mismatch surfaces the existing diagnostic; when the
        // parameter is a canonical scalar against a non-scalar slot or there is a competing
        // nested-reachable field of the parameter's type, defer to the
        // unambiguousReachablePath dot-path suggestion which captures the developer's likely
        // intent (a nested field pull).
        if (unboundParams.size() == 1 && unclaimedSlotNames.size() == 1) {
            String slotName = unclaimedSlotNames.get(0);
            String slotJavaType = mapToJavaTypeName(slotTypes.get(slotName));
            String paramType = unboundParams.get(0).getParameterizedType().getTypeName();
            boolean slotIsNamedInputOrEnum = slotJavaType == null;
            boolean paramIsScalarJavaType = isClassifiedScalarJavaTypeName(paramType);
            if (slotIsNamedInputOrEnum
                    && !paramIsScalarJavaType
                    && !anyReachableNestedMatch(paramType, unclaimedSlotNames, slotTypes)) {
                augmented.put(unboundParams.get(0).getName(), PathExpr.head(slotName));
                return augmented;
            }
        }

        // Type-unique branch: for each Java type T appearing exactly once on both sides
        // (where the slot has a canonical Java mapping), bind the pair.
        var slotsByType = new LinkedHashMap<String, List<String>>();
        for (var slotName : unclaimedSlotNames) {
            String javaTypeName = mapToJavaTypeName(slotTypes.get(slotName));
            if (javaTypeName == null) continue;
            slotsByType.computeIfAbsent(javaTypeName, k -> new ArrayList<>()).add(slotName);
        }
        var paramsByType = new LinkedHashMap<String, List<String>>();
        for (var p : unboundParams) {
            String pType = p.getParameterizedType().getTypeName();
            paramsByType.computeIfAbsent(pType, k -> new ArrayList<>()).add(p.getName());
        }
        for (var paramEntry : paramsByType.entrySet()) {
            if (paramEntry.getValue().size() != 1) continue;
            var slots = slotsByType.get(paramEntry.getKey());
            if (slots == null || slots.size() != 1) continue;
            // The user's rule: "one and only one possible mapping". A top-level slot is one
            // possible mapping; any reachable nested field of the same Java type inside any
            // unclaimed slot is another. When both exist, the binding is ambiguous and the
            // inference yields so the existing unambiguousReachablePath suggestion can render
            // the dot-path alternative.
            if (anyReachableNestedMatch(paramEntry.getKey(), unclaimedSlotNames, slotTypes)) continue;
            augmented.put(paramEntry.getValue().get(0), PathExpr.head(slots.get(0)));
        }
        return augmented;
    }

    /**
     * True when any unclaimed slot's input-object type contains a reachable nested field whose
     * GraphQL type maps to {@code targetTypeName}. Mirrors the search in
     * {@link #unambiguousReachablePath} but stops at the first hit — for ambiguity detection in
     * {@link #inferBindingsByType} we only need existence, not uniqueness.
     */
    private boolean anyReachableNestedMatch(
            String targetTypeName, List<String> unclaimedSlotNames,
            Map<String, GraphQLInputType> slotTypes) {
        var matches = new ArrayList<String>(1);
        for (var slotName : unclaimedSlotNames) {
            searchSlotForMatchingPath(slotName, slotTypes.get(slotName), targetTypeName,
                new ArrayList<>(), new HashSet<>(), matches);
            if (!matches.isEmpty()) return true;
        }
        return false;
    }

    /**
     * Adapter onto {@link ScalarTypeResolver#isClassifiedScalarJavaType}. The resolver owns the
     * predicate; this method threads {@code ctx.types.values()} in so the inference's
     * arity-unique gate consults the same source of truth that {@link #mapToJavaTypeName}
     * routes through for the forward direction.
     */
    private boolean isClassifiedScalarJavaTypeName(String javaTypeName) {
        return ScalarTypeResolver.isClassifiedScalarJavaType(
            javaTypeName, ctx.types == null ? null : ctx.types.values());
    }

    /**
     * True when the parameter's Java type matches a recognised SOURCES shape — {@code List<RowN>},
     * {@code List<RecordN>}, {@code List<TableRecord>}, or the {@code Set<>} equivalents.
     * {@link #inferBindingsByType} consults this to keep SOURCES-shape parameters out of the
     * inferred-binding candidate set, so the per-parameter loop's SOURCES classifier still wins
     * at child coordinates. The narrower {@link #looksLikeSourcesShape} only covers
     * {@code RowN} / {@code RecordN}; the TableRecord arm here matches the third element-type
     * arm of {@link #classifySourcesType}.
     */
    private static boolean couldBeSourcesShape(java.lang.reflect.Type paramType) {
        var split = peelContainer(paramType, java.util.EnumSet.of(ContainerKind.LIST, ContainerKind.SET));
        if (split.isEmpty()) return false;
        java.lang.reflect.Type elementType = split.get().elementType();
        if (elementType instanceof java.lang.reflect.ParameterizedType ept
                && ept.getRawType() instanceof Class<?> rawClass) {
            String rawName = rawClass.getName();
            if (rawName.startsWith("org.jooq.Row")
                    && rawName.substring("org.jooq.Row".length()).matches("\\d+")) return true;
            if (rawName.startsWith("org.jooq.Record")
                    && rawName.substring("org.jooq.Record".length()).matches("\\d+")) return true;
        }
        if (elementType instanceof Class<?> ec
                && org.jooq.TableRecord.class.isAssignableFrom(ec)) return true;
        return false;
    }

    /**
     * Maps a {@link GraphQLInputType} to the canonical Java type name a graphql-java argument
     * extraction would produce for it, suitable for literal comparison against
     * {@link java.lang.reflect.Parameter#getParameterizedType()}'s name. Returns {@code null}
     * for types the search can't translate confidently (unclassified scalars, enums, named
     * input objects), so the caller skips that candidate rather than guessing.
     *
     * <p>Phase 3: routes scalars through {@code ctx.types} so the classifier's
     * {@link no.sikt.graphitron.rewrite.model.GraphitronType.ScalarType} is the single source of
     * truth for the Java type binding. Consumer scalars resolved via {@code @scalarType} or the
     * extended-scalars convention layer produce their resolved Java type FQN instead of the
     * previous {@code null}-fallback.
     */
    private String mapToJavaTypeName(GraphQLInputType t) {
        GraphQLType current = t;
        int listDepth = 0;
        while (true) {
            if (current instanceof GraphQLNonNull nn) {
                current = nn.getWrappedType();
                continue;
            }
            if (current instanceof GraphQLList l) {
                current = l.getWrappedType();
                listDepth++;
                continue;
            }
            break;
        }
        String inner;
        if (current instanceof GraphQLScalarType s) {
            // Prefer the classified ScalarType from the model when available; fall back to the
            // resolver's closed spec-built-in table for unit-tier callers that exercise
            // mapToJavaTypeName without a full classified type registry (ctx.types null or empty).
            TypeName javaType = null;
            if (ctx.types != null
                    && ctx.types.get(s.getName()) instanceof no.sikt.graphitron.rewrite.model.GraphitronType.ScalarType st) {
                javaType = st.resolution().javaType();
            }
            if (javaType == null) {
                javaType = ScalarTypeResolver.builtInJavaType(s.getName());
            }
            if (javaType == null) return null;
            inner = javaType.toString();
        } else {
            return null;
        }
        String result = inner;
        for (int i = 0; i < listDepth; i++) {
            result = "java.util.List<" + result + ">";
        }
        return result;
    }

    // ===== Result container =====

    /**
     * Carries the result of {@link #reflectServiceMethod}: either a successfully resolved
     * {@link MethodRef} or a typed {@link Rejection} carrying the failure shape (so consumers
     * that wrap with caller-specific prose can preserve {@link Rejection.AuthorError.UnknownName}
     * fields rather than collapsing back to {@link Rejection.AuthorError.Structural}).
     */
    record ServiceReflectionResult(MethodRef ref, Rejection rejection) {
        boolean failed() { return rejection != null; }
    }
}
