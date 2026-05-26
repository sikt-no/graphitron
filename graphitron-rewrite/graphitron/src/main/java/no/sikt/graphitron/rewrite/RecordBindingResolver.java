package no.sikt.graphitron.rewrite;

import graphql.language.EnumValue;
import graphql.language.SourceLocation;
import graphql.schema.GraphQLAppliedDirective;
import graphql.schema.GraphQLArgument;
import graphql.schema.GraphQLDirectiveContainer;
import graphql.schema.GraphQLFieldDefinition;
import graphql.schema.GraphQLInputObjectField;
import graphql.schema.GraphQLInputObjectType;
import graphql.schema.GraphQLList;
import graphql.schema.GraphQLNamedType;
import graphql.schema.GraphQLNonNull;
import graphql.schema.GraphQLObjectType;
import graphql.schema.GraphQLType;
import graphql.schema.GraphQLTypeUtil;
import no.sikt.graphitron.rewrite.model.DmlKind;
import no.sikt.graphitron.rewrite.model.ProducerBinding;
import no.sikt.graphitron.rewrite.model.Rejection;
import no.sikt.graphitron.rewrite.model.SourceKey;
import no.sikt.graphitron.rewrite.model.TableRef;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import static no.sikt.graphitron.rewrite.BuildContext.ARG_CLASS_NAME;
import static no.sikt.graphitron.rewrite.BuildContext.ARG_METHOD;
import static no.sikt.graphitron.rewrite.BuildContext.ARG_NAME;
import static no.sikt.graphitron.rewrite.BuildContext.ARG_SERVICE_REF;
import static no.sikt.graphitron.rewrite.BuildContext.ARG_TYPE_NAME;
import static no.sikt.graphitron.rewrite.BuildContext.DIR_MUTATION;
import static no.sikt.graphitron.rewrite.BuildContext.DIR_RECORD;
import static no.sikt.graphitron.rewrite.BuildContext.DIR_SERVICE;
import static no.sikt.graphitron.rewrite.BuildContext.DIR_TABLE;
import static no.sikt.graphitron.rewrite.BuildContext.DIR_TABLE_METHOD;
import static no.sikt.graphitron.rewrite.BuildContext.argString;
import static no.sikt.graphitron.rewrite.BuildContext.asMap;
import static no.sikt.graphitron.rewrite.BuildContext.locationOf;

/**
 * R96: derives SDL → backing-class bindings from reflection alone. Replaces the directive-driven
 * binding population that {@link TypeBuilder} used to perform inside {@code buildResultType} and
 * {@code buildNonTableInputType}.
 *
 * <p>The walk grounds at root producers ({@code @service} method returns, {@code @table}
 * resolutions, {@code @tableMethod} returns) and extends through parent-accessor return types.
 * Bindings accumulate into a per-SDL-type collection set on each axis (result + input); after the
 * walk completes, the per-type set is folded into a single agreed {@link Class}, an empty
 * resolution, or a {@link Rejection.AuthorError.RecordBindingMultiProducer} diagnostic.
 *
 * <p>The {@code @record} directive is read only to surface a directive-ignored warning; it does
 * not contribute to the binding under R96.
 *
 * <p>The per-SDL-type fold is the producer-side rejection point for backing-class disagreement,
 * surfacing as {@link Rejection.AuthorError.RecordBindingMultiProducer}. The consumer is
 * {@link FieldBuilder} (via {@code resolveRecordAccessor}), which assumes the resolved
 * {@code Class} the binding produces is the class field accessors will be emitted against.
 */
final class RecordBindingResolver {

    private final BuildContext ctx;
    private final ServiceCatalog svc;

    /**
     * Per-SDL-type collection set of observed bindings on the result axis. A multimap during
     * the walk; folded at the end of the walk into {@link #resultMemo}.
     */
    private final Map<String, List<ProducerBinding>> resultObserved = new LinkedHashMap<>();
    private final Map<String, List<ProducerBinding>> inputObserved = new LinkedHashMap<>();

    /** Post-fold memoization. Maps SDL type → agreed binding, or {@code null} for empty / failed. */
    private final Map<String, Class<?>> resultMemo = new LinkedHashMap<>();
    private final Map<String, Class<?>> inputMemo = new LinkedHashMap<>();

    /** Multi-producer rejections, keyed by SDL type, on either axis. */
    private final Map<String, Rejection.AuthorError.RecordBindingMultiProducer> rejections =
        new LinkedHashMap<>();

    /** Reachable SDL types (any axis) — used to gate the directive-ignored warning. */
    private final Set<String> reachable = new LinkedHashSet<>();

    /**
     * R178 DML mutation payload bindings, observed for the payload SDL type of every DML
     * {@code @mutation} field whose payload is a non-{@code @table} SDL Object. Stored on a
     * dedicated axis (not result/input) so the existing record-binding fold and the
     * {@code recordBackingClasses} pump in {@link TypeBuilder#buildTypes()} continue to ignore
     * the new arm. A future cutover commit may lift these observations into the result-axis
     * fold; until then the dedicated map keeps the DML-emitted shape isolated from
     * developer-authored {@code @record} bindings.
     */
    private final Map<String, ProducerBinding.DmlEmitted> dmlEmittedMemo = new LinkedHashMap<>();

    /**
     * R178 step 2b: dedicated map for {@link ProducerBinding.ServiceEmitted} observations from
     * {@code @service} mutation fields with payload-returning shapes. Mirrors
     * {@link #dmlEmittedMemo}.
     */
    private final Map<String, ProducerBinding.ServiceEmitted> serviceEmittedMemo = new LinkedHashMap<>();

    RecordBindingResolver(BuildContext ctx, ServiceCatalog svc) {
        this.ctx = Objects.requireNonNull(ctx);
        this.svc = Objects.requireNonNull(svc);
    }

    /**
     * Runs the recursive walk: grounds at every root producer, propagates through parent-accessor
     * chains to a fixed point, then folds each per-type collection set into a single agreed
     * binding (or a multi-producer rejection).
     */
    void resolveAll() {
        groundRootProducers();
        propagateAccessorChains();
        foldAll();
    }

    /** Returns the resolved result-axis binding for an SDL type, or empty when none. */
    Optional<Class<?>> resolveResult(String sdlTypeName) {
        return Optional.ofNullable(resultMemo.get(sdlTypeName));
    }

    /** Returns the resolved input-axis binding for an SDL type, or empty when none. */
    Optional<Class<?>> resolveInput(String sdlTypeName) {
        return Optional.ofNullable(inputMemo.get(sdlTypeName));
    }

    /**
     * R178 DML mutation payload binding for an SDL type. Carries the inner {@link TableRef}
     * the DML producer emits rows for, the {@link DmlKind}, and the producer-side cardinality
     * lifted from the input {@code @table} arg. Held on a dedicated axis so the existing fold
     * and the {@link TypeBuilder#buildTypes()} {@code recordBackingClasses} pump don't see it.
     */
    Optional<ProducerBinding.DmlEmitted> resolveDmlEmitted(String sdlTypeName) {
        return Optional.ofNullable(dmlEmittedMemo.get(sdlTypeName));
    }

    /**
     * R178 step 2b: resolves the optional {@link ProducerBinding.ServiceEmitted} observation
     * for an SDL payload type whose producer is an {@code @service} mutation field with a
     * carrier-shaped payload. Mirrors {@link #resolveDmlEmitted}.
     */
    Optional<ProducerBinding.ServiceEmitted> resolveServiceEmitted(String sdlTypeName) {
        return Optional.ofNullable(serviceEmittedMemo.get(sdlTypeName));
    }

    /**
     * Predicate-form access to "did the walk produce a binding for this SDL type on either
     * axis." Consulted by the drop-manifest assertion and the additive corpus assertion to
     * pin reachability to the same definition the walk uses.
     */
    boolean fromAnyProducer(String sdlTypeName) {
        return reachable.contains(sdlTypeName);
    }

    /** Multi-producer rejection for the SDL type, or empty when none. */
    Optional<Rejection.AuthorError.RecordBindingMultiProducer> rejection(String sdlTypeName) {
        return Optional.ofNullable(rejections.get(sdlTypeName));
    }

    // ===== Phase 1: root producers =====

    private void groundRootProducers() {
        // @table on Object types: ground to jOOQ TableRecord class.
        ctx.schema.getAllTypesAsList().forEach(named -> {
            if (named.getName().startsWith("__")) return;
            if (named instanceof GraphQLObjectType obj && obj.hasAppliedDirective(DIR_TABLE)) {
                String tableSqlName = argString(obj, DIR_TABLE, ARG_NAME).orElse(obj.getName().toLowerCase());
                Optional<TableRef> tableOpt = svc.resolveTable(tableSqlName);
                tableOpt.ifPresent(table -> {
                    try {
                        Class<?> recordClass = Class.forName(
                            table.recordClass().reflectionName(), false, ctx.codegenLoader());
                        addResultObservation(obj.getName(), new ProducerBinding.RootTable(
                            recordClass, obj.getName(), tableSqlName, locationOf(obj)));
                    } catch (ClassNotFoundException ignored) {
                        // jOOQ catalog class missing: nothing to bind.
                    }
                });
            }
            if (named instanceof GraphQLInputObjectType inp && inp.hasAppliedDirective(DIR_TABLE)) {
                String tableSqlName = argString(inp, DIR_TABLE, ARG_NAME).orElse(inp.getName().toLowerCase());
                Optional<TableRef> tableOpt = svc.resolveTable(tableSqlName);
                tableOpt.ifPresent(table -> {
                    try {
                        Class<?> recordClass = Class.forName(
                            table.recordClass().reflectionName(), false, ctx.codegenLoader());
                        addInputObservation(inp.getName(), new ProducerBinding.RootTable(
                            recordClass, inp.getName(), tableSqlName, locationOf(inp)));
                    } catch (ClassNotFoundException ignored) {}
                });
            }
        });

        // @service, @tableMethod, and @mutation (R178 DmlEmitted) on field definitions.
        ctx.schema.getAllTypesAsList().forEach(named -> {
            if (!(named instanceof GraphQLObjectType obj)) return;
            if (named.getName().startsWith("__")) return;
            for (GraphQLFieldDefinition field : obj.getFieldDefinitions()) {
                groundServiceField(obj, field);
                groundTableMethodField(obj, field);
                groundDmlMutationField(obj, field);
            }
        });
    }

    private void groundServiceField(GraphQLObjectType parent, GraphQLFieldDefinition field) {
        if (!field.hasAppliedDirective(DIR_SERVICE)) return;
        GraphQLAppliedDirective dir = field.getAppliedDirective(DIR_SERVICE);
        var serviceArg = dir.getArgument(ARG_SERVICE_REF);
        if (serviceArg == null || serviceArg.getValue() == null) return;
        Map<String, Object> ref = asMap(serviceArg.getValue());
        String className = Optional.ofNullable(ref.get(ARG_CLASS_NAME)).map(Object::toString).orElse(null);
        String methodName = Optional.ofNullable(ref.get(ARG_METHOD)).map(Object::toString).orElse(null);
        if (className == null || methodName == null) return;

        Method method = findUniqueMethod(className, methodName);
        if (method == null) return;

        SourceLocation loc = locationOf(field);

        // Ground result-axis binding from method return-element type, but only when the
        // SDL return type carries @record. Plain SDL Objects (single-record carriers,
        // R75 Phase 1) and non-@record types do not consume the producer's return class
        // as their binding — the producer feeds their inner data field instead. Restricting
        // the result-side observation to @record SDL types matches the spec's intent (replace
        // the directive's className with reflection) while preserving R75's carrier shape.
        String resultSdl = unwrappedTypeName(field.getType());
        if (resultSdl != null) {
            var resultObjType = ctx.schema.getType(resultSdl);
            boolean sdlHasRecord = resultObjType instanceof GraphQLObjectType ro
                && ro.hasAppliedDirective(DIR_RECORD);
            if (sdlHasRecord) {
                Class<?> retElement = peelReturnElement(method.getGenericReturnType());
                if (retElement != null && shouldBind(retElement)) {
                    addResultObservation(resultSdl, new ProducerBinding.RootService(
                        retElement, parent.getName(), field.getName(), className, methodName, loc));
                }
            }
        }

        // R178 step 2b: ServiceEmitted observation for @service-carrier candidates. The check
        // is structural: the payload SDL must be a GraphQL Object with exactly one @table-typed
        // field whose record class matches the method's reflected return-element. Both NoBacking
        // (plain SDL Object) and ClassBacked (@record-bound) carriers ground here; this map is
        // independent of the main result-axis fold and the existing RootService observation
        // above. The classifier-side dispatch in FieldBuilder.classifyChildFieldOnResultType
        // reads through TypeBuilder.serviceEmittedBinding to construct
        // ChildField.SingleRecordTableField with Wrap.TableRecord at the data-field coord.
        groundServicePayloadBinding(parent, field, method, className, methodName, resultSdl, loc);

        // Ground input-axis bindings from method parameters → SDL arg types.
        // Argument mapping: parameter name = SDL arg name unless argMapping overrides.
        Map<String, String> argMappingOverrides = parseArgMapping(
            Optional.ofNullable(ref.get(BuildContext.ARG_ARG_MAPPING)).map(Object::toString).orElse(""));
        for (var p : method.getParameters()) {
            if (!p.isNamePresent()) continue;
            String paramName = p.getName();
            String sdlArgName = argMappingOverrides.getOrDefault(paramName, paramName);
            GraphQLArgument arg = field.getArgument(sdlArgName);
            if (arg == null) continue;
            String inputSdl = unwrappedTypeName(arg.getType());
            if (inputSdl == null) continue;
            Class<?> paramElement = peelReturnElement(p.getParameterizedType());
            if (paramElement == null || !shouldBind(paramElement)) continue;
            addInputObservation(inputSdl, new ProducerBinding.RootService(
                paramElement, parent.getName(), field.getName(), className, methodName, loc));
        }
    }

    /**
     * R178 step 2b: structural detection for an {@code @service}-carrier candidate. Grounds a
     * {@link ProducerBinding.ServiceEmitted} observation when the payload SDL Object exposes
     * exactly one {@code @table}-typed data field whose record class equals the
     * {@code @service} method's reflected return-element class. Skipped silently for shapes
     * the carrier mold doesn't admit (multiple {@code @table}-typed fields, no
     * {@code @table}-typed field, unresolvable inner table, class-load failure, type mismatch
     * between method return and the data field's record class).
     *
     * <p>R96 runs before per-type classification populates {@code ctx.types}, so the detection
     * reads directly from {@code ctx.schema} (the assembled GraphQL schema) and the catalog
     * via {@link ServiceCatalog#resolveTable}. The {@code @table}-typed predicate is "the
     * field's return type unwraps to a GraphQL Object that carries the {@code @table}
     * directive"; errors-shaped fields (polymorphic-of-{@code @error}) are excluded by the
     * "must be a GraphQL Object" check.
     */
    private void groundServicePayloadBinding(GraphQLObjectType parent, GraphQLFieldDefinition field,
            Method method, String className, String methodName, String resultSdl, SourceLocation loc) {
        if (resultSdl == null) return;
        GraphQLType payloadType = ctx.schema.getType(resultSdl);
        if (!(payloadType instanceof GraphQLObjectType payloadObj)) return;

        GraphQLFieldDefinition dataField = null;
        String dataFieldTableName = null;
        for (var f : payloadObj.getFieldDefinitions()) {
            String unwrappedFieldType = unwrappedTypeName(f.getType());
            if (unwrappedFieldType == null) continue;
            GraphQLType fieldType = ctx.schema.getType(unwrappedFieldType);
            if (!(fieldType instanceof GraphQLObjectType fieldObj)) continue;
            if (!fieldObj.hasAppliedDirective(DIR_TABLE)) continue;
            if (dataField != null) return;
            dataField = f;
            dataFieldTableName = argString(fieldObj, DIR_TABLE, ARG_NAME)
                .orElse(unwrappedFieldType.toLowerCase());
        }
        if (dataField == null) return;

        Optional<TableRef> tableOpt = svc.resolveTable(dataFieldTableName);
        if (tableOpt.isEmpty()) return;
        TableRef table = tableOpt.get();

        Class<?> recordClass;
        try {
            recordClass = Class.forName(
                table.recordClass().reflectionName(), false, ctx.codegenLoader());
        } catch (ClassNotFoundException ignored) {
            return;
        }

        Class<?> retElement = peelReturnElement(method.getGenericReturnType());
        if (retElement == null || !retElement.equals(recordClass)) return;

        SourceKey.Cardinality cardinality =
            GraphQLTypeUtil.unwrapNonNull(dataField.getType()) instanceof GraphQLList
                ? SourceKey.Cardinality.MANY
                : SourceKey.Cardinality.ONE;

        serviceEmittedMemo.putIfAbsent(resultSdl, new ProducerBinding.ServiceEmitted(
            retElement, table, cardinality, parent.getName(), field.getName(),
            className, methodName, loc));
    }

    private void groundTableMethodField(GraphQLObjectType parent, GraphQLFieldDefinition field) {
        if (!field.hasAppliedDirective(DIR_TABLE_METHOD)) return;
        // @tableMethod returns a jOOQ Table subclass; the SDL return type is @table-bound and the
        // binding for that SDL type comes from @table already. The Table subclass's reflection
        // record-type is the same class @table resolves to, but obtaining it from a bare-class
        // return requires querying jOOQ's TableImpl.recordType() at instantiation time — which
        // adds machinery without strengthening the diagnostic (the @table observation alone is
        // sufficient). The walker therefore reads @tableMethod input-arg bindings (mirror of the
        // @service input-arg arm; see groundServiceField's input loop) but does not contribute
        // a result-axis observation here.
        GraphQLAppliedDirective dir = field.getAppliedDirective(DIR_TABLE_METHOD);
        String className = argString(field, DIR_TABLE_METHOD, ARG_CLASS_NAME).orElse(null);
        String methodName = argString(field, DIR_TABLE_METHOD, ARG_METHOD).orElse(null);
        if (className == null || methodName == null) return;

        Method method = findUniqueMethod(className, methodName);
        if (method == null) return;
        SourceLocation loc = locationOf(field);

        // Ground input-axis bindings from method parameters → SDL arg types.
        String rawArgMapping = argString(field, DIR_TABLE_METHOD, BuildContext.ARG_ARG_MAPPING).orElse("");
        Map<String, String> overrides = parseArgMapping(rawArgMapping);
        for (var p : method.getParameters()) {
            if (!p.isNamePresent()) continue;
            String paramName = p.getName();
            String sdlArgName = overrides.getOrDefault(paramName, paramName);
            GraphQLArgument arg = field.getArgument(sdlArgName);
            if (arg == null) continue;
            String inputSdl = unwrappedTypeName(arg.getType());
            if (inputSdl == null) continue;
            Class<?> paramElement = peelReturnElement(p.getParameterizedType());
            if (paramElement == null || !shouldBind(paramElement)) continue;
            addInputObservation(inputSdl, new ProducerBinding.RootTableMethod(
                paramElement, parent.getName(), field.getName(), className, methodName, loc));
        }
    }

    /**
     * R178: grounds a {@link ProducerBinding.DmlEmitted} result-axis observation for the payload
     * SDL type of every DML {@code @mutation} field whose payload is a non-{@code @table} SDL
     * Object. Reads the {@code @mutation(typeName:)} arg to derive {@link DmlKind}, locates the
     * single {@code @table}-bearing input argument, resolves the table via the catalog, and
     * lifts the cardinality from the input arg's list shape (bulk-vs-single dispatch).
     *
     * <p>Skipped cases: missing or malformed {@code @mutation(typeName:)}, no {@code @table}
     * input argument, unresolvable table name, ID/scalar return types, {@code @table}-bound
     * returns (already grounded by {@link ProducerBinding.RootTable}), and unloadable record
     * classes. Each skip preserves the build's current handling for that shape.
     *
     * <p>Multi-argument {@code @table} mutations and missing-{@code @table} mutations fall
     * through silently here; the per-mutation diagnostics live in
     * {@link MutationInputResolver#resolveInput} and surface there.
     */
    private void groundDmlMutationField(GraphQLObjectType parent, GraphQLFieldDefinition field) {
        if (!field.hasAppliedDirective(DIR_MUTATION)) return;
        DmlKind kind = readDmlKind(field);
        if (kind == null) return;

        GraphQLArgument tableArg = findSingleTableInputArg(field);
        if (tableArg == null) return;

        GraphQLInputObjectType tableInput = (GraphQLInputObjectType)
            GraphQLTypeUtil.unwrapAll(tableArg.getType());
        String tableSqlName = argString(tableInput, DIR_TABLE, ARG_NAME)
            .orElse(tableInput.getName().toLowerCase());

        Optional<TableRef> tableOpt = svc.resolveTable(tableSqlName);
        if (tableOpt.isEmpty()) return;
        TableRef table = tableOpt.get();

        Class<?> recordClass;
        try {
            recordClass = Class.forName(
                table.recordClass().reflectionName(), false, ctx.codegenLoader());
        } catch (ClassNotFoundException ignored) {
            return;
        }

        String payloadSdl = unwrappedTypeName(field.getType());
        if (payloadSdl == null) return;
        GraphQLType payloadType = ctx.schema.getType(payloadSdl);
        if (!(payloadType instanceof GraphQLObjectType payloadObj)) return;
        // @table-bound payloads (mutate(...): Film, where Film carries @table) are already
        // grounded as RootTable; don't double-bind.
        if (payloadObj.hasAppliedDirective(DIR_TABLE)) return;

        SourceKey.Cardinality cardinality =
            GraphQLTypeUtil.unwrapNonNull(tableArg.getType()) instanceof GraphQLList
                ? SourceKey.Cardinality.MANY
                : SourceKey.Cardinality.ONE;

        // Phase 1 storage: dedicated map, isolated from the result-axis fold and the
        // recordBackingClasses pump. The cutover commit moves this into addResultObservation.
        // A payload SDL type reachable as the return of multiple DML mutations keeps the first
        // observation; the cutover-side multi-producer check fires from the standard fold once
        // the bindings move there.
        dmlEmittedMemo.putIfAbsent(payloadSdl, new ProducerBinding.DmlEmitted(
            recordClass, table, kind, cardinality, locationOf(field)));
    }

    private static DmlKind readDmlKind(GraphQLFieldDefinition field) {
        GraphQLAppliedDirective dir = field.getAppliedDirective(DIR_MUTATION);
        if (dir == null) return null;
        var arg = dir.getArgument(ARG_TYPE_NAME);
        if (arg == null) return null;
        Object value = arg.getValue();
        String raw = value instanceof EnumValue ev ? ev.getName()
            : value instanceof String s ? s
            : null;
        if (raw == null) return null;
        try {
            return DmlKind.valueOf(raw);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static GraphQLArgument findSingleTableInputArg(GraphQLFieldDefinition field) {
        GraphQLArgument found = null;
        for (var arg : field.getArguments()) {
            GraphQLType unwrapped = GraphQLTypeUtil.unwrapAll(arg.getType());
            if (unwrapped instanceof GraphQLInputObjectType iot
                    && iot.hasAppliedDirective(DIR_TABLE)) {
                if (found != null) return null;  // multi-table: defer to MutationInputResolver
                found = arg;
            }
        }
        return found;
    }

    // ===== Phase 2: parent-accessor propagation =====

    private void propagateAccessorChains() {
        // Iterate until no new bindings are produced. Each pass walks every SDL Object/Input
        // type with a currently-observed binding and propagates through its accessor edges.
        boolean changed = true;
        int safety = 0;
        while (changed) {
            if (safety++ > 1000) {
                throw new IllegalStateException("R96 walker did not converge in 1000 passes");
            }
            changed = false;
            // Snapshot the currently-folded bindings so we don't iterate over mutations.
            var resultSnapshot = new LinkedHashMap<>(resultMemo);
            for (var entry : resultSnapshot.entrySet()) {
                if (entry.getValue() == null) continue;
                changed |= propagateResultChildren(entry.getKey(), entry.getValue());
            }
            var inputSnapshot = new LinkedHashMap<>(inputMemo);
            for (var entry : inputSnapshot.entrySet()) {
                if (entry.getValue() == null) continue;
                changed |= propagateInputChildren(entry.getKey(), entry.getValue());
            }
            // Re-fold after each pass to catch new bindings.
            if (changed) foldAll();
        }
    }

    private boolean propagateResultChildren(String parentSdlType, Class<?> parentClass) {
        var named = ctx.schema.getType(parentSdlType);
        if (!(named instanceof GraphQLObjectType obj)) return false;
        boolean changed = false;
        for (GraphQLFieldDefinition field : obj.getFieldDefinitions()) {
            // Skip directive-driven fields: their return type is already bound via root producer.
            if (field.hasAppliedDirective(DIR_SERVICE)) continue;
            if (field.hasAppliedDirective(DIR_TABLE_METHOD)) continue;
            String childSdl = unwrappedTypeName(field.getType());
            if (childSdl == null) continue;
            // Find the accessor method/field on the parent class.
            Type accessorReturn = findAccessorReturnType(parentClass, field.getName());
            if (accessorReturn == null) continue;
            Class<?> childCls = peelReturnElement(accessorReturn);
            if (childCls == null || !shouldBind(childCls)) continue;
            ProducerBinding pb = new ProducerBinding.ParentAccessor(
                childCls, parentSdlType, parentClass.getName(),
                field.getName(), inferAccessorName(parentClass, field.getName()),
                locationOf(field));
            if (addResultObservation(childSdl, pb)) {
                changed = true;
            }
        }
        return changed;
    }

    private boolean propagateInputChildren(String parentSdlType, Class<?> parentClass) {
        var named = ctx.schema.getType(parentSdlType);
        if (!(named instanceof GraphQLInputObjectType obj)) return false;
        boolean changed = false;
        for (GraphQLInputObjectField field : obj.getFieldDefinitions()) {
            String childSdl = unwrappedTypeName(field.getType());
            if (childSdl == null) continue;
            Type accessorReturn = findAccessorReturnType(parentClass, field.getName());
            if (accessorReturn == null) continue;
            Class<?> childCls = peelReturnElement(accessorReturn);
            if (childCls == null || !shouldBind(childCls)) continue;
            ProducerBinding pb = new ProducerBinding.ParentAccessor(
                childCls, parentSdlType, parentClass.getName(),
                field.getName(), inferAccessorName(parentClass, field.getName()),
                locationOf(field));
            if (addInputObservation(childSdl, pb)) {
                changed = true;
            }
        }
        return changed;
    }

    // ===== Phase 3: fold per-type collection sets =====

    private void foldAll() {
        for (var entry : resultObserved.entrySet()) {
            fold(entry.getKey(), entry.getValue(), resultMemo);
        }
        for (var entry : inputObserved.entrySet()) {
            fold(entry.getKey(), entry.getValue(), inputMemo);
        }
    }

    private void fold(String sdlType, List<ProducerBinding> observed, Map<String, Class<?>> memo) {
        if (observed.isEmpty()) {
            memo.put(sdlType, null);
            return;
        }
        // Distinct classes by identity.
        var distinct = new LinkedHashSet<Class<?>>();
        for (var b : observed) distinct.add(b.reflectedClass());
        if (distinct.size() == 1) {
            memo.put(sdlType, distinct.iterator().next());
            return;
        }
        // Disagreement: record a typed rejection. The first observed binding's location is used
        // for the rejection's surfacing site; the full list is in the typed payload.
        rejections.computeIfAbsent(sdlType, k ->
            new Rejection.AuthorError.RecordBindingMultiProducer(sdlType, observed));
        memo.put(sdlType, null);
    }

    // ===== Observation bookkeeping =====

    private boolean addResultObservation(String sdlType, ProducerBinding binding) {
        return addObservation(sdlType, binding, resultObserved);
    }

    private boolean addInputObservation(String sdlType, ProducerBinding binding) {
        return addObservation(sdlType, binding, inputObserved);
    }

    private boolean addObservation(String sdlType, ProducerBinding binding,
                                   Map<String, List<ProducerBinding>> store) {
        reachable.add(sdlType);
        List<ProducerBinding> list = store.computeIfAbsent(sdlType, k -> new ArrayList<>());
        // Deduplicate by (reflectedClass identity + describe()) so the same (parent, field)
        // path doesn't double-count across propagation passes.
        for (var existing : list) {
            if (existing.reflectedClass() == binding.reflectedClass()
                    && existing.describe().equals(binding.describe())) {
                return false;
            }
        }
        list.add(binding);
        return true;
    }

    // ===== Reflection helpers =====

    private Method findUniqueMethod(String className, String methodName) {
        try {
            Class<?> cls = Class.forName(className, false, ctx.codegenLoader());
            Method found = null;
            for (Method m : cls.getDeclaredMethods()) {
                if (!m.getName().equals(methodName)) continue;
                if (found != null) {
                    // Overloaded: ambiguous; existing reflection takes the first match per
                    // ServiceCatalog.reflectServiceMethod's `methods.get(0)`. Mirror that.
                    return found;
                }
                found = m;
            }
            return found;
        } catch (ClassNotFoundException e) {
            return null;
        }
    }

    /**
     * Peels common container types (Single, List, Set, Optional, CompletableFuture, Page, Result)
     * to find the inner element class. Returns null when the type is not a recognised single-
     * argument container or the element is not a bare Class.
     */
    private static Class<?> peelReturnElement(Type t) {
        Type current = t;
        // Peel through one level of single-arg parametric containers.
        for (int i = 0; i < 4; i++) {
            if (current instanceof Class<?> cls) return cls;
            if (!(current instanceof ParameterizedType pt)) return null;
            if (!(pt.getRawType() instanceof Class<?> raw)) return null;
            Type[] args = pt.getActualTypeArguments();
            // Recognise common single-arg containers.
            boolean unwrap =
                java.util.List.class.isAssignableFrom(raw)
                || java.util.Set.class.isAssignableFrom(raw)
                || java.util.Collection.class.isAssignableFrom(raw)
                || java.util.Optional.class.isAssignableFrom(raw)
                || java.util.concurrent.CompletableFuture.class.isAssignableFrom(raw)
                || org.jooq.Result.class.isAssignableFrom(raw);
            if (!unwrap) {
                // Not a known container: treat the parameterised raw class itself as the element.
                return raw;
            }
            if (args.length != 1) return raw;
            current = args[0];
        }
        return current instanceof Class<?> c ? c : null;
    }

    /**
     * Walks the parent class's public methods and public fields looking for an accessor matching
     * {@code fieldName}. Tries bare name, {@code getX}, then {@code isX}; falls back to a public
     * field read. Zero-arg / {@code DataFetchingEnvironment}-arg accessors are accepted.
     */
    private static Type findAccessorReturnType(Class<?> parentClass, String fieldName) {
        if (fieldName.isEmpty()) return null;
        String camel = fieldName;
        String capitalised = Character.toUpperCase(camel.charAt(0)) + camel.substring(1);
        String[] candidates = {camel, "get" + capitalised, "is" + capitalised};
        for (String name : candidates) {
            for (Method m : parentClass.getMethods()) {
                if (Modifier.isStatic(m.getModifiers())) continue;
                if (!m.getName().equals(name)) continue;
                if (m.getParameterCount() == 0) {
                    return m.getGenericReturnType();
                }
                // Single DataFetchingEnvironment param: accept for getter-style env-aware methods.
                if (m.getParameterCount() == 1
                        && "graphql.schema.DataFetchingEnvironment".equals(
                            m.getParameterTypes()[0].getName())) {
                    return m.getGenericReturnType();
                }
            }
        }
        try {
            var f = parentClass.getField(camel);
            if (!Modifier.isStatic(f.getModifiers())) {
                return f.getGenericType();
            }
        } catch (NoSuchFieldException ignored) {}
        return null;
    }

    private static String inferAccessorName(Class<?> parentClass, String fieldName) {
        if (fieldName.isEmpty()) return fieldName;
        String capitalised = Character.toUpperCase(fieldName.charAt(0)) + fieldName.substring(1);
        String[] candidates = {fieldName, "get" + capitalised, "is" + capitalised};
        for (String name : candidates) {
            for (Method m : parentClass.getMethods()) {
                if (!Modifier.isStatic(m.getModifiers()) && m.getName().equals(name)) {
                    return name;
                }
            }
        }
        return fieldName;
    }

    /**
     * Returns true when {@code cls} represents a meaningful binding (not a primitive, not
     * {@link Object}, not a scalar wrapper). Filters out primitives and a few uninteresting
     * leaf types so accessor chains don't propagate spurious bindings.
     */
    private static boolean shouldBind(Class<?> cls) {
        if (cls == null) return false;
        if (cls.isPrimitive()) return false;
        if (cls == Object.class) return false;
        if (cls == String.class) return false;
        if (cls == Boolean.class || cls == Character.class) return false;
        if (Number.class.isAssignableFrom(cls)) return false;
        if (cls.isArray()) return false;
        if (cls.isEnum()) return false;
        if (cls.getPackage() != null && cls.getPackage().getName().startsWith("java.")) return false;
        return true;
    }

    /** Strips {@code !} and {@code [...]} wrappers, returning the named SDL type. */
    private static String unwrappedTypeName(GraphQLType t) {
        GraphQLType inner = GraphQLTypeUtil.unwrapAll(t);
        return inner instanceof GraphQLNamedType nt ? nt.getName() : null;
    }

    /**
     * Parses an {@code argMapping} string ({@code "javaParam: graphqlArg, ..."}) into a map of
     * Java-parameter name → GraphQL-argument name overrides. Empty string and unparseable entries
     * fall through silently — the typed override path runs through
     * {@link ArgBindingMap#of} elsewhere; this helper only needs the override map for the walker's
     * input-binding probe and tolerates the same forms.
     */
    private static Map<String, String> parseArgMapping(String raw) {
        if (raw == null || raw.isBlank()) return Map.of();
        Map<String, String> overrides = new LinkedHashMap<>();
        for (String entry : raw.split(",")) {
            int colon = entry.indexOf(':');
            if (colon < 0) continue;
            String javaName = entry.substring(0, colon).strip();
            String tail = entry.substring(colon + 1).strip();
            // Drop any dot-path suffix; the walker only cares about the top-level arg name.
            int dot = tail.indexOf('.');
            String argName = dot < 0 ? tail : tail.substring(0, dot);
            if (!javaName.isEmpty() && !argName.isEmpty()) {
                overrides.put(javaName, argName);
            }
        }
        return overrides;
    }
}
