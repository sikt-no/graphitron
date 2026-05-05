package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.TypeName;
import no.sikt.graphitron.rewrite.model.BatchKey;
import no.sikt.graphitron.rewrite.model.CallSiteExtraction;
import no.sikt.graphitron.rewrite.model.ColumnRef;
import no.sikt.graphitron.rewrite.model.GraphitronType.TableBackedType;
import no.sikt.graphitron.rewrite.model.JoinStep;
import no.sikt.graphitron.rewrite.model.JoinStep.FkJoin;
import no.sikt.graphitron.rewrite.model.MethodRef;
import no.sikt.graphitron.rewrite.model.ParamSource;
import no.sikt.graphitron.rewrite.model.Rejection;
import no.sikt.graphitron.rewrite.model.TableRef;
import no.sikt.graphitron.rewrite.model.TypeNames;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
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
     * The captured return type stored on the resulting {@link MethodRef.Basic} is always
     * the parameterised form so emitters can declare matching fetcher return types
     * directly without parsing a string.
     */
    @no.sikt.graphitron.rewrite.model.LoadBearingClassifierCheck(
        key = "service-catalog-strict-service-return",
        description = "The strict TypeName.equals arm rejects developer @service methods whose "
            + "parameterised return type doesn't match the expected record class for the field's "
            + "@table-bound return type. Lets the emitter declare a typed Result<XRecord> "
            + "(or XRecord) return rather than Object.")
    @no.sikt.graphitron.rewrite.model.LoadBearingClassifierCheck(
        key = "service-catalog-instance-service-holder-shape",
        description = "The checkServiceInstanceHolderShape arm rejects instance @service methods "
            + "whose enclosing class is abstract / an interface, or lacks a public single-arg "
            + "DSLContext constructor. Lets the emitter unconditionally emit "
            + "`new ServiceClass(dsl).method(...)` for non-static methods without checking the "
            + "holder shape at emit time.")
    ServiceReflectionResult reflectServiceMethod(String className, String methodName,
            ArgBindingMap argBindings, Set<String> ctxKeys, List<ColumnRef> parentPkColumns,
            TypeName expectedReturnType) {
        var argByJavaName = argBindings.byJavaName();
        if (className == null || methodName == null) {
            return new ServiceReflectionResult(null, Rejection.structural("service reference is incomplete"));
        }
        try {
            Class<?> cls = Class.forName(className);
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
            var params = new ArrayList<MethodRef.Param>();
            for (var p : javaMethod.getParameters()) {
                if (org.jooq.DSLContext.class.isAssignableFrom(p.getType())) {
                    String paramName = p.isNamePresent() ? p.getName() : "dsl";
                    params.add(new MethodRef.Param.Typed(paramName,
                        p.getParameterizedType().getTypeName(), new ParamSource.DslContext()));
                    continue;
                }
                String pName = p.isNamePresent() ? p.getName() : null;
                String displayName = pName != null ? pName : p.getType().getSimpleName();
                String typeName = p.getParameterizedType().getTypeName();
                String resolvedArgName = pName != null ? argByJavaName.get(pName) : null;
                if (resolvedArgName != null) {
                    params.add(new MethodRef.Param.Typed(displayName, typeName,
                        new ParamSource.Arg(argExtraction(typeName), resolvedArgName)));
                } else if (pName != null && ctxKeys.contains(pName)) {
                    params.add(new MethodRef.Param.Typed(displayName, typeName, new ParamSource.Context()));
                } else {
                    Optional<BatchKey.ParentKeyed> batchKey = classifySourcesType(p.getParameterizedType(), parentPkColumns);
                    if (batchKey.isEmpty()) {
                        if (pName == null) {
                            return new ServiceReflectionResult(null,
                                Rejection.structural("parameter names not available for method '" + methodName + "' in class '" + className
                                + "' — compile with -parameters flag (see warning above for instructions)"));
                        }
                        // SOURCES batching is only meaningful when there is a parent table to batch
                        // against. On root operation fields and DTO-parent children (parentPkColumns
                        // empty) the parameter must match a GraphQL argument or context key — point
                        // the user at the actual mismatch instead of an unrelated SOURCES-flavored
                        // hint (the lifter-directive roadmap doesn't help with arg-name typos).
                        if (parentPkColumns.isEmpty()) {
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
                                suggestion = " — either rename the Java parameter to match one of the available argument names, or bind explicitly via the @service directive's argMapping field"
                                    + " (e.g. argMapping: \"" + displayName + ": " + soleArg + "\""
                                    + ", which reads as \"the Java parameter named '" + displayName
                                    + "' binds to the GraphQL argument named '" + soleArg + "'\")";
                            }
                            return new ServiceReflectionResult(null,
                                Rejection.structural("parameter '" + displayName + "' in method '" + methodName
                                + "' does not match any GraphQL argument or context key on this field"
                                + " — available GraphQL arguments: " + available
                                + "; available context keys: " + formatNameSet(ctxKeys)
                                + suggestion));
                        }
                        String dtoReason = dtoSourcesRejectionReason(p.getParameterizedType());
                        if (dtoReason != null) {
                            return new ServiceReflectionResult(null,
                                Rejection.structural("parameter '" + displayName + "' in method '" + methodName + "': " + dtoReason));
                        }
                        return new ServiceReflectionResult(null,
                            Rejection.structural("parameter '" + displayName + "' in method '" + methodName
                            + "' has an unrecognized sources type: '" + typeName + "'"));
                    }
                    params.add(new MethodRef.Param.Sourced(displayName, batchKey.get()));
                }
            }
            return new ServiceReflectionResult(
                new MethodRef.Basic(className, methodName, actualReturnType, List.copyOf(params),
                    declaredExceptionFqns(javaMethod), isStatic),
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
     * Feeds {@link MethodRef.Basic#declaredExceptions()} so the classifier's match rule can
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
    private static String checkOverrideTargets(Map<String, String> argByJavaName,
                                               java.lang.reflect.Method javaMethod,
                                               String methodName, String className) {
        var paramNames = Arrays.stream(javaMethod.getParameters())
            .filter(java.lang.reflect.Parameter::isNamePresent)
            .map(java.lang.reflect.Parameter::getName)
            .collect(Collectors.toCollection(java.util.LinkedHashSet::new));
        for (var entry : argByJavaName.entrySet()) {
            String javaTarget = entry.getKey();
            String argName = entry.getValue();
            if (javaTarget.equals(argName)) continue;
            if (!paramNames.contains(javaTarget)) {
                return "argMapping entry '" + javaTarget + ": " + argName
                    + "' references Java parameter '" + javaTarget
                    + "', but method '" + methodName + "' in class '" + className
                    + "' has parameters " + formatNameSet(paramNames);
            }
        }
        return null;
    }

    /**
     * Loads the table-method class and method via reflection and classifies each parameter.
     *
     * <p>Parameters whose type is assignable to {@link org.jooq.Table} get {@link ParamSource.Table};
     * parameters whose name appears as a key in {@code argBindings} get {@link ParamSource.Arg}
     * with {@link ParamSource.Arg#graphqlArgName()} set to the corresponding value;
     * parameters whose name matches a context key get {@link ParamSource.Context}.
     * Any other parameter is an error.
     *
     * <p>{@code argBindings} carries the Java-target → GraphQL-arg-name mapping per
     * {@link #reflectServiceMethod}. Override entries that target the {@code Table<?>} parameter,
     * or that point at non-existent Java parameters, are rejected with a typo-guard message
     * naming the directive site and the available parameter names.
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
    @no.sikt.graphitron.rewrite.model.LoadBearingClassifierCheck(
        key = "service-catalog-strict-tablemethod-return",
        description = "The strict ClassName.equals arm rejects developer @tableMethod methods "
            + "whose return type is wider than the generated jOOQ table class for the field's "
            + "@table-bound return type. Lets the emitter declare <SpecificTable> table = "
            + "Method.x(...) without a downcast.")
    ServiceReflectionResult reflectTableMethod(String className, String methodName,
            ArgBindingMap argBindings, Set<String> ctxKeys, ClassName expectedReturnClass) {
        var argByJavaName = argBindings.byJavaName();
        if (className == null || methodName == null) {
            return new ServiceReflectionResult(null, Rejection.structural("table method reference is incomplete"));
        }
        try {
            Class<?> cls = Class.forName(className);
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
            String tableTypoGuard = checkTableMethodOverrideTargets(argByJavaName, javaMethod, methodName, className);
            if (tableTypoGuard != null) {
                return new ServiceReflectionResult(null, Rejection.structural(tableTypoGuard));
            }
            var params = new ArrayList<MethodRef.Param>();
            boolean foundTable = false;
            for (var p : javaMethod.getParameters()) {
                if (org.jooq.Table.class.isAssignableFrom(p.getType())) {
                    String paramName = p.isNamePresent() ? p.getName() : "table";
                    params.add(new MethodRef.Param.Typed(paramName,
                        p.getParameterizedType().getTypeName(), new ParamSource.Table()));
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
                String resolvedArgName = argByJavaName.get(pName);
                if (resolvedArgName != null) {
                    params.add(new MethodRef.Param.Typed(pName, typeName,
                        new ParamSource.Arg(argExtraction(typeName), resolvedArgName)));
                } else if (ctxKeys.contains(pName)) {
                    params.add(new MethodRef.Param.Typed(pName, typeName, new ParamSource.Context()));
                } else {
                    return new ServiceReflectionResult(null,
                        Rejection.structural("parameter '" + pName + "' in method '" + methodName
                        + "' is not a Table<?> parameter, not a GraphQL argument, and not a context key"));
                }
            }
            if (!foundTable) {
                return new ServiceReflectionResult(null,
                    Rejection.structural("method '" + methodName + "' in class '" + className
                    + "' has no Table<?> parameter — @tableMethod requires exactly one Table<?> parameter"));
            }
            return new ServiceReflectionResult(
                new MethodRef.Basic(className, methodName,
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
            Class<?> cls = Class.forName(className);
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
                paramName, p.getParameterizedType().getTypeName(), new ParamSource.Table()));
            TypeName returnTypeName = TypeName.get(genericReturn);
            return new ServiceReflectionResult(
                new MethodRef.Basic(className, methodName, returnTypeName, params),
                null);
        } catch (ClassNotFoundException e) {
            return new ServiceReflectionResult(null, Rejection.structural("class '" + className + "' could not be loaded"));
        }
    }

    /**
     * {@code @tableMethod}-specific override-target check: rejects override entries that point
     * at the {@code Table<?>} parameter slot, then defers to the same logic as
     * {@link #checkOverrideTargets} for missing-parameter detection.
     */
    private static String checkTableMethodOverrideTargets(Map<String, String> argByJavaName,
                                                          java.lang.reflect.Method javaMethod,
                                                          String methodName, String className) {
        var tableParamNames = Arrays.stream(javaMethod.getParameters())
            .filter(p -> org.jooq.Table.class.isAssignableFrom(p.getType()))
            .filter(java.lang.reflect.Parameter::isNamePresent)
            .map(java.lang.reflect.Parameter::getName)
            .collect(Collectors.toCollection(java.util.LinkedHashSet::new));
        for (var entry : argByJavaName.entrySet()) {
            String javaTarget = entry.getKey();
            String argName = entry.getValue();
            if (javaTarget.equals(argName)) continue;
            if (tableParamNames.contains(javaTarget)) {
                return "argMapping entry '" + javaTarget + ": " + argName
                    + "' targets the Table<?> parameter of @tableMethod '" + methodName
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
                + "  To fix: add <compilerArg>-parameters</compilerArg> to maven-compiler-plugin in your pom.xml:\n"
                + "    <plugin>\n"
                + "      <groupId>org.apache.maven.plugins</groupId>\n"
                + "      <artifactId>maven-compiler-plugin</artifactId>\n"
                + "      <configuration>\n"
                + "        <compilerArgs><arg>-parameters</arg></compilerArgs>\n"
                + "      </configuration>\n"
                + "    </plugin>");
        }
    }

    /**
     * Returns the {@link CallSiteExtraction} for a GraphQL {@code Arg} parameter based on its
     * Java type. jOOQ-generated enums get {@link CallSiteExtraction.EnumValueOf}; all other
     * types default to {@link CallSiteExtraction.Direct}.
     *
     * <p>Text-mapped enum detection (String Java type + GraphQL enum with value mappings) requires
     * GraphQL schema access and is handled as a post-processing step in
     * {@link FieldBuilder#enrichArgExtractions}.
     */
    static CallSiteExtraction argExtraction(String typeName) {
        try {
            if (Class.forName(typeName).isEnum()) {
                return new CallSiteExtraction.EnumValueOf(typeName);
            }
        } catch (ClassNotFoundException ignored) {}
        return new CallSiteExtraction.Direct();
    }

    /**
     * Classifies the element type of a {@code List<?>} or {@code Set<?>} SOURCES parameter into
     * a {@link BatchKey} variant, or returns {@link Optional#empty()} when the type is not
     * recognised.
     *
     * <p>The container axis ({@code List} vs {@code Set}) and key-shape axis (one of
     * {@code RowN}, {@code RecordN}, or {@code X extends TableRecord}) combine into six
     * {@link BatchKey} variants. {@code List<?>} maps to the positional variants
     * ({@link BatchKey.RowKeyed}/{@link BatchKey.RecordKeyed}/{@link BatchKey.TableRecordKeyed});
     * {@code Set<?>} maps to the mapped variants ({@link BatchKey.MappedRowKeyed}/
     * {@link BatchKey.MappedRecordKeyed}/{@link BatchKey.MappedTableRecordKeyed}).
     *
     * <p>For all variants, {@code parentPkColumns} is used as the authoritative key column
     * list. The column types from the parent PK are used in the generated method signature;
     * the user's declared type args are used only to determine the arity and variant. Pass
     * {@link List#of()} when no parent table context is available.
     */
    static Optional<BatchKey.ParentKeyed> classifySourcesType(java.lang.reflect.Type paramType,
            List<ColumnRef> parentPkColumns) {
        var split = peelContainer(paramType, java.util.EnumSet.of(ContainerKind.LIST, ContainerKind.SET));
        if (split.isEmpty()) {
            return Optional.empty();
        }
        boolean isSet = split.get().container() == ContainerKind.SET;
        java.lang.reflect.Type elementType = split.get().elementType();

        if (elementType instanceof java.lang.reflect.ParameterizedType ept
                && ept.getRawType() instanceof Class<?> rawClass) {
            String rawName = rawClass.getName();
            if (rawName.startsWith("org.jooq.Row")) {
                String suffix = rawName.substring("org.jooq.Row".length());
                if (suffix.matches("\\d+")) {
                    return Optional.of(isSet
                        ? new BatchKey.MappedRowKeyed(parentPkColumns)
                        : new BatchKey.RowKeyed(parentPkColumns));
                }
            }
            if (rawName.startsWith("org.jooq.Record")) {
                String suffix = rawName.substring("org.jooq.Record".length());
                if (suffix.matches("\\d+")) {
                    return Optional.of(isSet
                        ? new BatchKey.MappedRecordKeyed(parentPkColumns)
                        : new BatchKey.RecordKeyed(parentPkColumns));
                }
            }
        } else if (elementType instanceof Class<?> elementClass
                && org.jooq.TableRecord.class.isAssignableFrom(elementClass)) {
            @SuppressWarnings("unchecked")
            Class<? extends org.jooq.TableRecord<?>> tableRecordClass =
                (Class<? extends org.jooq.TableRecord<?>>) elementClass;
            return Optional.of(isSet
                ? new BatchKey.MappedTableRecordKeyed(parentPkColumns, tableRecordClass)
                : new BatchKey.TableRecordKeyed(parentPkColumns, tableRecordClass));
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
     * {@code @record} parents; all three kinds are accepted). Both call sites remain inside
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
            + " The @batchKeyLifter directive solves the analogous case for child fields on @record"
            + " parents (not @service SOURCES)";
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
