package no.sikt.graphitron.rewrite;

import graphql.schema.GraphQLFieldDefinition;
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
import no.sikt.graphitron.rewrite.session.SessionHooks;
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

    /**
     * The catalog table a jOOQ record class names, keyed on the javapoet {@link ClassName} a decoded
     * SOURCES shape carries rather than on a live {@link Class}. Empty when the class does not load
     * or is not a catalog record. The classify phase asks this to turn an author-declared batch-key
     * element type into a real table; the load stays inside this parse-boundary class so no raw
     * reflection type has to travel to the caller.
     */
    Optional<TableRef> resolveTableByRecordClassName(ClassName recordClass) {
        try {
            return resolveTableByRecordClass(
                Class.forName(recordClass.reflectionName(), false, ctx.codegenLoader()));
        } catch (ClassNotFoundException e) {
            return Optional.empty();
        }
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
     * A {@code @service} method reduced to the facts the classifier and the binder need, with no
     * {@link java.lang.reflect.Method} left in it: the decode phase's product. Everything a
     * downstream phase would otherwise re-derive from live reflection is precomputed here, which
     * is what keeps raw reflection types inside this parse-boundary class structurally rather
     * than by convention (see {@code development-principles.adoc}).
     *
     * @param returnType     the method's generic return type, parameterised so emitters can
     *                       declare matching fetcher return types without parsing a string
     * @param ctorParams     the instance-holder constructor's parameters, empty for a static
     *                       method and {@code null} when the holder is unconstructible
     * @param unconstructible the holder rejection decode captured, surfaced by
     *                       {@link #bindServiceMethod}; {@code null} when the holder resolved
     */
    record ServiceSignature(
        String className,
        String methodName,
        TypeName returnType,
        boolean isStatic,
        List<String> declaredExceptions,
        List<MethodRef.Param> ctorParams,
        Rejection unconstructible,
        List<DecodedParam> params
    ) {
        /** Declared parameter names, in declaration order, skipping nameless parameters. */
        java.util.LinkedHashSet<String> namedParameters() {
            return params.stream().map(DecodedParam::name).filter(java.util.Objects::nonNull)
                .collect(Collectors.toCollection(java.util.LinkedHashSet::new));
        }
    }

    /**
     * One decoded parameter. {@code name} is {@code null} when the class was compiled without
     * {@code -parameters}; {@code displayName} falls back to the declared type's simple name so
     * diagnostics still have something to print.
     *
     * @param sourcesShape the shape {@link #classifySourcesType} recognises, or {@code null}.
     *                     Recognition is a type question and is answered once here; whether it
     *                     can be honoured is the coordinate's question and belongs to classify.
     * @param dtoSourcesReason the {@code List<DTO>} / {@code Set<DTO>} rejection prose, or
     *                     {@code null} when the parameter is not DTO-shaped
     */
    record DecodedParam(
        String name,
        String displayName,
        String typeName,
        TypeName javaType,
        boolean isDslContext,
        SourcesShape sourcesShape,
        String dtoSourcesReason
    ) {}

    /** Outcome of {@link #decodeServiceMethod}: the signature fact, or a decode-level rejection. */
    record DecodeResult(ServiceSignature signature, Rejection rejection) {
        boolean failed() { return rejection != null; }
    }

    /**
     * The role a parameter plays once membership in the binding map, the context-key set and the
     * SOURCES shape recogniser have all been consulted. Deciding candidacy once, as a carried
     * value, is what lets classify and bind switch on the same answer instead of re-evaluating
     * three predicates they would have to agree to spell the same way.
     */
    sealed interface ParamRole {
        /** A {@code DSLContext} slot, resolved by type before any name is consulted. */
        record Dsl() implements ParamRole {}
        /** Name-claimed by the {@code $session} argMapping sigil: bound to the session handle. */
        record SessionBound() implements ParamRole {}
        /** Name-claimed by a GraphQL argument (possibly through an {@code argMapping} path). */
        record ArgBound(PathExpr path) implements ParamRole {}
        /** Name-claimed by a declared context key. */
        record ContextBound() implements ParamRole {}
        /** Unclaimed by name and SOURCES-shaped; the coordinate decides whether it can be honoured. */
        record SourcesCandidate(SourcesShape shape) implements ParamRole {}
        /** Unclaimed by name and not SOURCES-shaped. */
        record Unclaimed() implements ParamRole {}
    }

    /**
     * Product of the claim-reduction step: one {@link ParamRole} per decoded parameter, in
     * declaration order, plus the binding map the roles were reduced against (augmented by
     * {@link #inferBindingsByType}), which the binder's diagnostics name.
     */
    record ClaimedParams(List<ParamRole> roles, Map<String, PathExpr> argByJavaName) {
        ParamRole roleOf(int index) { return roles.get(index); }
    }

    /**
     * Decode: loads the service class, resolves the single method of that name, and reduces it to
     * a {@link ServiceSignature}. Pure over the class, the method name and the declared context
     * keys; no binding inputs, no coordinate inputs, so nothing decided here can depend on either.
     *
     * <p>Instance-holder resolution runs here too. It reads only the class and {@code ctxKeys}, so
     * it is decode by that definition even though its rejection is a binding-level concern; the
     * outcome rides on the signature and {@link #bindServiceMethod} surfaces it.
     *
     * <p>If the compiler was not invoked with {@code -parameters}, any parameter may lack a name.
     * A warning is logged proactively as soon as any nameless parameter is detected.
     */
    DecodeResult decodeServiceMethod(String className, String methodName, Set<String> ctxKeys) {
        if (className == null || methodName == null) {
            return new DecodeResult(null, Rejection.structural("service reference is incomplete"));
        }
        try {
            Class<?> cls = Class.forName(className, false, ctx.codegenLoader());
            MethodPick pick = pickMethod(cls, className, methodName);
            if (pick instanceof MethodPick.Rejected rejected) {
                return new DecodeResult(null, rejected.rejection());
            }
            var javaMethod = ((MethodPick.Picked) pick).method();
            boolean isStatic = java.lang.reflect.Modifier.isStatic(javaMethod.getModifiers());
            List<MethodRef.Param> ctorParams = List.of();
            Rejection unconstructible = null;
            if (!isStatic) {
                InstanceHolderResolution holder = resolveInstanceHolder(cls, methodName, className, ctxKeys);
                if (holder.rejection() != null) {
                    unconstructible = holder.rejection();
                    ctorParams = null;
                } else {
                    ctorParams = holder.ctorParams();
                }
            }
            if (Arrays.stream(javaMethod.getParameters()).anyMatch(p -> !p.isNamePresent())) {
                emitParametersWarning();
            }
            var decoded = new ArrayList<DecodedParam>();
            for (var p : javaMethod.getParameters()) {
                boolean isDsl = org.jooq.DSLContext.class.isAssignableFrom(p.getType());
                String pName = p.isNamePresent() ? p.getName() : null;
                String displayName = pName != null
                    ? pName
                    : (isDsl ? "dsl" : p.getType().getSimpleName());
                decoded.add(new DecodedParam(pName, displayName,
                    p.getParameterizedType().getTypeName(),
                    TypeName.get(p.getParameterizedType()),
                    isDsl,
                    isDsl ? null : classifySourcesType(p.getParameterizedType()).orElse(null),
                    isDsl ? null : dtoSourcesRejectionReason(p.getParameterizedType())));
            }
            return new DecodeResult(new ServiceSignature(className, methodName,
                TypeName.get(javaMethod.getGenericReturnType()), isStatic,
                declaredExceptionFqns(javaMethod), ctorParams, unconstructible,
                List.copyOf(decoded)), null);
        } catch (ClassNotFoundException e) {
            return new DecodeResult(null, new ReflectionError.ClassNotLoaded(className));
        }
    }

    /**
     * Claim reduction: folds {@link ArgBindingMap#byJavaName}, {@link #inferBindingsByType} and
     * the context-key set into one {@link ParamRole} per decoded parameter. Membership only; no
     * extraction runs here, and no rejection is raised. The role order mirrors the binding
     * precedence the loop used to apply inline: type-resolved {@code DSLContext} first, then the
     * name claims, then SOURCES shape recognition, then nothing.
     */
    ClaimedParams reduceClaims(ServiceSignature sig, ArgBindingMap argBindings, Set<String> ctxKeys,
            Map<String, GraphQLInputType> slotTypes) {
        return reduceClaims(sig, argBindings, ctxKeys, slotTypes, Set.of());
    }

    /**
     * Sigil-aware overload of {@link #reduceClaims}: {@code sessionBound} carries the Java
     * parameter names an argMapping {@code $session} entry bound to the session handle. An
     * explicit binding, so it claims right after the type-resolved {@code DSLContext} slot,
     * ahead of every name claim.
     */
    ClaimedParams reduceClaims(ServiceSignature sig, ArgBindingMap argBindings, Set<String> ctxKeys,
            Map<String, GraphQLInputType> slotTypes, Set<String> sessionBound) {
        var argByJavaName = inferBindingsByType(sig, argBindings.byJavaName(), ctxKeys, slotTypes);
        var roles = new ArrayList<ParamRole>(sig.params().size());
        for (var p : sig.params()) {
            if (p.isDslContext()) {
                roles.add(new ParamRole.Dsl());
                continue;
            }
            if (p.name() != null && sessionBound.contains(p.name())) {
                roles.add(new ParamRole.SessionBound());
                continue;
            }
            PathExpr resolvedPath = p.name() != null ? argByJavaName.get(p.name()) : null;
            if (resolvedPath != null) {
                roles.add(new ParamRole.ArgBound(resolvedPath));
            } else if (p.name() != null && ctxKeys.contains(p.name())) {
                roles.add(new ParamRole.ContextBound());
            } else if (p.sourcesShape() != null) {
                roles.add(new ParamRole.SourcesCandidate(p.sourcesShape()));
            } else {
                roles.add(new ParamRole.Unclaimed());
            }
        }
        return new ClaimedParams(List.copyOf(roles), argByJavaName);
    }

    /**
     * Bind: the argMapping override typo guard, then extraction and {@link MethodRef.Param}
     * minting over the carried roles. Every rejection raised here is binding-level by
     * construction; coordinate-level and signature-fit verdicts have already been decided by
     * {@link ServiceDirectiveResolver}'s classify phase and cannot be masked by a parameter
     * declared before the one they concern.
     *
     * <p>{@code batchKeyColumns} is the batch key the classify phase resolved: the key owner's
     * primary key at a child site, empty at the root. The key owner is the parent's own table at a
     * {@code @table}-parent site and the table the {@code Sources} element type names at a
     * class-backed one, which is why this is the resolved columns and not the parent's primary key.
     * A {@link ParamRole.SourcesCandidate} reaching bind with no key columns exists only where
     * classify declined to claim it (a root {@code List<XRecord>} input bean), which is why the
     * fallback below is binding's own diagnostic rather than an invariant throw.
     *
     * <p>An explicit override entry ({@code key != value}) whose target is not among the resolved
     * method's parameter names fails with a typo-guard message naming the directive site, the
     * override target, and the available parameter names.
     */
    ServiceReflectionResult bindServiceMethod(ServiceSignature sig, ClaimedParams claims,
            ArgBindingMap argBindings, Set<String> ctxKeys, List<ColumnRef> batchKeyColumns,
            Map<String, GraphQLInputType> slotTypes) {
        if (sig.unconstructible() != null) {
            return new ServiceReflectionResult(null, sig.unconstructible());
        }
        String typoGuard = checkOverrideTargets(argBindings.byJavaName(), sig.namedParameters(),
            sig.methodName(), sig.className());
        if (typoGuard != null) {
            return new ServiceReflectionResult(null, Rejection.structural(typoGuard));
        }
        var argByJavaName = claims.argByJavaName();
        var params = new ArrayList<MethodRef.Param>();
        for (int i = 0; i < sig.params().size(); i++) {
            var p = sig.params().get(i);
            switch (claims.roleOf(i)) {
                case ParamRole.Dsl ignored ->
                    params.add(new MethodRef.Param.Typed(p.displayName(), p.typeName(), p.javaType(),
                        new ParamSource.DslContext()));
                // The $session-bound slot: structural, supplied by the generator off the resolved
                // DSLContext's Configuration data. Whether a handle exists (and matches the
                // declared type) is the validator's session-hook pass, over the resolved carrier.
                case ParamRole.SessionBound ignored ->
                    params.add(new MethodRef.Param.Typed(p.displayName(), p.typeName(), p.javaType(),
                        new ParamSource.SessionHandle()));
                case ParamRole.ArgBound bound -> {
                    ArgExtraction ext = argExtraction(p.typeName(),
                        resolvePathLeafType(bound.path(), slotTypes),
                        "parameter '" + p.displayName() + "' of method '" + sig.methodName()
                            + "' in class '" + sig.className() + "'");
                    if (ext instanceof ArgExtraction.Rejected rej) {
                        return new ServiceReflectionResult(null, rej.rejection());
                    }
                    params.add(new MethodRef.Param.Typed(p.displayName(), p.typeName(), p.javaType(),
                        new ParamSource.Arg(((ArgExtraction.Resolved) ext).extraction(), bound.path())));
                }
                case ParamRole.ContextBound ignored ->
                    params.add(new MethodRef.Param.Typed(p.displayName(), p.typeName(), p.javaType(),
                        new ParamSource.Context()));
                case ParamRole.SourcesCandidate candidate -> {
                    if (!batchKeyColumns.isEmpty()) {
                        params.add(new MethodRef.Param.Sourced(p.displayName(), candidate.shape().wrap(),
                            batchKeyColumns, candidate.shape().container()));
                        break;
                    }
                    // No key columns, so classify declined to claim this candidate. Only the root
                    // List<XRecord> input bean gets here (every anonymous-key shape is answered by
                    // the classify phase at every coordinate); it is the canonical
                    // InputBeanResolver shape and falls through to the arg-mismatch arm.
                    if (p.name() == null) {
                        return new ServiceReflectionResult(null,
                            new ReflectionError.ParameterNamesMissing(sig.className(), sig.methodName()));
                    }
                    if (!(candidate.shape().wrap() instanceof SourceKey.Wrap.TableRecord)) {
                        return new ServiceReflectionResult(null,
                            new ServiceMethodCallError.UnrecognizedSourcesType(
                                p.displayName(), sig.methodName(), p.typeName()));
                    }
                    return new ServiceReflectionResult(null,
                        argumentParameterMismatch(p, sig, argByJavaName, ctxKeys, slotTypes));
                }
                case ParamRole.Unclaimed ignored -> {
                    if (p.name() == null) {
                        return new ServiceReflectionResult(null,
                            new ReflectionError.ParameterNamesMissing(sig.className(), sig.methodName()));
                    }
                    // A DTO-shape parameter (List<DTO> / Set<DTO>) at a batching coordinate is
                    // answered by the classify phase, which owns the "this coordinate batches, and
                    // your DTO parameter cannot be its key" verdict; the root's List<DTO> has no
                    // batching context and lands here, on the arg-mismatch arm (pinned by
                    // dtoSources_onRootField_pointsAtArgCtxMismatch).
                    return new ServiceReflectionResult(null,
                        argumentParameterMismatch(p, sig, argByJavaName, ctxKeys, slotTypes));
                }
            }
        }
        MethodRef.CallShape callShape;
        if (sig.isStatic()) {
            // A $session-bound slot forces the dsl local too: its extraction reads the handle
            // off the resolved DSLContext's own Configuration data.
            boolean needsDslLocal = params.stream()
                .anyMatch(p -> p.source() instanceof ParamSource.DslContext
                    || p.source() instanceof ParamSource.SessionHandle);
            callShape = new MethodRef.CallShape.Static(needsDslLocal);
        } else {
            callShape = new MethodRef.CallShape.InstanceWithDslHolder(sig.ctorParams());
        }
        return new ServiceReflectionResult(
            new MethodRef.Service(sig.className(), sig.methodName(), sig.returnType(),
                List.copyOf(params), sig.declaredExceptions(), callShape),
            null);
    }

    /**
     * The name-mismatch rejection for a parameter that matched no GraphQL argument and no context
     * key, with its {@code argMapping} suggestion. The suggestion pre-fills a concrete dot-path
     * when exactly one reachable slot field has the parameter's Java type, and falls back to a
     * {@code <fieldName>} placeholder otherwise.
     */
    private Rejection argumentParameterMismatch(DecodedParam p, ServiceSignature sig,
            Map<String, PathExpr> argByJavaName, Set<String> ctxKeys,
            Map<String, GraphQLInputType> slotTypes) {
        String suggestion;
        if (argByJavaName.isEmpty()) {
            suggestion = " — this field declares no GraphQL arguments;"
                + " remove the Java parameter, add a matching GraphQL argument to the field,"
                + " or register a context key that supplies it";
        } else {
            String soleArg = argByJavaName.size() == 1
                ? argByJavaName.keySet().iterator().next()
                : "<argName>";
            String reachablePath = unambiguousReachablePath(p.typeName(), slotTypes);
            String pathExample;
            String pathTrailer;
            if (reachablePath != null) {
                pathExample = "argMapping: \"" + p.displayName() + ": " + reachablePath + "\"";
                pathTrailer = " — that path is the only field reachable from the available"
                    + " arguments whose type matches '" + p.typeName() + "', so the suggestion"
                    + " is concrete";
            } else {
                pathExample = "argMapping: \"" + p.displayName() + ": " + soleArg + ".<fieldName>\"";
                pathTrailer = " when the parameter pulls one field out of a wrapper input"
                    + " type";
            }
            suggestion = " — either rename the Java parameter to match one of the available argument names, or bind explicitly via the @service directive's argMapping field"
                + " (e.g. argMapping: \"" + p.displayName() + ": " + soleArg + "\""
                + ", which reads as \"the Java parameter named '" + p.displayName()
                + "' binds to the GraphQL argument named '" + soleArg + "'\")."
                + " The right-hand side may also be a dot-path into a nested"
                + " input field (e.g. " + pathExample + ")"
                + pathTrailer;
        }
        return new ServiceMethodCallError.ArgumentParameterMismatch(
            p.displayName(), sig.methodName(),
            List.copyOf(argByJavaName.keySet()),
            List.copyOf(ctxKeys),
            suggestion);
    }

    /**
     * Outcome of {@link #pickMethod}: sealed ok-or-rejected, so the resolution arms arrive as
     * variants rather than as a null combination every caller re-tests. {@link Rejected} carries
     * the typed rejection (method-not-found {@link Rejection.AuthorError.UnknownName},
     * {@link ReflectionError.AmbiguousMethod} when more than one declaration shares the name, or
     * the seam-filter arms {@link ReflectionError.SeamParameterMissing} /
     * {@link ReflectionError.SeamCandidateAmbiguous} when a {@link SeamFilter} was supplied).
     */
    private sealed interface MethodPick {
        record Picked(java.lang.reflect.Method method) implements MethodPick {}
        record Rejected(Rejection rejection) implements MethodPick {}
    }

    /**
     * The session-hook overload selector, passed to {@link #pickMethod} as an explicit input:
     * among same-named declarations, exactly one must carry exactly one seam parameter
     * ({@code org.jooq.Configuration} or {@code java.sql.Connection}). jOOQ emits same-named
     * {@code Field}-expression overloads beside every executing method, so overloading is the
     * normal case on the {@code Routines} path and the seam rule resolves it without a grammar
     * for naming an overload.
     */
    enum SeamFilter { SESSION_HOOK }

    /** True when the parameter is seam-typed; exact types, so the selector is predictable. */
    private static boolean isSeamParameter(java.lang.reflect.Parameter p) {
        return p.getType() == org.jooq.Configuration.class || p.getType() == java.sql.Connection.class;
    }

    /** True when the method carries exactly one seam parameter, anywhere in its list. */
    private static boolean carriesOneSeam(java.lang.reflect.Method m) {
        return Arrays.stream(m.getParameters()).filter(ServiceCatalog::isSeamParameter).count() == 1;
    }

    /** A candidate's rendered parameter list for the seam-filter rejection messages. */
    private static String renderSignature(java.lang.reflect.Method m) {
        return m.getName() + Arrays.stream(m.getParameters())
            .map(p -> p.getParameterizedType().getTypeName())
            .collect(Collectors.joining(", ", "(", ")"));
    }

    /**
     * Resolves the single declared method named {@code methodName} on {@code cls}. Shared by all
     * three reflect helpers: zero matches produce the typed {@code unknownServiceMethod}
     * {@link Rejection.AuthorError.UnknownName}; more than one produce
     * {@link ReflectionError.AmbiguousMethod} carrying every candidate's parameter arity.
     */
    private static MethodPick pickMethod(Class<?> cls, String className, String methodName) {
        return pickMethod(cls, className, methodName, null);
    }

    /**
     * The single method-resolution point, with the seam filter as an explicit input: a non-null
     * {@code seamFilter} narrows same-named declarations to those carrying exactly one seam
     * parameter before ambiguity is judged, so zero qualifying candidates produce
     * {@link ReflectionError.SeamParameterMissing} (naming every same-named declaration) and
     * several produce {@link ReflectionError.SeamCandidateAmbiguous} (naming the qualifying
     * ones). A null filter keeps the exact-name behaviour the three directive reflect helpers
     * rely on.
     */
    private static MethodPick pickMethod(Class<?> cls, String className, String methodName,
            SeamFilter seamFilter) {
        var methods = Arrays.stream(cls.getDeclaredMethods())
            .filter(m -> m.getName().equals(methodName))
            .toList();
        if (methods.isEmpty()) {
            var declaredMethodNames = Arrays.stream(cls.getDeclaredMethods())
                .map(java.lang.reflect.Method::getName)
                .distinct()
                .toList();
            return new MethodPick.Rejected(
                Rejection.unknownServiceMethod(
                    "method '" + methodName + "' not found in class '" + className + "'",
                    methodName, declaredMethodNames));
        }
        if (seamFilter != null) {
            var qualifying = methods.stream().filter(ServiceCatalog::carriesOneSeam).toList();
            if (qualifying.isEmpty()) {
                return new MethodPick.Rejected(new ReflectionError.SeamParameterMissing(
                    className, methodName,
                    methods.stream().map(ServiceCatalog::renderSignature).toList()));
            }
            if (qualifying.size() > 1) {
                return new MethodPick.Rejected(new ReflectionError.SeamCandidateAmbiguous(
                    className, methodName,
                    qualifying.stream().map(ServiceCatalog::renderSignature).toList()));
            }
            return new MethodPick.Picked(qualifying.get(0));
        }
        if (methods.size() > 1) {
            var arities = methods.stream()
                .map(java.lang.reflect.Method::getParameterCount)
                .toList();
            return new MethodPick.Rejected(new ReflectionError.AmbiguousMethod(className, methodName, arities));
        }
        return new MethodPick.Picked(methods.get(0));
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
     * override target ({@code javaTarget} differs from the path head) is among the resolved method's
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
        return checkOverrideTargets(argByJavaName, paramNames, methodName, className);
    }

    /** Reflection-free form of {@link #checkOverrideTargets}, over a decoded parameter-name set. */
    private static String checkOverrideTargets(Map<String, PathExpr> argByJavaName,
                                               Set<String> paramNames,
                                               String methodName, String className) {
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
     * {@link #bindServiceMethod}, with the same override typo guard; an override entry
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
            if (pick instanceof MethodPick.Rejected rejected) {
                return new ServiceReflectionResult(null, rejected.rejection());
            }
            var javaMethod = ((MethodPick.Picked) pick).method();
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
     * <p>The assignability half is javac's own rule at the emitted call site: the helper is
     * rendered as {@code <Helper>.<method>(table)} inside a {@code $project} unit whose
     * {@code table} parameter is typed from {@link TableRef#tableClass()}, so a helper typed on
     * another table fails a consumer's build with no line back to the SDL. Two ordered layers
     * enforce it as value comparisons on {@code parentTable}, both behind the
     * {@code Table}-subtype gate above:
     *
     * <ol>
     *   <li><b>Table identity.</b> When the parameter class is itself a catalog table
     *       ({@link JooqCatalog#findTableByClass}), it must denote the parent's table
     *       ({@link TableRef#denotesSameTableAs}). {@code org.jooq.Table}, {@code TableImpl},
     *       and hand-written table supertypes are not catalog entries, so they admit. This
     *       layer carries the non-generic case ({@code h(Film t)}) alone.</li>
     *   <li><b>Record type.</b> When the parameter's generic type is {@code X<R>} with {@code R}
     *       a concrete class, {@code R} must be the parent's {@link TableRef#recordClass()}.
     *       {@code Table<FilmRecord>} passes on a {@code film} parent; {@code Table<ActorRecord>}
     *       and {@code Table<Record>} do not, matching what javac accepts for a generated
     *       {@code Film}, which implements {@code Table<FilmRecord>} and nothing else.</li>
     * </ol>
     *
     * <p>Both layers fail open, with the {@code graphitron-sakila-example} compile as the
     * backstop: a parameter typed on a generated table outside this catalog is invisible to
     * {@code findTableByClass}, and layer 2 skips wildcards, raw {@code Table}, and type
     * variables (including a concretely-bounded one, which javac rejects and this admits).
     * Neither can produce a false rejection.
     *
     * <p>Both {@code className} and {@code methodName} are required: the {@code @externalField}
     * arm in {@link FieldBuilder} surfaces a targeted "missing className" error before this call
     * and defaults {@code methodName} to the GraphQL field name when the directive omits
     * {@code method:}.
     */
    ServiceReflectionResult reflectExternalField(String className, String methodName,
            TableRef parentTable) {
        try {
            Class<?> cls = Class.forName(className, false, ctx.codegenLoader());
            MethodPick pick = pickMethod(cls, className, methodName);
            if (pick instanceof MethodPick.Rejected rejected) {
                return new ServiceReflectionResult(null, rejected.rejection());
            }
            var javaMethod = ((MethodPick.Picked) pick).method();
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
            Rejection parentTableMismatch = checkExternalFieldParentTable(p, parentTable, className, methodName);
            if (parentTableMismatch != null) {
                return new ServiceReflectionResult(null, parentTableMismatch);
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
     * The two assignability layers of {@link #reflectExternalField}'s parameter contract,
     * documented on that method. Returns the rejection for the first layer the sole parameter
     * fails, or null when both admit. Runs behind the {@code Table}-subtype gate, so
     * {@code p.getType()} is always a {@code Table} here.
     *
     * <p>Neither layer resolves, loads, or re-derives anything: {@code parentTable} already
     * carries both facts ({@code tableClass} via {@link TableRef#denotesSameTableAs},
     * {@code recordClass} directly), so no live jOOQ handle for the parent has to reach this
     * class. The catalog side of layer 1 is class-keyed on the reflected parameter type, which
     * is a live {@code Class} the reflection already holds.
     */
    private Rejection checkExternalFieldParentTable(java.lang.reflect.Parameter p,
            TableRef parentTable, String className, String methodName) {
        if (parentTable == null) {
            return null;
        }
        // Layer 1: a parameter typed on a *generated* table must be typed on the parent's.
        // Non-catalog classes (org.jooq.Table, TableImpl, a hand-written table base) are not
        // entries and admit, which is correct — they accept the parent table.
        var declaredEntry = ctx.catalog.findTableByClass(p.getType());
        if (declaredEntry.isPresent()) {
            var entry = declaredEntry.get();
            TableRef declared = entry.toTableRef(entry.table().getName());
            if (!declared.denotesSameTableAs(parentTable)) {
                return Rejection.structural("method '" + methodName + "' in class '" + className
                    + "' takes parameter type '" + p.getType().getSimpleName()
                    + "', which does not accept the parent table '" + parentTable.tableName()
                    + "' (jOOQ class '" + parentTable.tableClass() + "'); type the parameter as"
                    + " the parent's table class or widen it to org.jooq.Table<?>");
            }
            return null;
        }
        // Layer 2: `X<R>` with a concrete `R` must name the parent's record class. Wildcards,
        // raw `Table`, and type variables carry no concrete `R` and are skipped.
        if (parentTable.recordClass() == null
                || !(p.getParameterizedType() instanceof java.lang.reflect.ParameterizedType pt)) {
            return null;
        }
        var typeArgs = pt.getActualTypeArguments();
        if (typeArgs.length != 1 || !(typeArgs[0] instanceof Class<?> recordArg)) {
            return null;
        }
        if (!ClassName.get(recordArg).equals(parentTable.recordClass())) {
            return Rejection.structural("method '" + methodName + "' in class '" + className
                + "' takes parameter type '" + p.getType().getSimpleName() + "<"
                + recordArg.getSimpleName() + ">', which does not accept the parent table '"
                + parentTable.tableName() + "' (jOOQ record class '" + parentTable.recordClass()
                + "'); parameterise the parameter with the parent's record class or widen it to"
                + " org.jooq.Table<?>");
        }
        return null;
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
            var iot = asInputObject(current);
            if (iot == null) return null;
            var field = iot.getField(segments.get(i).name());
            if (field == null) return null;
            current = field.getType();
        }
        return current;
    }

    /**
     * Whether the SDL declaration a {@link PathExpr} binds to carries {@code @nodeId}: the argument
     * itself on a single-segment path, otherwise the input-object field the last segment names. The
     * same walk as {@link #resolvePathLeafType}, asking after the declaration rather than its type,
     * which is why the single-segment case needs {@code fieldDef}: a head slot's type is in
     * {@code slotTypes} but its directives are only on the argument.
     *
     * <p>A type gate reads this and stands aside on {@code true}. Not a laxity: a {@code @nodeId}
     * leaf is a wire value that is decoded before anything consumes it, so the parameter receives a
     * key column's own value and never the {@code ID}'s coercion output. A gate comparing the SDL
     * leaf's coercion output against the declared Java type is therefore comparing two things that
     * never meet, and it rejects exactly the binding the decode exists to make work. Whether the
     * decode then resolves, and whether the key column's type is one the parameter can take, are
     * both the store's to answer, with an arm for each: the walk runs before capture and has neither
     * the key list nor the catalog to answer them with.
     */
    static boolean pathLeafDeclaresNodeId(PathExpr path, GraphQLFieldDefinition fieldDef,
                                          Map<String, GraphQLInputType> slotTypes) {
        if (path == null || slotTypes == null) return false;
        var segments = path.segments();
        if (segments.size() == 1) {
            var argument = fieldDef == null ? null : fieldDef.getArgument(path.headName());
            return argument != null && argument.hasAppliedDirective(BuildContext.DIR_NODE_ID);
        }
        GraphQLInputType current = slotTypes.get(path.headName());
        for (int i = 1; i < segments.size() && current != null; i++) {
            var iot = asInputObject(current);
            if (iot == null) return false;
            var field = iot.getField(segments.get(i).name());
            if (field == null) return false;
            if (i == segments.size() - 1) {
                return field.hasAppliedDirective(BuildContext.DIR_NODE_ID);
            }
            current = field.getType();
        }
        return false;
    }

    /** One path step's input object, past a non-null and one list wrapper, or {@code null}. */
    private static GraphQLInputObjectType asInputObject(GraphQLInputType type) {
        GraphQLType t = type;
        while (t instanceof GraphQLNonNull nn) t = nn.getWrappedType();
        if (t instanceof GraphQLList lst) {
            t = lst.getWrappedType();
            while (t instanceof GraphQLNonNull nn2) t = nn2.getWrappedType();
        }
        return t instanceof GraphQLInputObjectType iot ? iot : null;
    }

    /**
     * Classification of a {@code @service} SOURCES parameter: the per-row shape
     * ({@link SourceKey.Wrap}) and the container axis ({@link LoaderRegistration.Container})
     * needed to construct {@link MethodRef.Param.Sourced}. The columns axis is the caller's
     * batch-key input and is not repeated here.
     *
     * <p>The {@link SourceKey.Wrap.TableRecord} arm's class is what lets a class-backed parent host a
     * batched child: {@link #resolveTableByRecordClassName} turns it into a real table whose primary
     * key is a real column tuple, so the coordinate's question becomes "can this parent produce a
     * record of that table?" rather than "what is this parent's own primary key?". That lookup is the
     * classify phase's to make, not this record's to carry.
     */
    record SourcesShape(SourceKey.Wrap wrap, LoaderRegistration.Container container) {}

    /**
     * Classifies the element type of a {@code List<?>} or {@code Set<?>} SOURCES parameter into
     * a {@link SourcesShape}, or returns {@link Optional#empty()} when the type is not recognised.
     *
     * <p>Purely a type question, and deliberately no catalog lookup: whether a recognised shape can
     * actually be honoured depends on the coordinate (a root type has no parent to batch against; a
     * PK-less parent table and a class-backed parent that cannot produce the declared record each
     * fail for their own reason), and the classify phase decides that. Keeping both out of here is
     * what lets one recognition serve the {@link MethodRef.Param.Sourced} construction, the
     * {@link ServiceMethodCallError.SourcesOnPkLessParent} rejection, and the class-backed parent's
     * key resolution alike, and what keeps {@link #decodeServiceMethod} pure over the class, the
     * method name and the context keys.
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
            + " Declare the batch key as a jOOQ record class instead: the element type names the"
            + " table the batch keys on, and on a class-backed parent the parent must either be"
            + " that record, expose exactly one zero-arg accessor returning it, or declare"
            + " @sourceRow naming a static method that produces it from the parent";
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
     * fires from the binder); SOURCES-shape parameters are skipped so the SOURCES candidate role
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
        var eligible = new ArrayList<InferParam>();
        for (var p : javaMethod.getParameters()) {
            if (org.jooq.Table.class.isAssignableFrom(p.getType())) continue;
            if (org.jooq.DSLContext.class.isAssignableFrom(p.getType())) continue;
            if (!p.isNamePresent()) continue;
            if (classifySourcesType(p.getParameterizedType()).isPresent()) continue;
            eligible.add(new InferParam(p.getName(), p.getParameterizedType().getTypeName()));
        }
        return inferBindingsByType(paramNames, eligible, existing, ctxKeys, slotTypes);
    }

    /**
     * Decoded-signature form of {@link #inferBindingsByType}: the eligibility filter reads the
     * precomputed {@link DecodedParam#sourcesShape()} rather than re-running shape recognition.
     */
    private Map<String, PathExpr> inferBindingsByType(
            ServiceSignature sig,
            Map<String, PathExpr> existing,
            Set<String> ctxKeys,
            Map<String, GraphQLInputType> slotTypes) {
        if (slotTypes == null || slotTypes.isEmpty()) return existing;
        var paramNames = new HashSet<>(sig.namedParameters());
        var eligible = new ArrayList<InferParam>();
        for (var p : sig.params()) {
            if (p.isDslContext() || p.name() == null || p.sourcesShape() != null) continue;
            eligible.add(new InferParam(p.name(), p.typeName()));
        }
        return inferBindingsByType(paramNames, eligible, existing, ctxKeys, slotTypes);
    }

    /** A named parameter and its declared Java type, the only two facts inference reads. */
    private record InferParam(String name, String typeName) {}

    private Map<String, PathExpr> inferBindingsByType(
            Set<String> paramNames,
            List<InferParam> eligible,
            Map<String, PathExpr> existing,
            Set<String> ctxKeys,
            Map<String, GraphQLInputType> slotTypes) {
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

        var unboundParams = new ArrayList<InferParam>();
        for (var p : eligible) {
            if (existing.containsKey(p.name())) continue;
            if (ctxKeys.contains(p.name())) continue;
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
            String paramType = unboundParams.get(0).typeName();
            boolean slotIsNamedInputOrEnum = slotJavaType == null;
            boolean paramIsScalarJavaType = isClassifiedScalarJavaTypeName(paramType);
            if (slotIsNamedInputOrEnum
                    && !paramIsScalarJavaType
                    && !anyReachableNestedMatch(paramType, unclaimedSlotNames, slotTypes)) {
                augmented.put(unboundParams.get(0).name(), PathExpr.head(slotName));
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
            paramsByType.computeIfAbsent(p.typeName(), k -> new ArrayList<>()).add(p.name());
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
            if (augmented.containsKey(p.name())) continue;
            PathExpr nested = inferNestedFieldByName(
                p.name(), p.typeName(), unclaimedSlotNames, slotTypes);
            if (nested != null) {
                augmented.put(p.name(), nested);
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

    // ===== Session-hook resolution =====

    /**
     * Outcome of {@link #resolveSessionHooks}: the total resolved carrier plus the typed
     * rejections that forced a {@link SessionHooks.NotConfigured} fallback (empty on success).
     * The builder drains the rejections as schema-wide, coordinate-less
     * {@link ValidationError}s; the carrier stays total so every emit-side reader keeps its
     * one exhaustive fork.
     */
    record SessionHookResolution(SessionHooks hooks, List<Rejection> rejections) {}

    /**
     * Reflects the authored {@code <mount>}/{@code <unmount>} strings into the resolved
     * {@link SessionHooks} carrier. A session hook is a user-provided Java method, so this is
     * {@link MethodRef.StaticOnly}'s population through the same reflection path the directive
     * helpers use: {@link #pickMethod} with the seam filter as the overload selector, the
     * seam parameter decided once here into {@link ParamSource.SessionSeam}, mount payload
     * parameters as {@link ParamSource.Context} (feeding the contextArgument classifier as an
     * additional root), and the unmount's optional handle parameter as
     * {@link ParamSource.SessionHandle}, type-checked against the mount's reflected return.
     */
    SessionHookResolution resolveSessionHooks(no.sikt.graphitron.rewrite.session.SessionStateConfig config) {
        if (!(config instanceof no.sikt.graphitron.rewrite.session.SessionStateConfig.MethodHooks methodHooks)) {
            return new SessionHookResolution(SessionHooks.NotConfigured.INSTANCE, List.of());
        }
        var rejections = new ArrayList<Rejection>();
        MethodRef.StaticOnly mount = reflectSessionHook(methodHooks.mount(), true, rejections);
        MethodRef.StaticOnly unmount = methodHooks.unmount()
            .map(u -> reflectSessionHook(u, false, rejections))
            .orElse(null);
        if (mount == null || (methodHooks.unmount().isPresent() && unmount == null)) {
            return new SessionHookResolution(SessionHooks.NotConfigured.INSTANCE, List.copyOf(rejections));
        }
        boolean handleLess = TypeName.VOID.equals(mount.returnType());
        if (unmount != null) {
            var handleParams = unmount.params().stream()
                .filter(p -> p.source() instanceof ParamSource.SessionHandle)
                .toList();
            if (!handleParams.isEmpty()) {
                String unmountParamTypes = handleParams.stream()
                    .map(MethodRef.Param::typeName)
                    .collect(Collectors.joining(", "));
                boolean typeAgrees = handleParams.size() == 1 && !handleLess
                    && ((MethodRef.Param.Typed) handleParams.get(0)).javaType().equals(mount.returnType());
                if (!typeAgrees) {
                    rejections.add(new ReflectionError.HandleTypeMismatch(
                        mount.className(), mount.methodName(),
                        handleLess ? "void" : mount.returnType().toString(),
                        unmount.className(), unmount.methodName(),
                        unmountParamTypes));
                    return new SessionHookResolution(SessionHooks.NotConfigured.INSTANCE, List.copyOf(rejections));
                }
            }
        }
        SessionHooks hooks = handleLess
            ? new SessionHooks.HandleLess(mount, Optional.ofNullable(unmount))
            : new SessionHooks.Handled(mount, mount.returnType(), Optional.ofNullable(unmount));
        return new SessionHookResolution(hooks, List.of());
    }

    /**
     * Reflects one hook reference into a {@link MethodRef.StaticOnly}, or registers the typed
     * rejection and returns {@code null}. {@code isMount} decides how non-seam parameters
     * classify: payload ({@link ParamSource.Context}, name required) on the mount, handle
     * ({@link ParamSource.SessionHandle}, structural, no name needed) on the unmount.
     */
    private MethodRef.StaticOnly reflectSessionHook(
            no.sikt.graphitron.rewrite.session.SessionStateConfig.HookRef ref,
            boolean isMount, List<Rejection> rejections) {
        String className = ref.className();
        String methodName = ref.methodName();
        Class<?> cls;
        try {
            cls = Class.forName(className, false, ctx.codegenLoader());
        } catch (ClassNotFoundException e) {
            rejections.add(new ReflectionError.ClassNotLoaded(className));
            return null;
        }
        MethodPick pick = pickMethod(cls, className, methodName, SeamFilter.SESSION_HOOK);
        if (pick instanceof MethodPick.Rejected rejected) {
            rejections.add(rejected.rejection());
            return null;
        }
        var javaMethod = ((MethodPick.Picked) pick).method();
        int mods = javaMethod.getModifiers();
        if (!java.lang.reflect.Modifier.isStatic(mods) || !java.lang.reflect.Modifier.isPublic(mods)) {
            rejections.add(new ReflectionError.HookNotStatic(className, methodName));
            return null;
        }
        var checked = declaredExceptionFqns(javaMethod);
        if (!checked.isEmpty()) {
            rejections.add(new ReflectionError.HookThrowsChecked(className, methodName, checked));
            return null;
        }
        var params = new ArrayList<MethodRef.Param>();
        for (var p : javaMethod.getParameters()) {
            String typeName = p.getParameterizedType().getTypeName();
            TypeName javaType = TypeName.get(p.getParameterizedType());
            if (isSeamParameter(p)) {
                var kind = p.getType() == org.jooq.Configuration.class
                    ? ParamSource.SessionSeam.Kind.CONFIGURATION
                    : ParamSource.SessionSeam.Kind.CONNECTION;
                String name = p.isNamePresent() ? p.getName()
                    : (kind == ParamSource.SessionSeam.Kind.CONFIGURATION ? "cfg" : "connection");
                params.add(new MethodRef.Param.Typed(name, typeName, javaType,
                    new ParamSource.SessionSeam(kind)));
                continue;
            }
            if (isMount) {
                // A payload parameter's name is the public factory-slot identity, so a
                // synthesized fallback is exactly the throwaway identifier consumer-facing
                // generated code must not carry: fail through the existing -parameters gate.
                if (!p.isNamePresent()) {
                    emitParametersWarning();
                    rejections.add(new ReflectionError.ParameterNamesMissing(className, methodName));
                    return null;
                }
                params.add(new MethodRef.Param.Typed(p.getName(), typeName, javaType,
                    new ParamSource.Context()));
            } else {
                String name = p.isNamePresent() ? p.getName() : "handle";
                params.add(new MethodRef.Param.Typed(name, typeName, javaType,
                    new ParamSource.SessionHandle()));
            }
        }
        return new MethodRef.StaticOnly(className, methodName,
            TypeName.get(javaMethod.getGenericReturnType()), List.copyOf(params), List.of());
    }

    // ===== Result container =====

    /**
     * Carries the result of {@link #bindServiceMethod}: either a successfully resolved
     * {@link MethodRef} or a typed {@link Rejection} carrying the failure shape (so consumers
     * that wrap with caller-specific prose can preserve {@link Rejection.AuthorError.UnknownName}
     * fields rather than collapsing back to {@link Rejection.AuthorError.Structural}).
     */
    record ServiceReflectionResult(MethodRef ref, Rejection rejection) {
        boolean failed() { return rejection != null; }
    }
}
