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
import no.sikt.graphitron.rewrite.model.On;
import no.sikt.graphitron.rewrite.model.LoaderRegistration;
import no.sikt.graphitron.rewrite.model.MethodRef;
import no.sikt.graphitron.rewrite.model.ParamSource;
import no.sikt.graphitron.rewrite.model.ReflectionError;
import no.sikt.graphitron.rewrite.model.Rejection;
import no.sikt.graphitron.rewrite.model.ServiceMethodCallError;
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
            .map(e -> new ColumnRef(e.sqlName(), e.javaName(), e.columnClass(), e.columnType()));
    }

    Optional<ColumnRef> resolveColumn(String columnName, TableBackedType tableType) {
        return resolveColumnInTable(columnName, tableType.table().tableName());
    }

    Optional<ColumnRef> resolveColumnForReference(String columnName, List<JoinStep> path, TableBackedType sourceType) {
        return resolveColumnForReference(columnName, path, sourceType.table());
    }

    /**
     * Resolves a column at the terminal of an FK {@code @reference} path. The terminal is the
     * identity-resolved {@link TableRef} the path's hops carry, never a bare SQL name re-resolved
     * through the catalog: a bare-name lookup is ambiguous when the terminal table name collides
     * across generated schemas.
     */
    Optional<ColumnRef> resolveColumnForReference(String columnName, List<JoinStep> path, TableRef start) {
        return terminalTableForReference(path, start).flatMap(t -> t.column(columnName));
    }

    /**
     * Walks the FK join path from {@code start} and returns the terminal table's
     * identity-resolved {@link TableRef}; an empty path yields {@code start}. Empty when any
     * step is not FK-derived (a condition-only step's target table is unknown at build time).
     */
    Optional<TableRef> terminalTableForReference(List<JoinStep> path, TableRef start) {
        TableRef current = start;
        for (var step : path) {
            if (!(step instanceof JoinStep.Hop hop
                    && hop.on() instanceof On.ColumnPairs)) return Optional.empty();
            current = hop.targetTable();
        }
        return Optional.of(current);
    }

    Optional<ColumnRef> resolveColumnInTable(String columnName, String tableSqlName) {
        return ctx.catalog.findColumn(tableSqlName, columnName)
            .map(e -> new ColumnRef(e.sqlName(), e.javaName(), e.columnClass(), e.columnType()));
    }

    /**
     * Returns the SQL table name for a GraphQL type name when the type is table-backed, or
     * {@code null} when the type has no associated table.
     */
    String getTableSqlNameForType(String typeName) {
        // Resolve table-backedness through the TableIndex (a fixed point built before the walk),
        // not ctx.types: under the single classify-and-emit walk a field's target composite may
        // not be registered yet when the field classifies, so a registry read would miss it. The
        // index agrees with the registry for table-backed types by construction.
        var type = ctx.tables.forName(typeName).orElse(null);
        if (type instanceof TableBackedType tbt) return tbt.table().tableName();
        return null;
    }

    // ===== Service method reflection =====

    /**
     * Loads the service class and method via reflection and classifies each parameter: binding-map
     * keys become {@link ParamSource.Arg}, context keys become {@link ParamSource.Context}, the
     * rest classify via {@link #classifySourcesType}.
     *
     * <p>{@code argBindings} is built by the caller via {@link ArgBindingMap#of}. An explicit
     * override entry ({@code key != value}) whose target is not among the resolved method's
     * parameter names fails with a typo-guard message naming the directive site, the override
     * target, and the available parameter names.
     *
     * <p>{@code parentPkColumns} is the primary-key column list of the parent type's table.
     * Pass {@link List#of()} when the parent is a root operation type or has no backing table.
     *
     * <p>If the compiler was not invoked with {@code -parameters}, any parameter may lack a name.
     * A warning is logged proactively as soon as any nameless parameter is detected.
     *
     * <p>{@code expectedReturnType} (when non-null) is the structured javapoet {@link TypeName}
     * the method's generic return type must equal exactly; a mismatch fails classification with
     * a message naming expected vs actual. Pass {@code null} where strict validation isn't
     * applicable. Comparison is {@link TypeName#equals(Object)}: whitespace-tolerant and
     * structurally exact (a wildcard {@code ? extends Foo} is not equal to {@code Foo}). The
     * captured return type on the resulting {@link MethodRef.Service} is always the
     * parameterised form so emitters can declare matching fetcher return types directly without
     * parsing a string.
     */
    ServiceReflectionResult reflectServiceMethod(String className, String methodName,
            ArgBindingMap argBindings, Set<String> ctxKeys, List<ColumnRef> parentPkColumns,
            TypeName expectedReturnType) {
        return reflectServiceMethod(className, methodName, argBindings, ctxKeys,
            parentPkColumns, expectedReturnType, Map.of(), null);
    }

    /** @see #reflectServiceMethod(String, String, ArgBindingMap, Set, List, TypeName, Map, PkLessParent) */
    ServiceReflectionResult reflectServiceMethod(String className, String methodName,
            ArgBindingMap argBindings, Set<String> ctxKeys, List<ColumnRef> parentPkColumns,
            TypeName expectedReturnType, Map<String, GraphQLInputType> slotTypes) {
        return reflectServiceMethod(className, methodName, argBindings, ctxKeys,
            parentPkColumns, expectedReturnType, slotTypes, null);
    }

    /**
     * The one coordinate shape an empty {@code parentPkColumns} does <em>not</em> describe: a child
     * whose parent type maps a real table that happens to declare no primary key. Root coordinates
     * pass {@code null} here, which is what keeps the root diagnostics reachable.
     *
     * @param typeName  the parent GraphQL type
     * @param tableName the table it maps, named in the rejection so the author can find it
     */
    record PkLessParent(String typeName, String tableName) {}

    /**
     * Suggestion-aware overload: {@code slotTypes} lets a parameter-mismatch rejection pre-fill
     * an unambiguous reachable path in its argMapping suggestion. The production caller
     * ({@link ServiceDirectiveResolver}) threads the real slot types from
     * {@link FieldBuilder#argSlotTypes(graphql.schema.GraphQLFieldDefinition)}.
     *
     * <p>{@code pkLessParent} disambiguates the two coordinates that both arrive with an empty
     * {@code parentPkColumns}: pass the parent's type and table when it maps a table with no
     * primary key, {@code null} at a root operation type. Only the former can raise
     * {@link ServiceMethodCallError.SourcesOnPkLessParent}.
     */
    ServiceReflectionResult reflectServiceMethod(String className, String methodName,
            ArgBindingMap argBindings, Set<String> ctxKeys, List<ColumnRef> parentPkColumns,
            TypeName expectedReturnType, Map<String, GraphQLInputType> slotTypes,
            PkLessParent pkLessParent) {
        var argByJavaName = argBindings.byJavaName();
        if (className == null || methodName == null) {
            return new ServiceReflectionResult(null, Rejection.structural("service reference is incomplete"));
        }
        try {
            Class<?> cls = Class.forName(className, false, ctx.codegenLoader());
            MethodPick pick = pickMethod(cls, className, methodName);
            if (pick.rejection() != null) {
                return new ServiceReflectionResult(null, pick.rejection());
            }
            var javaMethod = pick.method();
            TypeName actualReturnType = TypeName.get(javaMethod.getGenericReturnType());
            if (expectedReturnType != null
                    && !actualReturnType.equals(expectedReturnType)) {
                return new ServiceReflectionResult(null,
                    new ReflectionError.ReturnTypeMismatch(className, methodName,
                        TypeNames.simple(expectedReturnType), TypeNames.simple(actualReturnType)));
            }
            boolean isStatic = java.lang.reflect.Modifier.isStatic(javaMethod.getModifiers());
            List<MethodRef.Param> ctorParams = List.of();
            if (!isStatic) {
                InstanceHolderResolution holder = resolveInstanceHolder(cls, methodName, className, ctxKeys);
                if (holder.rejection() != null) {
                    return new ServiceReflectionResult(null, holder.rejection());
                }
                ctorParams = holder.ctorParams();
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
                    ArgExtraction ext = argExtraction(typeName, resolvePathLeafType(resolvedPath, slotTypes),
                        "parameter '" + displayName + "' of method '" + methodName + "' in class '" + className + "'");
                    if (ext instanceof ArgExtraction.Rejected rej) {
                        return new ServiceReflectionResult(null, rej.rejection());
                    }
                    params.add(new MethodRef.Param.Typed(displayName, typeName, javaType,
                        new ParamSource.Arg(((ArgExtraction.Resolved) ext).extraction(), resolvedPath)));
                } else if (pName != null && ctxKeys.contains(pName)) {
                    params.add(new MethodRef.Param.Typed(displayName, typeName, javaType, new ParamSource.Context()));
                } else {
                    // One recognition, two readings. The shape says "this parameter is a SOURCES
                    // batch"; whether it can be honoured is the coordinate's question, and the two
                    // empty-PK coordinates answer it differently. A PK-less parent table is
                    // rejected by name here rather than at the arg-mismatch arm below, which would
                    // describe a problem the author does not have.
                    Optional<SourcesShape> recognisedShape = classifySourcesType(p.getParameterizedType());
                    if (recognisedShape.isPresent() && parentPkColumns.isEmpty() && pkLessParent != null) {
                        return new ServiceReflectionResult(null,
                            new ServiceMethodCallError.SourcesOnPkLessParent(
                                displayName, methodName, pkLessParent.typeName(), pkLessParent.tableName()));
                    }
                    Optional<SourcesShape> sourcesShape =
                        parentPkColumns.isEmpty() ? Optional.empty() : recognisedShape;
                    if (sourcesShape.isEmpty()) {
                        if (pName == null) {
                            return new ServiceReflectionResult(null,
                                new ReflectionError.ParameterNamesMissing(className, methodName));
                        }
                        // The discriminator is the parameter type axis, not the coordinate:
                        // parentPkColumns only gates which SOURCES outcomes are reachable, not
                        // the name-mismatch diagnostic. Anonymous-key SOURCES shapes (List<RowN>
                        // / List<RecordN>) at root get the dedicated batch-at-root diagnostic;
                        // List<TableRecord> at root is the canonical InputBeanResolver shape and
                        // falls through to the arg-mismatch arm when the name doesn't bind
                        // (looksLikeSourcesShape excludes TableRecord). The recognised shape is
                        // discarded for empty parentPkColumns above, so detection happens here on
                        // the parameter type directly.
                        if (parentPkColumns.isEmpty() && looksLikeSourcesShape(p.getParameterizedType())) {
                            return new ServiceReflectionResult(null,
                                Rejection.structural("@service at the root does not support "
                                + "List<Row>/List<Record> batch parameters — the root "
                                + "has no parent context to batch against"));
                        }
                        // DTO-shape parameters (List<DTO> / Set<DTO>) at child coordinates keep
                        // precedence over the name-mismatch arm: the @sourceRow hint is
                        // actionable there (DataLoader batching applies; the missing piece is a
                        // DTO-to-key conversion). At root, List<DTO> has no batching context, so
                        // the arg-mismatch arm wins (pinned by
                        // dtoSources_onRootField_pointsAtArgCtxMismatch).
                        if (!parentPkColumns.isEmpty()) {
                            String dtoReason = dtoSourcesRejectionReason(p.getParameterizedType());
                            if (dtoReason != null) {
                                return new ServiceReflectionResult(null,
                                    new ServiceMethodCallError.DtoSourcesUnsupported(displayName, methodName, dtoReason));
                            }
                        }
                        // Non-SOURCES-adjacent parameter that matched no argument or context key:
                        // the only plausible diagnosis is a name mismatch or missing context key.
                        if (!looksLikeSourcesShape(p.getParameterizedType())) {
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
                                new ServiceMethodCallError.ArgumentParameterMismatch(
                                    displayName, methodName,
                                    List.copyOf(argByJavaName.keySet()),
                                    List.copyOf(ctxKeys),
                                    suggestion));
                        }
                        return new ServiceReflectionResult(null,
                            new ServiceMethodCallError.UnrecognizedSourcesType(displayName, methodName, typeName));
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
                callShape = new MethodRef.CallShape.InstanceWithDslHolder(ctorParams);
            }
            return new ServiceReflectionResult(
                new MethodRef.Service(className, methodName, actualReturnType, List.copyOf(params),
                    declaredExceptionFqns(javaMethod), callShape),
                null);
        } catch (ClassNotFoundException e) {
            return new ServiceReflectionResult(null, new ReflectionError.ClassNotLoaded(className));
        }
    }

    /**
     * Outcome of {@link #pickMethod}: either the single resolved method or a typed rejection
     * (method-not-found {@link Rejection.AuthorError.UnknownName}, or
     * {@link ReflectionError.AmbiguousMethod} when more than one declaration shares the name).
     */
    private record MethodPick(java.lang.reflect.Method method, Rejection rejection) {}

    /**
     * Resolves the single declared method named {@code methodName} on {@code cls}. Shared by all
     * three reflect helpers: zero matches produce the typed {@code unknownServiceMethod}
     * {@link Rejection.AuthorError.UnknownName}; more than one produce
     * {@link ReflectionError.AmbiguousMethod} carrying every candidate's parameter arity.
     */
    private static MethodPick pickMethod(Class<?> cls, String className, String methodName) {
        var methods = Arrays.stream(cls.getDeclaredMethods())
            .filter(m -> m.getName().equals(methodName))
            .toList();
        if (methods.isEmpty()) {
            var declaredMethodNames = Arrays.stream(cls.getDeclaredMethods())
                .map(java.lang.reflect.Method::getName)
                .distinct()
                .toList();
            return new MethodPick(null,
                Rejection.unknownServiceMethod(
                    "method '" + methodName + "' not found in class '" + className + "'",
                    methodName, declaredMethodNames));
        }
        if (methods.size() > 1) {
            var arities = methods.stream()
                .map(java.lang.reflect.Method::getParameterCount)
                .toList();
            return new MethodPick(null, new ReflectionError.AmbiguousMethod(className, methodName, arities));
        }
        return new MethodPick(methods.get(0), null);
    }

    /**
     * Outcome of {@link #resolveInstanceHolder}: the resolved constructor's parameter sources (in
     * declaration order) on success, or a typed {@link ServiceMethodCallError.InstanceHolderUnconstructible}
     * rejection.
     */
    private record InstanceHolderResolution(List<MethodRef.Param> ctorParams, Rejection rejection) {}

    /**
     * Resolves the holder constructor for an instance {@code @service} method. The class must be
     * concrete and expose a public constructor whose parameters are each bindable from a
     * {@code DSLContext} slot or a declared context key. Among qualifying constructors the one
     * with the most parameters wins; ties break on declaration order.
     *
     * <p>Returns the chosen constructor's parameters projected onto {@link MethodRef.Param} with
     * {@link ParamSource.DslContext} / {@link ParamSource.Context} sources, which the walker
     * translates into {@code ServiceMethodCall.Instance.ctorArgs}. Context-key binding reuses the
     * same {@code ctxKeys} membership the method-parameter loop uses, so a ctor context arg
     * participates in the cross-site {@code contextArgument} type-agreement check unchanged. A
     * multi-{@code DSLContext} constructor is not rejected here; the walker raises
     * {@link ServiceMethodCallError.MultipleDslContextSlots} with the {@code CTOR} round.
     */
    private static InstanceHolderResolution resolveInstanceHolder(
            Class<?> cls, String methodName, String className, Set<String> ctxKeys) {
        int classMods = cls.getModifiers();
        if (java.lang.reflect.Modifier.isAbstract(classMods) || cls.isInterface()) {
            return new InstanceHolderResolution(null,
                new ServiceMethodCallError.InstanceHolderUnconstructible(className, methodName,
                    cls.getSimpleName(), ServiceMethodCallError.HolderProblem.ABSTRACT_OR_INTERFACE));
        }
        java.lang.reflect.Constructor<?> chosen = null;
        for (var ctor : cls.getDeclaredConstructors()) {
            if (!java.lang.reflect.Modifier.isPublic(ctor.getModifiers())) continue;
            if (!ctorParamsAllBindable(ctor, ctxKeys)) continue;
            if (chosen == null || ctor.getParameterCount() > chosen.getParameterCount()) {
                chosen = ctor;
            }
        }
        if (chosen == null) {
            return new InstanceHolderResolution(null,
                new ServiceMethodCallError.InstanceHolderUnconstructible(className, methodName,
                    cls.getSimpleName(), ServiceMethodCallError.HolderProblem.NO_BINDABLE_CTOR));
        }
        var ctorParams = new ArrayList<MethodRef.Param>();
        for (var p : chosen.getParameters()) {
            if (org.jooq.DSLContext.class.isAssignableFrom(p.getType())) {
                String paramName = p.isNamePresent() ? p.getName() : "dsl";
                ctorParams.add(new MethodRef.Param.Typed(paramName,
                    p.getParameterizedType().getTypeName(),
                    TypeName.get(p.getParameterizedType()),
                    new ParamSource.DslContext()));
            } else {
                // Bindable by the ctorParamsAllBindable guard: name matches a context key.
                ctorParams.add(new MethodRef.Param.Typed(p.getName(),
                    p.getParameterizedType().getTypeName(),
                    TypeName.get(p.getParameterizedType()),
                    new ParamSource.Context()));
            }
        }
        return new InstanceHolderResolution(List.copyOf(ctorParams), null);
    }

    /**
     * True when every parameter of {@code ctor} is bindable for an instance-{@code @service}
     * holder: a {@code DSLContext}, or a named parameter whose name is a declared context key.
     * A nameless parameter (compiled without {@code -parameters}) that isn't a {@code DSLContext}
     * is not bindable.
     */
    private static boolean ctorParamsAllBindable(java.lang.reflect.Constructor<?> ctor, Set<String> ctxKeys) {
        for (var p : ctor.getParameters()) {
            if (org.jooq.DSLContext.class.isAssignableFrom(p.getType())) continue;
            if (p.isNamePresent() && ctxKeys.contains(p.getName())) continue;
            return false;
        }
        return true;
    }

    /**
     * Captures the developer method's declared exception classes as FQNs, in source order,
     * feeding {@link MethodRef#declaredExceptions()} so the classifier can verify each declared
     * exception is covered by an {@code @error} handler on the field's channel.
     */
    private static List<String> declaredExceptionFqns(java.lang.reflect.Method m) {
        return Arrays.stream(m.getExceptionTypes())
            .map(Class::getName)
            .toList();
    }

    /**
     * Post-reflection typo guard for {@code argMapping} overrides: verifies each explicit
     * override target ({@code javaTarget != graphqlArgName}) is among the resolved method's
     * parameter names. Returns a failure message naming the directive site, the target, and the
     * actual parameter list, or {@code null} when every target resolves.
     *
     * <p>Identity entries skip the guard: an unresolved identity entry produces the per-parameter
     * "does not match any GraphQL argument" error in the main loop, which is already actionable.
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
     * Reflects a static, table-parameterised developer method: the {@code @condition} call
     * surface. Loads the class and method through the codegen classloader and classifies each
     * parameter — binding-map keys become {@link ParamSource.Arg}, context keys
     * {@link ParamSource.Context}, and the single required {@code Table<?>} parameter becomes
     * {@link ParamSource.Table}. Any other parameter shape is an error, as is a method with no
     * {@code Table<?>} parameter at all.
     *
     * <p>{@code argBindings} carries the Java-target to GraphQL-arg-name mapping per
     * {@link #reflectServiceMethod}, with the same override typo guard; an override entry
     * targeting the reserved Table slot is additionally rejected by
     * {@link #checkConditionOverrideTargets}.
     *
     * <p>If the compiler was not invoked with {@code -parameters}, a warning is logged as soon
     * as any nameless parameter is detected, even if type-based classification would otherwise
     * succeed.
     */
    ServiceReflectionResult reflectTableMethod(String className, String methodName,
            ArgBindingMap argBindings, Set<String> ctxKeys) {
        return reflectTableMethod(className, methodName, argBindings, ctxKeys, Map.of());
    }

    /**
     * Slot-types-aware overload of {@link #reflectTableMethod}. {@code slotTypes} carries the
     * GraphQL slots in scope at the directive site (single argument for argument-level
     * {@code @condition}, every field argument for field-level {@code @condition}), and feeds
     * {@link #inferBindingsByType} so an unbound Java parameter whose type uniquely matches a
     * single unclaimed slot binds positionally without requiring an {@code argMapping} entry.
     */
    ServiceReflectionResult reflectTableMethod(String className, String methodName,
            ArgBindingMap argBindings, Set<String> ctxKeys, Map<String, GraphQLInputType> slotTypes) {
        var argByJavaName = argBindings.byJavaName();
        if (className == null || methodName == null) {
            return new ServiceReflectionResult(null, Rejection.structural("table method reference is incomplete"));
        }
        try {
            Class<?> cls = Class.forName(className, false, ctx.codegenLoader());
            MethodPick pick = pickMethod(cls, className, methodName);
            if (pick.rejection() != null) {
                return new ServiceReflectionResult(null, pick.rejection());
            }
            var javaMethod = pick.method();
            if (!java.lang.reflect.Modifier.isStatic(javaMethod.getModifiers())) {
                return new ServiceReflectionResult(null,
                    Rejection.structural("method '" + methodName + "' in class '" + className
                    + "' must be declared 'static' — instance condition methods are not supported;"
                    + " the call site emits 'ClassName.method(...)' which requires a static method"));
            }
            if (Arrays.stream(javaMethod.getParameters()).anyMatch(p -> !p.isNamePresent())) {
                emitParametersWarning();
            }
            String tableTypoGuard = checkConditionOverrideTargets(argByJavaName, javaMethod, methodName, className);
            if (tableTypoGuard != null) {
                return new ServiceReflectionResult(null, Rejection.structural(tableTypoGuard));
            }
            argByJavaName = inferBindingsByType(javaMethod, argByJavaName, ctxKeys, slotTypes);
            var params = new ArrayList<MethodRef.Param>();
            boolean foundTable = false;
            for (var p : javaMethod.getParameters()) {
                if (org.jooq.Table.class.isAssignableFrom(p.getType())) {
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
                        new ReflectionError.ParameterNamesMissing(className, methodName));
                }
                String typeName = p.getParameterizedType().getTypeName();
                TypeName javaType = TypeName.get(p.getParameterizedType());
                PathExpr resolvedPath = argByJavaName.get(pName);
                if (resolvedPath != null) {
                    // No wire-coercion check on this path: @condition arguments
                    // use legacyArgExtraction; only the @service caller rejects, via
                    // argExtraction.
                    params.add(new MethodRef.Param.Typed(pName, typeName, javaType,
                        new ParamSource.Arg(legacyArgExtraction(typeName, ctx.codegenLoader()), resolvedPath)));
                } else if (ctxKeys.contains(pName)) {
                    params.add(new MethodRef.Param.Typed(pName, typeName, javaType, new ParamSource.Context()));
                } else {
                    return new ServiceReflectionResult(null,
                        Rejection.structural("parameter '" + pName + "' in method '" + methodName
                        + "' is not a GraphQL argument and not a context key"));
                }
            }
            if (!foundTable) {
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
            return new ServiceReflectionResult(null, new ReflectionError.ClassNotLoaded(className));
        }
    }

    /**
     * Reflects on a developer-supplied {@code @externalField} method.
     *
     * <p>Contract: the method must be {@code public static}, take exactly one parameter
     * assignable from the parent's jOOQ {@code Table<?>} class, and return parameterised
     * {@code org.jooq.Field<X>}. The captured return TypeName preserves the parameterised
     * shape so the generated {@code $project()} body compiles cleanly when projecting against
     * a {@code List<Field<?>>}.
     *
     * <p>Both {@code className} and {@code methodName} are required: the {@code @externalField}
     * arm in {@link FieldBuilder} surfaces a targeted "missing className" error before this call
     * and defaults {@code methodName} to the GraphQL field name when the directive omits
     * {@code method:}.
     */
    ServiceReflectionResult reflectExternalField(String className, String methodName,
            ClassName parentTableClass) {
        try {
            Class<?> cls = Class.forName(className, false, ctx.codegenLoader());
            MethodPick pick = pickMethod(cls, className, methodName);
            if (pick.rejection() != null) {
                return new ServiceReflectionResult(null, pick.rejection());
            }
            var javaMethod = pick.method();
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
            return new ServiceReflectionResult(null, new ReflectionError.ClassNotLoaded(className));
        }
    }

    /**
     * Override-target check for {@link #reflectTableMethod}'s {@code @condition} callers:
     * rejects argMapping entries that target the reserved {@code Table<?>} parameter slot, then
     * defers to {@link #checkOverrideTargets} for missing-parameter detection.
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
     * Outcome of {@link #argExtraction}: either the resolved {@link CallSiteExtraction} or a
     * typed wire-coercion rejection. A wire-incompatible arg rejects instead of classifying to a
     * {@code Direct} raw cast that crashes at runtime, so every downstream
     * {@link ParamSource.Arg} consumer can assume the extraction is wire-sound.
     */
    sealed interface ArgExtraction {
        record Resolved(CallSiteExtraction extraction) implements ArgExtraction {}
        record Rejected(Rejection rejection) implements ArgExtraction {}
    }

    /**
     * Extraction without the wire-coercion check: a jOOQ enum gets
     * {@link CallSiteExtraction.EnumValueOf}, everything else {@link CallSiteExtraction.Direct}.
     * Used by the {@code @condition} argument path, which has no
     * dimensional wire-coercion channel to surface a rejection; only the {@code @service} path
     * rejects, via {@link #argExtraction}.
     */
    static CallSiteExtraction legacyArgExtraction(String typeName, ClassLoader codegenLoader) {
        try {
            if (Class.forName(typeName, false, codegenLoader).isEnum()) {
                return new CallSiteExtraction.EnumValueOf(typeName);
            }
        } catch (ClassNotFoundException ignored) {}
        return new CallSiteExtraction.Direct();
    }

    /**
     * Returns the {@link CallSiteExtraction} for a GraphQL {@code Arg} parameter given its
     * declared Java type and the resolved SDL leaf type at the bound argument position. A
     * jOOQ-generated enum gets {@link CallSiteExtraction.EnumValueOf} after an enum-constant
     * parity check against the SDL enum values (a divergent value name rejects rather than
     * emitting an {@code Enum.valueOf} that throws at runtime); a scalar gets
     * {@link CallSiteExtraction.Direct} only once the wire-coercion predicate confirms
     * graphql-java's coercion output for the SDL leaf is assignable to the declared Java type,
     * else an {@link no.sikt.graphitron.rewrite.model.WireCoercionError.Assignability} rejection.
     *
     * <p>Text-mapped enums (GraphQL enum bound to a varchar column via {@code @field(name:)})
     * route through {@code Direct}: graphql-java translates the wire form to the runtime form
     * at the boundary via {@code GraphQLEnumValueDefinition.value(...)}, so resolvers
     * receive the runtime string and no extra extraction step is needed.
     *
     * <p>{@code sdlLeafType} may be {@code null} when the bound path cannot be resolved against the
     * field's slot types (e.g. a dot-path through an input type not in {@code slotTypes}); the
     * wire-coercion check then passes through conservatively rather than over-rejecting.
     */
    ArgExtraction argExtraction(String typeName, GraphQLInputType sdlLeafType, String site) {
        // The scalar fixed point, not the live registry view: this runs during field
        // classification, when a reachable scalar may be a not-yet-visited child of the walk.
        var classifiedTypes = ctx.scalarVerdicts.values();
        Class<?> javaClass;
        try {
            javaClass = Class.forName(typeName, false, ctx.codegenLoader());
        } catch (ClassNotFoundException e) {
            // Unloadable declared type: fall through to Direct (the reflect path has already
            // surfaced any hard class-loading failure for the method itself).
            return new ArgExtraction.Resolved(new CallSiteExtraction.Direct());
        }
        if (javaClass.isEnum()) {
            var namedSdl = namedSdlType(sdlLeafType);
            if (namedSdl instanceof graphql.schema.GraphQLEnumType enumType) {
                var parity = new EnumMappingResolver(ctx).checkEnumConstants(enumType.getName(), javaClass);
                if (parity instanceof EnumMappingResolver.EnumConstantParity.Divergence d) {
                    return new ArgExtraction.Rejected(
                        new no.sikt.graphitron.rewrite.model.WireCoercionError.EnumConstantDivergence(
                            typeName,
                            d.mismatches().stream().map(EnumMappingResolver.EnumConstantParity.ValueMismatch::sdlValueName).toList(),
                            d.mismatches().isEmpty() ? List.of() : d.mismatches().get(0).candidates(),
                            site));
                }
            }
            return new ArgExtraction.Resolved(new CallSiteExtraction.EnumValueOf(typeName));
        }
        return switch (WireCoercionResolver.checkScalar(sdlLeafType, typeName, classifiedTypes, site)) {
            case WireCoercionResolver.Result.PassThrough ignored ->
                new ArgExtraction.Resolved(new CallSiteExtraction.Direct());
            case WireCoercionResolver.Result.Rejected r ->
                new ArgExtraction.Rejected(r.error());
        };
    }

    /** Unwraps one NonNull, one optional List, one inner NonNull to the named SDL leaf type, or null. */
    private static GraphQLType namedSdlType(GraphQLInputType type) {
        if (type == null) return null;
        GraphQLType t = type;
        if (t instanceof GraphQLNonNull nn) t = nn.getWrappedType();
        if (t instanceof GraphQLList lst) {
            t = lst.getWrappedType();
            if (t instanceof GraphQLNonNull nn2) t = nn2.getWrappedType();
        }
        return t;
    }

    /**
     * Resolves the SDL leaf type a {@link PathExpr} binds to, walking from the head slot in
     * {@code slotTypes} through each subsequent dot-path segment's input-object field. Returns
     * {@code null} when the head slot is absent or the path descends through a non-input-object
     * intermediate (the caller then passes through the wire-coercion check conservatively).
     *
     * <p>Package-visible because {@code RoutineDirectiveResolver} feeds the same leaf type into
     * the same {@link #argExtraction} gate: one type check for every {@code argMapping} binding,
     * whichever directive authored it.
     */
    static GraphQLInputType resolvePathLeafType(PathExpr path, Map<String, GraphQLInputType> slotTypes) {
        if (path == null || slotTypes == null) return null;
        GraphQLInputType current = slotTypes.get(path.headName());
        var segments = path.segments();
        for (int i = 1; i < segments.size() && current != null; i++) {
            GraphQLType t = current;
            while (t instanceof GraphQLNonNull nn) t = nn.getWrappedType();
            if (t instanceof GraphQLList lst) {
                t = lst.getWrappedType();
                while (t instanceof GraphQLNonNull nn2) t = nn2.getWrappedType();
            }
            if (!(t instanceof GraphQLInputObjectType iot)) return null;
            var field = iot.getField(segments.get(i).name());
            if (field == null) return null;
            current = field.getType();
        }
        return current;
    }

    /**
     * True when the parameter type is a {@code List} / {@code Set} of {@code RowN} or
     * {@code RecordN}. Used by the root-op diagnostic to detect anonymous-key SOURCES-shape
     * parameters that {@link #classifySourcesType} cannot classify because the parent has no PK
     * to populate the source key. Concrete {@code TableRecord} subclasses are intentionally
     * excluded: at root, {@code List<XRecord>} is the canonical {@code InputBeanResolver} shape,
     * so it must fall through to the arg-mismatch diagnostic when the parameter name doesn't
     * bind to a GraphQL argument.
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
     * Classification of a {@code @service} SOURCES parameter: the per-row shape
     * ({@link SourceKey.Wrap}) and the container axis ({@link LoaderRegistration.Container})
     * needed to construct {@link MethodRef.Param.Sourced}. The columns axis is the caller's
     * {@code parentPkColumns} input and is not repeated here.
     */
    record SourcesShape(SourceKey.Wrap wrap, LoaderRegistration.Container container) {}

    /**
     * Classifies the element type of a {@code List<?>} or {@code Set<?>} SOURCES parameter into
     * a {@link SourcesShape}, or returns {@link Optional#empty()} when the type is not recognised.
     *
     * <p>Purely a type question. Whether a recognised shape can actually be honoured depends on the
     * coordinate (a root type and a PK-less parent table both lack a batch key, for different
     * reasons), and the caller decides that; keeping it out of here is what lets one recognition
     * serve both the {@link MethodRef.Param.Sourced} construction and the
     * {@link ServiceMethodCallError.SourcesOnPkLessParent} rejection.
     */
    static Optional<SourcesShape> classifySourcesType(java.lang.reflect.Type paramType) {
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
     * Peels the container layer off a {@link java.lang.reflect.Type}: {@code List<X>} /
     * {@code Set<X>} yield {@code LIST} / {@code SET} with the inner type as
     * {@code elementType}, a bare {@link Class} yields {@code SINGLE}. Empty for anything else
     * (raw types, wildcards, type variables, unrecognised parameterised containers).
     *
     * <p>Shared between {@link #classifySourcesType} (SINGLE filtered out via {@code accept})
     * and {@code FieldBuilder.classifyAccessorReturn} (all three kinds accepted). Both call
     * sites stay inside parse-boundary classes per the {@code development-principles.adoc}
     * containment invariant on holding raw reflection types only inside {@code JooqCatalog} /
     * {@code TypeBuilder} / {@code FieldBuilder} / {@code ServiceCatalog}.
     *
     * @param type the type to peel
     * @param accept the container kinds the caller accepts; other container axes return
     *               {@link Optional#empty()}
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

    /** Formats a set of names as a sorted bracketed list, or {@code (none)} when empty. */
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
            + " The @sourceRow directive solves the analogous case for child fields on record-backed"
            + " parents (not @service SOURCES)";
    }

    // ===== Suggestion-side path search =====

    /**
     * Walks every slot in {@code slotTypes} looking for a single nested input-object field
     * whose GraphQL type maps to {@code targetTypeName} (the Java type of an unmatched method
     * parameter). Returns the dotted path (e.g. {@code "input.filmId"}) when exactly one such
     * field exists across all slots; returns {@code null} when there is no match or more than
     * one (the caller then falls back to the {@code <fieldName>} placeholder).
     *
     * <p>The search is conservative on purpose: it only descends through non-list
     * {@link GraphQLInputObjectType} intermediates and only matches leaves whose GraphQL kind
     * maps to a standard Java type via {@link #mapToJavaTypeName}, compared literally against
     * the parameter's parameterized-type name. Custom scalars, enums and named input objects
     * don't count as candidate leaves; the suggestion would mislead users by pointing at a path
     * whose runtime Java shape isn't guaranteed to match.
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
     * <p>{@code Table<?>} and {@code DSLContext} parameters are skipped (resolved by type
     * elsewhere); parameters whose name matches a declared context key are skipped (name-based
     * binding wins); nameless parameters are skipped (the {@code -parameters} diagnostic still
     * fires from the per-parameter loop); SOURCES-shape parameters
     * ({@link #couldBeSourcesShape}) are skipped so the SOURCES classifier downstream retains
     * precedence at child coordinates.
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

        // Arity-unique branch: one unbound parameter, one unclaimed slot. Bind positionally only
        // when the slot has no canonical Java scalar mapping (named input object, enum), the
        // parameter's Java type is not a canonical scalar, and no reachable nested field of the
        // parameter's type exists inside the slot: the input-bean case the
        // unambiguousReachablePath hint can't disambiguate. Otherwise fall through, so a real
        // type mismatch surfaces the existing diagnostic or the dot-path suggestion captures the
        // likely intent (a nested field pull).
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
            // Bind only when there is one and only one possible mapping. A top-level slot is one
            // possible mapping; any reachable nested field of the same Java type inside any
            // unclaimed slot is another. When both exist, the binding is ambiguous and inference
            // yields to the unambiguousReachablePath dot-path suggestion.
            if (anyReachableNestedMatch(paramEntry.getKey(), unclaimedSlotNames, slotTypes)) continue;
            augmented.put(paramEntry.getValue().get(0), PathExpr.head(slots.get(0)));
        }

        // Name-based depth-1 unpacking, on the residual parameters still unbound after the
        // arity-unique and type-unique branches: a parameter whose name matches exactly one
        // direct field (by name AND mapped Java type) of a single unclaimed input-object slot
        // binds one level in. The synthesised PathExpr is identical to the one a hand-written
        // `argMapping: "p: slot.field"` produces, so downstream emission is unchanged. Identity
        // binding is handled earlier in ArgBindingMap.of, so a parameter that matched a slot by
        // its own name is already bound and skipped here. Zero or >1 candidates leave the
        // parameter unbound, so the per-parameter rejection (with its unambiguousReachablePath
        // argMapping suggestion) still fires.
        for (var p : unboundParams) {
            if (augmented.containsKey(p.getName())) continue;
            PathExpr nested = inferNestedFieldByName(
                p.getName(), p.getParameterizedType().getTypeName(), unclaimedSlotNames, slotTypes);
            if (nested != null) {
                augmented.put(p.getName(), nested);
            }
        }
        return augmented;
    }

    /**
     * Depth-1 name-based descent: scans every unclaimed slot whose unwrapped (non-null) GraphQL
     * type is a {@link GraphQLInputObjectType} for the direct field named {@code paramName}
     * whose {@link #mapToJavaTypeName mapped Java type} equals {@code paramJavaTypeName}.
     * Returns the {@link PathExpr.Step} binding when exactly one such candidate exists across
     * all unclaimed slots; {@code null} for zero or more than one, so the caller leaves the
     * parameter unbound.
     *
     * <p>Strips only non-null wrappers off the slot, mirroring {@link #searchSlotForMatchingPath},
     * so a list-shaped intermediate is never descended through. Routing the leaf through
     * {@link #mapToJavaTypeName} means only canonical-scalar-typed leaves match (a scalar or a
     * list thereof), the same null-is-no-match discipline the arity-unique / type-unique
     * branches gate on.
     */
    PathExpr inferNestedFieldByName(String paramName, String paramJavaTypeName,
            List<String> unclaimedSlotNames, Map<String, GraphQLInputType> slotTypes) {
        PathExpr match = null;
        for (var slotName : unclaimedSlotNames) {
            GraphQLType walk = slotTypes.get(slotName);
            while (walk instanceof GraphQLNonNull nn) {
                walk = nn.getWrappedType();
            }
            if (!(walk instanceof GraphQLInputObjectType inputObj)) continue;
            var field = inputObj.getField(paramName);
            if (field == null) continue;
            String mapped = mapToJavaTypeName(field.getType());
            if (mapped == null || !mapped.equals(paramJavaTypeName)) continue;
            if (match != null) return null; // more than one candidate across slots → ambiguous
            match = PathExpr.step(PathExpr.head(slotName), field.getName(),
                ArgBindingMap.isListShaped(field.getType()));
        }
        return match;
    }

    /**
     * True when any unclaimed slot's input-object type contains a reachable nested field whose
     * GraphQL type maps to {@code targetTypeName}. Mirrors the search in
     * {@link #unambiguousReachablePath} but stops at the first hit: ambiguity detection in
     * {@link #inferBindingsByType} needs existence, not uniqueness.
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
     * predicate; this method threads the scalar fixed point
     * ({@link BuildContext#scalarVerdicts}, registry-free, safe mid-walk) in so the inference's
     * arity-unique gate consults the same source of truth that {@link #mapToJavaTypeName}
     * routes through for the forward direction.
     */
    private boolean isClassifiedScalarJavaTypeName(String javaTypeName) {
        return ScalarTypeResolver.isClassifiedScalarJavaType(javaTypeName, ctx.scalarVerdicts.values());
    }

    /**
     * True when the parameter's Java type matches a recognised SOURCES shape: {@code List} /
     * {@code Set} of {@code RowN}, {@code RecordN}, or {@code TableRecord}.
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
     * <p>Scalars route through the scalar fixed point ({@link BuildContext#scalarVerdicts}) so
     * the classifier's
     * {@link no.sikt.graphitron.rewrite.model.GraphitronType.ScalarType} is the single source of
     * truth for the Java type binding; consumer scalars resolved via {@code @scalarType} produce
     * their resolved Java type FQN.
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
            // Prefer the classifier's ScalarType verdict from the scalar fixed point
            // (BuildContext.scalarVerdicts, registry-free, safe to read while the walk is
            // mid-flight); fall back to the resolver's closed spec-built-in table for unit-tier
            // callers that exercise mapToJavaTypeName without the fixed point populated.
            TypeName javaType = null;
            if (ctx.scalarVerdicts.get(s.getName()) instanceof no.sikt.graphitron.rewrite.model.GraphitronType.ScalarType st) {
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
