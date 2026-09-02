package no.sikt.graphitron.rewrite;

import graphql.language.EnumValue;
import graphql.language.SourceLocation;
import graphql.schema.GraphQLAppliedDirective;
import graphql.schema.GraphQLArgument;
import graphql.schema.GraphQLDirectiveContainer;
import graphql.schema.GraphQLFieldDefinition;
import graphql.schema.GraphQLInputObjectField;
import graphql.schema.GraphQLInputObjectType;
import graphql.schema.GraphQLInputType;
import graphql.schema.GraphQLList;
import graphql.schema.GraphQLNamedType;
import graphql.schema.GraphQLNonNull;
import graphql.schema.GraphQLObjectType;
import graphql.schema.GraphQLType;
import graphql.schema.GraphQLTypeUtil;
import no.sikt.graphitron.render.CatalogRefs;
import no.sikt.graphitron.rewrite.model.AccessorProbe;
import no.sikt.graphitron.model.diagnostics.Arity;
import no.sikt.graphitron.rewrite.model.DmlKind;
import no.sikt.graphitron.rewrite.model.ProducerBinding;
import no.sikt.graphitron.model.diagnostics.Rejection;
import no.sikt.graphitron.model.jooq.TableRef;

import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import static no.sikt.graphitron.rewrite.BuildContext.ARG_CLASS_NAME;
import static no.sikt.graphitron.rewrite.BuildContext.ARG_METHOD;
import static no.sikt.graphitron.rewrite.BuildContext.ARG_NAME;
import static no.sikt.graphitron.rewrite.BuildContext.ARG_SERVICE_REF;
import static no.sikt.graphitron.rewrite.BuildContext.ARG_TYPE_NAME;
import static no.sikt.graphitron.rewrite.BuildContext.ARG_EXTERNAL_FIELD_REF;
import static no.sikt.graphitron.rewrite.BuildContext.DIR_EXTERNAL_FIELD;
import static no.sikt.graphitron.rewrite.BuildContext.DIR_FIELD;
import static no.sikt.graphitron.rewrite.BuildContext.DIR_MUTATION;
import static no.sikt.graphitron.rewrite.BuildContext.DIR_NODE_ID;
import static no.sikt.graphitron.rewrite.BuildContext.DIR_REFERENCE;
import static no.sikt.graphitron.rewrite.BuildContext.DIR_ROUTINE;
import static no.sikt.graphitron.rewrite.BuildContext.DIR_SERVICE;
import static no.sikt.graphitron.rewrite.BuildContext.DIR_TABLE;
import static no.sikt.graphitron.rewrite.BuildContext.argString;
import static no.sikt.graphitron.rewrite.BuildContext.asMap;
import static no.sikt.graphitron.rewrite.BuildContext.locationOf;
import no.sikt.graphitron.model.grammar.ArgMappingSigil;
import no.sikt.graphitron.model.jooq.JooqCatalog;

/**
 * Derives SDL → backing-class bindings from reflection alone. The walk grounds at root producers
 * ({@code @service} method returns, {@code @table} resolutions) and
 * extends through parent-accessor return types. Bindings accumulate into a per-SDL-type collection
 * set on each axis (result + input); after the walk, each per-type set folds into a single agreed
 * {@link Class}, an empty resolution, or a
 * {@link Rejection.AuthorError.RecordBindingMultiProducer} diagnostic.
 *
 * <p>The {@code @record} directive is read only to surface a directive-ignored warning; it does
 * not contribute to the binding.
 *
 * <p>The per-SDL-type fold is the producer-side rejection point for backing-class disagreement.
 * The consumer is {@link FieldBuilder} (via {@code resolveRecordAccessor}), which assumes the
 * resolved {@code Class} is the class field accessors are emitted against.
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

    /**
     * DML mutation payload bindings, observed for the payload SDL type of every DML
     * {@code @mutation} field whose payload is a non-{@code @table} SDL Object. A dedicated axis
     * (not result/input) so the record-binding fold and the {@code recordBackingClasses} pump in
     * {@link TypeBuilder#prepareForWalk()} ignore it, keeping the DML-emitted shape isolated from
     * reflection-derived class bindings.
     */
    private final Map<String, ProducerBinding.DmlEmitted> dmlEmittedMemo = new LinkedHashMap<>();

    /**
     * Dedicated map for {@link ProducerBinding.ServiceEmitted} observations from
     * {@code @service} mutation fields with payload-returning shapes. Mirrors
     * {@link #dmlEmittedMemo}.
     */
    private final Map<String, ProducerBinding.ServiceEmitted> serviceEmittedMemo = new LinkedHashMap<>();

    /**
     * Dedicated map for {@link ProducerBinding.RoutineEmitted} observations from hop-less
     * {@code @routine} Mutation fields with carrier-shaped payloads. Mirrors
     * {@link #dmlEmittedMemo}.
     */
    private final Map<String, ProducerBinding.RoutineEmitted> routineEmittedMemo = new LinkedHashMap<>();

    /**
     * The table a type is bound to by being what a hop-less {@code @routine} read field returns:
     * the routine's own result. A separate axis from {@link #routineEmittedMemo}, which is the
     * carrier's binding and answers a different question (which table the payload's data field
     * re-reads post-commit); the two populations are disjoint by seat.
     *
     * <p>First observation wins, as on the emitted memos. A type two routines return is a
     * disagreement the observations cannot settle, and settling it by picking would hide it; the
     * fact store states the same population with one row per landing and an arity beside them, so
     * that is where the disagreement is legible until a verdict relation carries it here.
     */
    private final Map<String, TableRef> routineReturnMemo = new LinkedHashMap<>();

    /**
     * The {@code @service} producer's arrival cardinality per carrier field, keyed by the field's
     * {@code parentType.fieldName} coordinate, decided once at this reflection boundary (from
     * {@link #isMultiCardinalityReturn}) and read by the classify-time shape verdict. Keyed by the
     * field coordinate rather than the payload SDL type because two {@code @service} fields may
     * return the same payload type with different producer arrivals. A separate axis from
     * {@link ProducerBinding.ServiceEmitted#arrival()} (the payload's <em>data-field</em> arrival),
     * so the two same-typed arrivals are never conflated on one accessor.
     */
    private final Map<String, Arity> serviceCarrierProducerArrivalMemo = new LinkedHashMap<>();

    /**
     * Reason ledger: the gated accessor near-miss (if any) the walk hit while trying to ground a
     * child SDL type through a parent accessor. Keyed by the child SDL type; first gated near-miss
     * wins. Read by the classifier ({@link TypeBuilder}) only when the child type ends the walk
     * with no producer, so the failure names the accessor gate.
     */
    private final Map<String, AccessorGateReason> accessorGateReasons = new LinkedHashMap<>();

    /**
     * One gated accessor near-miss: the parent SDL type + field whose accessor almost grounded the
     * child, the human-readable gate reason, and the field's source location for diagnostic placement.
     */
    record AccessorGateReason(String parentSdlType, String fieldName, String reason, SourceLocation location) {}

    RecordBindingResolver(BuildContext ctx, ServiceCatalog svc) {
        this.ctx = Objects.requireNonNull(ctx);
        this.svc = Objects.requireNonNull(svc);
    }

    /**
     * Grounds at every root producer, propagates through parent-accessor chains to a fixed point,
     * then folds each per-type collection set into a single agreed binding (or a multi-producer
     * rejection).
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

    /**
     * The first observed result-axis {@link ProducerBinding} for an SDL type, or empty when none. Used
     * to name the producer that bound a type class-backed when a mixed-source nesting edge fails
     * per-child column resolution, so the author who meant "return the produced value" gets intent-level
     * guidance ({@link ProducerBinding#describe()}).
     */
    Optional<ProducerBinding> resultProducer(String sdlTypeName) {
        var observed = resultObserved.get(sdlTypeName);
        return observed == null || observed.isEmpty() ? Optional.empty() : Optional.of(observed.get(0));
    }

    /** Returns the resolved input-axis binding for an SDL type, or empty when none. */
    Optional<Class<?>> resolveInput(String sdlTypeName) {
        return Optional.ofNullable(inputMemo.get(sdlTypeName));
    }

    /**
     * DML mutation payload binding for an SDL type. Carries the inner {@link TableRef}
     * the DML producer emits rows for, the {@link DmlKind}, and the producer-side cardinality
     * lifted from the input {@code @table} arg. Held on a dedicated axis so the existing fold
     * and the {@link TypeBuilder#prepareForWalk()} {@code recordBackingClasses} pump don't see it.
     */
    Optional<ProducerBinding.DmlEmitted> resolveDmlEmitted(String sdlTypeName) {
        return Optional.ofNullable(dmlEmittedMemo.get(sdlTypeName));
    }

    /**
     * Resolves the optional {@link ProducerBinding.ServiceEmitted} observation
     * for an SDL payload type whose producer is an {@code @service} mutation field with a
     * carrier-shaped payload. Mirrors {@link #resolveDmlEmitted}.
     */
    Optional<ProducerBinding.ServiceEmitted> resolveServiceEmitted(String sdlTypeName) {
        return Optional.ofNullable(serviceEmittedMemo.get(sdlTypeName));
    }

    /**
     * Resolves the optional {@link ProducerBinding.RoutineEmitted} observation for an SDL
     * payload type whose producer is a hop-less {@code @routine} Mutation field with a
     * carrier-shaped payload. Mirrors {@link #resolveDmlEmitted}.
     */
    Optional<ProducerBinding.RoutineEmitted> resolveRoutineEmitted(String sdlTypeName) {
        return Optional.ofNullable(routineEmittedMemo.get(sdlTypeName));
    }

    /**
     * The table an SDL type is bound to by being a hop-less {@code @routine} read field's return,
     * or empty when no such field returns it. See {@link #groundRoutineReturnType} for the
     * population.
     */
    Optional<TableRef> resolveRoutineReturn(String sdlTypeName) {
        return Optional.ofNullable(routineReturnMemo.get(sdlTypeName));
    }

    /**
     * The {@code @service} producer's arrival cardinality for a carrier field, keyed by its
     * {@code parentType.fieldName} coordinate (see {@link #carrierFieldKey}), or empty when the
     * coordinate is not an {@code @service} field. Consumed by the classify-time shape verdict at
     * the {@code @service} carrier seat ({@code FieldBuilder.scanServiceCarrierShape}).
     */
    Optional<Arity> resolveServiceCarrierProducerArrival(String parentType, String fieldName) {
        return Optional.ofNullable(serviceCarrierProducerArrivalMemo.get(carrierFieldKey(parentType, fieldName)));
    }

    /** The {@code parentType.fieldName} key under which producer arrival is memoised. */
    private static String carrierFieldKey(String parentType, String fieldName) {
        return parentType + "." + fieldName;
    }

    /** Multi-producer rejection for the SDL type, or empty when none. */
    Optional<Rejection.AuthorError.RecordBindingMultiProducer> rejection(String sdlTypeName) {
        return Optional.ofNullable(rejections.get(sdlTypeName));
    }

    /**
     * The gated accessor near-miss recorded for an SDL type the walk could not ground through a
     * parent accessor (a member name-matched but failed a walk tightening's gate). Empty when the
     * walk hit no gated near-miss for the type. Consulted only for a type that ends the walk with
     * no producer, so the accessor gate is named instead of a generic no-producer cascade.
     */
    Optional<AccessorGateReason> accessorGateReason(String sdlTypeName) {
        return Optional.ofNullable(accessorGateReasons.get(sdlTypeName));
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
                        // nameability: exempt (jOOQ catalog record class)
                        Class<?> recordClass = Class.forName(
                            CatalogRefs.recordClass(table).reflectionName(), false, ctx.codegenLoader());
                        addResultObservation(obj.getName(), new ProducerBinding.RootTable(
                            recordClass, obj.getName(), tableSqlName, locationOf(obj)));
                    } catch (ClassNotFoundException ignored) {
                        // jOOQ catalog class missing: nothing to bind.
                    }
                });
            }
            // No input-axis sibling arm: @table on an INPUT_OBJECT is deprecated and inert, so it
            // grounds nothing. Registering an input observation off it would honor the directive
            // rather than ignore it (the declared table's record class would decide the input's
            // backing carrier), and a second observation from a @service param would fold into a
            // RecordBindingMultiProducer rejection. An input's backing class comes from its
            // consumers alone.
        });

        // @service and @externalField (ComputedField) on field definitions. The DML @mutation
        // grounding is not here: its carrier scan reads the ErrorIndex, so it runs from
        // groundIndexDependentBindings once the classification indices exist.
        ctx.schema.getAllTypesAsList().forEach(named -> {
            if (!(named instanceof GraphQLObjectType obj)) return;
            if (named.getName().startsWith("__")) return;
            for (GraphQLFieldDefinition field : obj.getFieldDefinitions()) {
                groundServiceField(obj, field);
                groundComputedField(obj, field);
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

        // Ground the result-axis binding from the method's reflected return-element type; the
        // @service signature is the single source of truth for the SDL return type's backing
        // class. The shared grounding rules live in groundProducerResult.
        Class<?> retElement = peelReturnElement(method.getGenericReturnType());
        boolean producerIsMulti = isMultiCardinalityReturn(method.getGenericReturnType());
        groundProducerResult(field, retElement, producerIsMulti,
            () -> new ProducerBinding.RootService(
                retElement, parent.getName(), field.getName(), className, methodName, loc));

        String resultSdl = unwrappedTypeName(field.getType());

        // Producer arrival, decided once here at the reflection boundary and carried as a typed
        // fact; the classify-time shape verdict (FieldBuilder.scanServiceCarrierShape) reads this
        // rather than re-deriving producer multi-ness from MethodRef.returnType(), which would
        // have to replicate every container peel. Coordinate keying rationale is on
        // serviceCarrierProducerArrivalMemo.
        serviceCarrierProducerArrivalMemo.put(carrierFieldKey(parent.getName(), field.getName()),
            producerIsMulti ? Arity.MANY : Arity.ONE);

        // ServiceEmitted observation for @service-carrier candidates (structural check; see
        // groundServicePayloadBinding). Independent of the main result-axis fold and the
        // RootService observation above; FieldBuilder.classifyChildFieldOnResultType reads it
        // through TypeBuilder.serviceEmittedBinding.
        groundServicePayloadBinding(parent, field, method, className, methodName, resultSdl, loc);

        // Ground input-axis bindings from method parameters → SDL arg types.
        // Argument mapping: parameter name = SDL arg name unless argMapping overrides.
        Map<String, String> argMappingOverrides = headSlotOverrides(
            Optional.ofNullable(ref.get(BuildContext.ARG_ARGMAPPING)).map(Object::toString).orElse(""));
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
     * Shared result-axis grounding for a reflected producer field. Grounds the SDL return type's
     * backing from the producer's reflected return element, under the rules every producer shares:
     *
     * <ul>
     *   <li><b>@table-backed-SDL guard:</b> a producer whose SDL return type is itself
     *       {@code @table}-bound takes its backing from that {@code @table} (the {@link
     *       ProducerBinding.RootTable} observation); grounding here would be a spurious second
     *       producer and collide in the fold. Result-axis grounding is only for payload types that
     *       take their backing from the producer.</li>
     *   <li><b>Cardinality-match guard:</b> bind only when the SDL field and the reflected return
     *       agree on cardinality. A single-object SDL field produced by a collection return is a
     *       list carrier whose collection feeds an inner list field, not the wrapper, so the
     *       wrapper does not bind.</li>
     *   <li><b>{@link #shouldBind}:</b> only bindable element classes ground.</li>
     * </ul>
     *
     * <p>The producer-specific part (which method, how its return element is reflected) is the
     * caller's; the return rules live here so {@code @service} and {@code @externalField} /
     * {@code ComputedField} share one definition. {@code binding} is a supplier so it is built only
     * when grounding actually proceeds.
     */
    private void groundProducerResult(GraphQLFieldDefinition field, Class<?> reflectedElement,
            boolean reflectedIsMulti, java.util.function.Supplier<ProducerBinding> binding) {
        if (reflectedElement == null || !shouldBind(reflectedElement)) return;
        switch (producerBindLevel(field, reflectedElement, reflectedIsMulti)) {
            case ProducerBindLevel.BindsWrapper ignored ->
                addResultObservation(unwrappedTypeName(field.getType()), binding.get());
            case ProducerBindLevel.BindsDataFieldElement b -> {
                String elementSdl = unwrappedTypeName(b.dataField().getType());
                if (elementSdl != null) addResultObservation(elementSdl, binding.get());
            }
            case ProducerBindLevel.NoBind ignored -> { }
        }
    }

    /**
     * Which SDL level a reflected producer return binds to, computed once and projected by the
     * result-axis observation. The cardinality decision lives here as one verdict rather than as
     * two complementary comparisons that could desynchronise into a dangle or a double-bind.
     *
     * <ul>
     *   <li>{@link BindsWrapper}: the producer's reflected return element backs the SDL return type
     *       itself, when their cardinalities agree. The single-level case: a direct producer return
     *       ({@code films: [Film]} ← {@code List<FilmRecord>}) or a {@code @table}-data-field carrier
     *       payload ({@code FilmPayload} ← {@code FilmRecord}, where {@code film} keeps its
     *       {@code @table} / {@code ServiceEmitted} grounding).</li>
     *   <li>{@link BindsDataFieldElement}: the two-level record-composite carrier. The payload has a
     *       single non-{@code @table} object data field whose element type the reflected return feeds,
     *       and the reflected element is <em>not</em> a property of the wrapper's reflected class (no
     *       accessor for the data field name). The element binds the data field's element SDL type
     *       (the intermediate result type), not the wrapper. The binding is grounded regardless of
     *       cardinality agreement; a single-vs-list mismatch between the data field and the producer
     *       return is named precisely at the producing {@code @service} field
     *       ({@code FieldBuilder.checkServiceReturnMatchesPayload}), not silently dropped here.</li>
     *   <li>{@link NoBind}: the SDL return is {@code @table}-bound (keeps its {@code RootTable}
     *       grounding), or the wrapper cardinality does not match and the payload is not a
     *       record-composite carrier.</li>
     * </ul>
     */
    private sealed interface ProducerBindLevel
        permits ProducerBindLevel.BindsWrapper, ProducerBindLevel.BindsDataFieldElement,
                ProducerBindLevel.NoBind {
        record BindsWrapper() implements ProducerBindLevel {}
        record BindsDataFieldElement(GraphQLFieldDefinition dataField) implements ProducerBindLevel {}
        record NoBind() implements ProducerBindLevel {}
    }

    private ProducerBindLevel producerBindLevel(GraphQLFieldDefinition field,
            Class<?> reflectedElement, boolean reflectedIsMulti) {
        String resultSdl = unwrappedTypeName(field.getType());
        if (resultSdl == null || isTableBackedSdlType(resultSdl)) return new ProducerBindLevel.NoBind();
        // Two-level carrier: a payload with a single non-@table object data field whose element
        // the reflected return feeds. Distinguished from a plain result wrapper (whose data field IS
        // a property of the reflected class) by the absence of an accessor for the data-field name on
        // the reflected element; distinguished from the @table-data-field carrier by the data field
        // being non-@table (the @table case is skipped in singleNonTableObjectDataField and keeps its
        // BindsWrapper / ServiceEmitted grounding).
        GraphQLFieldDefinition dataField = singleNonTableObjectDataField(resultSdl);
        if (dataField != null && ClassAccessorResolver.probe(reflectedElement,
                accessorBaseName(dataField.getName(), dataField), paramShapeFor(dataField),
                ClassAccessorResolver.forBackingClass(reflectedElement))
                    instanceof AccessorProbe.NoMatch) {
            // The reflected element feeds the data field, not the wrapper. Bind regardless of
            // cardinality agreement; a single-vs-list near-miss is named at the producing @service
            // field (FieldBuilder.checkServiceReturnMatchesPayload) rather than left as a generic
            // dangling-type-reference failure that hides the cardinality cause.
            return new ProducerBindLevel.BindsDataFieldElement(dataField);
        }
        boolean sdlIsList = GraphQLTypeUtil.unwrapNonNull(field.getType()) instanceof GraphQLList;
        return sdlIsList == reflectedIsMulti
            ? new ProducerBindLevel.BindsWrapper()
            : new ProducerBindLevel.NoBind();
    }

    /**
     * The single data field of a candidate two-level carrier payload: a payload SDL field whose
     * unwrapped type is a GraphQL Object that does not carry {@code @table}. Returns null when
     * there is not exactly one such field. Errors-shaped fields (a union of / interface implemented
     * by {@code @error} types) are excluded by the {@link GraphQLObjectType} check, mirroring
     * {@link #groundServicePayloadBinding}; this runs during the binding phase, before the error
     * index exists, so it reads {@code ctx.schema} directly. The {@code @table}-typed object data
     * field (the single-level carrier) is skipped so that shape keeps its {@code BindsWrapper} +
     * {@code ServiceEmitted} grounding.
     */
    private GraphQLFieldDefinition singleNonTableObjectDataField(String payloadSdl) {
        if (!(ctx.schema.getType(payloadSdl) instanceof GraphQLObjectType payloadObj)) return null;
        GraphQLFieldDefinition found = null;
        for (var f : payloadObj.getFieldDefinitions()) {
            String unwrapped = unwrappedTypeName(f.getType());
            if (unwrapped == null) continue;
            if (!(ctx.schema.getType(unwrapped) instanceof GraphQLObjectType fieldObj)) continue;
            if (fieldObj.hasAppliedDirective(DIR_TABLE)) continue;
            if (found != null) return null;
            found = f;
        }
        return found;
    }

    /**
     * Grounds the result-axis binding for an {@code @externalField} field (model field
     * {@link no.sikt.graphitron.rewrite.model.ChildField.ComputedField}). Legal only on a child
     * field of a {@code @table}-typed parent: its developer-supplied static method takes that
     * parent's jOOQ {@code Table<?>} and returns {@code org.jooq.Field<X>}, where {@code X} is the
     * backing class for the SDL return type. The return type is not its own producer variant: it
     * grounds the same {@link ProducerBinding.RootService} observation under the same return rules
     * as a {@code @service} field ({@link #groundProducerResult}), so a carrier reached only
     * through {@code @externalField} binds and classifies exactly as a {@code @service}-produced
     * payload. Full method-shape validation stays with {@code ExternalFieldDirectiveResolver};
     * only {@code X} is needed here.
     */
    private void groundComputedField(GraphQLObjectType parent, GraphQLFieldDefinition field) {
        if (!field.hasAppliedDirective(DIR_EXTERNAL_FIELD)) return;
        GraphQLAppliedDirective dir = field.getAppliedDirective(DIR_EXTERNAL_FIELD);
        var refArg = dir.getArgument(ARG_EXTERNAL_FIELD_REF);
        if (refArg == null || refArg.getValue() == null) return;
        Map<String, Object> ref = asMap(refArg.getValue());
        String className = Optional.ofNullable(ref.get(ARG_CLASS_NAME)).map(Object::toString).orElse(null);
        if (className == null) return;
        // method: defaults to the GraphQL field name when omitted, mirroring ExternalFieldDirectiveResolver.
        String methodName = Optional.ofNullable(ref.get(ARG_METHOD)).map(Object::toString).orElse(field.getName());
        Method method = findUniqueMethod(className, methodName);
        if (method == null) return;
        Class<?> element = jooqFieldElement(method.getGenericReturnType());
        SourceLocation loc = locationOf(field);
        groundProducerResult(field, element, false,
            () -> new ProducerBinding.RootService(
                element, parent.getName(), field.getName(), className, methodName, loc));
    }

    /**
     * Extracts {@code X} from a {@code org.jooq.Field<X>} reflected return type, or {@code null}
     * when the return is not a parameterised {@code Field<X>} with a {@code Class} element (the
     * stricter {@code @externalField} return-shape validation lives in
     * {@code ServiceCatalog.reflectExternalField}).
     */
    private static Class<?> jooqFieldElement(java.lang.reflect.Type genericReturn) {
        if (genericReturn instanceof java.lang.reflect.ParameterizedType pt
                && pt.getRawType() == org.jooq.Field.class
                && pt.getActualTypeArguments().length == 1
                && pt.getActualTypeArguments()[0] instanceof Class<?> x) {
            return x;
        }
        return null;
    }

    /**
     * Structural detection for an {@code @service}-carrier candidate. Grounds a
     * {@link ProducerBinding.ServiceEmitted} observation when the payload SDL Object exposes
     * exactly one {@code @table}-typed data field whose record class equals the
     * {@code @service} method's reflected return-element class. Skipped silently for shapes
     * the carrier mold doesn't admit (multiple {@code @table}-typed fields, no
     * {@code @table}-typed field, unresolvable inner table, class-load failure, type mismatch
     * between method return and the data field's record class).
     *
     * <p>The binding walk runs before per-type classification populates {@code ctx.types}, so the detection
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
        // ID-element carrier. With no @table-typed data field, recognize exactly one ID-scalar
        // field carrying @nodeId(typeName: T); the SDL-side table comes from T's own @table
        // directive. The binding walk runs before per-type classification, so T's @node-ness
        // cannot be checked here; the classifier's encoder resolution closes that loop (and
        // re-asserts the table match against this binding's tableRef) at FieldBuilder's
        // serviceEmitted ID branch.
        if (dataField == null) {
            for (var f : payloadObj.getFieldDefinitions()) {
                String unwrappedFieldType = unwrappedTypeName(f.getType());
                if (!"ID".equals(unwrappedFieldType)) continue;
                if (!f.hasAppliedDirective(DIR_NODE_ID)) continue;
                Optional<String> nodeTypeName = argString(f, DIR_NODE_ID, ARG_TYPE_NAME);
                if (nodeTypeName.isEmpty()) continue;
                if (!(ctx.schema.getType(nodeTypeName.get()) instanceof GraphQLObjectType nodeObj)) continue;
                if (!nodeObj.hasAppliedDirective(DIR_TABLE)) continue;
                if (dataField != null) return;
                dataField = f;
                dataFieldTableName = argString(nodeObj, DIR_TABLE, ARG_NAME)
                    .orElse(nodeTypeName.get().toLowerCase());
            }
        }
        if (dataField == null) return;

        Optional<TableRef> tableOpt = svc.resolveTable(dataFieldTableName);
        if (tableOpt.isEmpty()) return;
        TableRef table = tableOpt.get();

        Class<?> recordClass;
        try {
            // nameability: exempt (jOOQ catalog record class)
            recordClass = Class.forName(
                CatalogRefs.recordClass(table).reflectionName(), false, ctx.codegenLoader());
        } catch (ClassNotFoundException ignored) {
            return;
        }

        Class<?> retElement = peelReturnElement(method.getGenericReturnType());
        if (retElement == null || !retElement.equals(recordClass)) return;

        Arity arrival =
            GraphQLTypeUtil.unwrapNonNull(dataField.getType()) instanceof GraphQLList
                ? Arity.MANY
                : Arity.ONE;

        serviceEmittedMemo.putIfAbsent(resultSdl, new ProducerBinding.ServiceEmitted(
            retElement, table, arrival, parent.getName(), field.getName(),
            className, methodName, loc));
    }

    /**
     * Grounds a {@link ProducerBinding.DmlEmitted} observation on its own dedicated axis (see
     * {@link #resolveDmlEmitted}) for the payload SDL type of every DML {@code @mutation} field
     * whose payload is a non-{@code @table} SDL Object. Reads the {@code @mutation(typeName:)} arg
     * to derive {@link DmlKind}, resolves the write-target table by the shared precedence
     * {@link MutationInputResolver#resolveDmlWriteTableRef} implements, and lifts the cardinality
     * from the field's single input-object argument's list shape (bulk-vs-single dispatch).
     *
     * <p>Called from {@link #groundIndexDependentBindings}, not from {@link #groundRootProducers}:
     * the write-target precedence reaches the structural payload scan, whose errors-field detection
     * needs the built {@link ErrorIndex}. That javadoc carries the reason.
     *
     * <p>Single-sourcing the precedence with the classify-time
     * {@code FieldBuilder.resolveDeleteWriteTarget} lets a payload-returning DELETE that names its
     * table on {@code @mutation(table:)} register as a producer-backed carrier and classify down
     * the {@code ResultReturnType} arm rather than rejecting for want of a binding. Two
     * independent precedence copies would ground a {@code DmlEmitted} on the wrong table whenever
     * the two rungs disagree.
     *
     * <p>Skipped cases: missing or malformed {@code @mutation(typeName:)}, no resolvable write
     * target, an unresolvable {@code @mutation(table:)} name, zero or multiple input-object
     * arguments, {@code @table}-bound returns (already grounded by
     * {@link ProducerBinding.RootTable}), and unloadable record classes. Each skip is silent: the
     * walk grounds observations, it does not diagnose. The per-mutation diagnostics live in the
     * classify-phase resolvers on {@link FieldBuilder} and surface there.
     */
    private void groundDmlMutationField(GraphQLObjectType parent, GraphQLFieldDefinition field) {
        if (!field.hasAppliedDirective(DIR_MUTATION)) return;
        DmlKind kind = readDmlKind(field);
        if (kind == null) return;

        // Write target by the shared precedence (see javadoc). An unresolvable @mutation(table:)
        // name or an absent source is a silent skip here; the loud rejection is the classifier's.
        TableRef table;
        switch (MutationInputResolver.resolveDmlWriteTableRef(field, kind, svc, ctx)) {
            case MutationInputResolver.WriteTableRef.Resolved r -> table = r.table();
            case MutationInputResolver.WriteTableRef.UnknownTable ignored -> { return; }
            case MutationInputResolver.WriteTableRef.None ignored -> { return; }
        }

        Class<?> recordClass;
        try {
            // nameability: exempt (jOOQ catalog record class)
            recordClass = Class.forName(
                CatalogRefs.recordClass(table).reflectionName(), false, ctx.codegenLoader());
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

        // Cardinality from the field's single input-object argument's list shape, whichever rung
        // resolved the write target above. Zero or multiple input-object arguments skip silently;
        // the classifier rejects any other shape independently.
        GraphQLArgument inputArg = singleInputObjectArg(field);
        if (inputArg == null) return;
        Arity arrival =
            GraphQLTypeUtil.unwrapNonNull(inputArg.getType()) instanceof GraphQLList
                ? Arity.MANY
                : Arity.ONE;

        // Dedicated map, isolated from the result-axis fold and the recordBackingClasses pump.
        // A payload SDL type reachable as the return of multiple DML mutations keeps the first
        // observation.
        dmlEmittedMemo.putIfAbsent(payloadSdl, new ProducerBinding.DmlEmitted(
            recordClass, table, kind, arrival, locationOf(field)));
    }

    /**
     * The grounding whose precondition is the classification indices, run by
     * {@link TypeBuilder#prepareForWalk()} <em>after</em> {@code buildClassificationIndices()}
     * rather than inside {@link #groundRootProducers}. The pass is named for that precondition and
     * not for the families in it, because the precondition is the single fact it asserts: every
     * grounder here reaches a structural carrier scan whose errors-field detection reads the
     * {@link ErrorIndex}. During the root-producer pass that index is still
     * {@code ErrorIndex.EMPTY}, so a carrier payload with an errors field mis-scans (the errors
     * field counted as a second data field) and silently fails to ground; the payload then earns
     * no carrier verdict and its field rejects at classify time with a message naming the return
     * type instead of the missing write target.
     *
     * <p>Two families, in two sequential loops rather than two calls per field: the order in which
     * {@code dmlEmittedMemo} and {@code routineEmittedMemo} become visible to
     * {@code TypeBuilder.carrierBinding} is observable for a payload reachable from both a
     * {@code @mutation} and a {@code @routine} field, and DML-then-routine is the total order the
     * per-field DML grounding had while it lived in {@link #groundRootProducers}. The routine loop
     * also carries {@link #groundRoutineReturnType}, which binds a routine read field's return
     * rather than a carrier: one more reason the pass cannot be named for a family set.
     *
     * <p>Safe to run late on both axes: {@code dmlEmittedMemo} and {@code routineEmittedMemo} are
     * dedicated maps that {@link #propagateAccessorChains} and {@link #foldAll} never read (see
     * {@link #resolveDmlEmitted}), and nothing between {@link #resolveAll} and this pass reads
     * them either.
     *
     * <p>{@link ProducerBinding.ServiceEmitted} stays in {@link #groundRootProducers}, deliberately.
     * Its carrier detection excludes errors-shaped fields structurally (the data field must be a
     * GraphQL Object), so it never reads the {@link ErrorIndex} and has no bug forcing the move;
     * and {@link #groundServiceField} also grounds result- and input-axis observations that must
     * feed the fold, so it could not move wholesale in any case. Do not "complete" the migration by
     * moving it.
     */
    void groundIndexDependentBindings() {
        ctx.schema.getAllTypesAsList().forEach(named -> {
            if (!(named instanceof GraphQLObjectType obj)) return;
            if (named.getName().startsWith("__")) return;
            for (GraphQLFieldDefinition field : obj.getFieldDefinitions()) {
                groundDmlMutationField(obj, field);
            }
        });
        ctx.schema.getAllTypesAsList().forEach(named -> {
            if (!(named instanceof GraphQLObjectType obj)) return;
            if (named.getName().startsWith("__")) return;
            for (GraphQLFieldDefinition field : obj.getFieldDefinitions()) {
                groundRoutineMutationField(obj, field);
                groundRoutineReturnType(obj, field);
            }
        });
    }

    /**
     * Grounds the return binding of a hop-less {@code @routine} read field: the type it returns is
     * bound to the routine's own result table, which is the fact an author states by hand when they
     * repeat the routine's name in a {@code @table} on that type. Nothing is grounded where the
     * author did write it, {@code @table} being read by the ordinary type classification, so this
     * fills the silence rather than competing with a directive.
     *
     * <p>The boundary is that the chain's last application is the {@code @routine}, so the landing
     * is the routine's own result: hops before it move where the chain starts and never where it
     * ends, so a hops-then-routine chain grounds here exactly as a hop-less one does. A chain that
     * hops <em>after</em> the routine lands somewhere only the chain walk knows, and this pass runs
     * before any field is walked, so it is left alone. That is also where the directive costs an
     * author nothing: such a chain's return type names a catalog table that is a table type in the
     * schema already, so its {@code @table} is that type's own binding rather than a second spelling
     * of the routine's name.
     *
     * <p>Skipped cases, each silent for the reason {@link #groundRoutineMutationField} states: a
     * return the author bound with {@code @table}, a non-object return, a chain landing past the
     * routine (the walk's), a second routine node (deferred at classification anyway), the mutation
     * root (whose {@code @routine}-terminal seat is the payload carrier's, whose rows are what the
     * data field re-reads rather than what the field returns), a carrier-shaped return reached from
     * anywhere else, and a routine name that resolves to no table-valued function.
     */
    private void groundRoutineReturnType(GraphQLObjectType parent, GraphQLFieldDefinition field) {
        if ("Mutation".equals(parent.getName())) return;
        var chain = field.getAppliedDirectives().stream()
            .filter(d -> DIR_ROUTINE.equals(d.getName()) || DIR_REFERENCE.equals(d.getName()))
            .toList();
        if (chain.isEmpty() || !DIR_ROUTINE.equals(chain.getLast().getName())) return;
        if (chain.stream().filter(d -> DIR_ROUTINE.equals(d.getName())).count() != 1) return;

        String returnSdl = unwrappedTypeName(field.getType());
        if (returnSdl == null) return;
        if (!(ctx.schema.getType(returnSdl) instanceof GraphQLObjectType returnObj)) return;
        if (returnObj.hasAppliedDirective(DIR_TABLE)) return;
        if (ctx.scanStructuralRoutineCarrierPayload(returnSdl)
                instanceof BuildContext.DmlPayloadScan.Admit) return;

        GraphQLAppliedDirective dir = field.getAppliedDirective(DIR_ROUTINE);
        String routineName = Optional.ofNullable(dir.getArgument(ARG_NAME))
            .map(a -> a.getValue()).map(Object::toString).orElse(null);
        if (routineName == null || routineName.isBlank()) return;
        if (!(ctx.catalog.resolveTableValuedFunction(routineName)
                instanceof JooqCatalog.RoutineResolution.Resolved fn)) return;

        routineReturnMemo.putIfAbsent(returnSdl, fn.resultTable());
    }

    /**
     * Grounds a {@link ProducerBinding.RoutineEmitted} observation for the payload SDL type of
     * a hop-less {@code @routine} Mutation field whose return scans as a routine carrier: the
     * shape the {@code @routine} carrier fork in {@code FieldBuilder.classifyMutationField}
     * classifies. The table is read straight off the scan's {@code DmlElementKind.Table}
     * element, and the name-matched pairs are computed right here, from the routine's result
     * table and that element table's primary key
     * ({@link BuildContext#deriveRoutineCarrierPairs}) — the derivation's single site; every
     * downstream reader (the mutation leaf's captured pairs, the data field's correlation)
     * reads this carried result.
     *
     * <p>Reading the table off the scan calls into {@code TypeBuilder.lookAheadVerdict} before the
     * walk begins; that is the sanctioned preparation-time probe pattern. The look-ahead memo does
     * not accept writes until {@code prepareForWalk} finishes, so a verdict computed here cannot
     * stick and be read back as if it were a post-fixed-point one.
     * {@link #groundDmlMutationField}, the other grounder in this pass, probes the same seam.
     *
     * <p>Skipped cases, each silent (the walk grounds observations, the classify-phase
     * resolvers diagnose): non-Mutation parents, a {@code @reference} beside the
     * {@code @routine} (the chain shape), {@code @table}-bound returns (the direct-return
     * shape), non-carrier scans, non-{@code Table} data-field elements, unresolvable routine
     * names, a failed name-match derivation, and unloadable record classes.
     */
    private void groundRoutineMutationField(GraphQLObjectType parent, GraphQLFieldDefinition field) {
        if (!"Mutation".equals(parent.getName())) return;
        if (!field.hasAppliedDirective(DIR_ROUTINE)) return;
        if (field.hasAppliedDirective(DIR_REFERENCE)) return;

        String payloadSdl = unwrappedTypeName(field.getType());
        if (payloadSdl == null) return;
        if (!(ctx.schema.getType(payloadSdl) instanceof GraphQLObjectType payloadObj)) return;
        // @table-bound returns are the direct-return shape (already grounded as RootTable);
        // the carrier fork never fires for them.
        if (payloadObj.hasAppliedDirective(DIR_TABLE)) return;

        if (!(ctx.scanStructuralRoutineCarrierPayload(payloadSdl)
                instanceof BuildContext.DmlPayloadScan.Admit admit)) return;
        if (!(admit.element() instanceof BuildContext.DmlElementKind.Table tableElement)) return;

        GraphQLAppliedDirective dir = field.getAppliedDirective(DIR_ROUTINE);
        String routineName = Optional.ofNullable(dir.getArgument(ARG_NAME))
            .map(a -> a.getValue()).map(Object::toString).orElse(null);
        if (routineName == null || routineName.isBlank()) return;
        if (!(ctx.catalog.resolveTableValuedFunction(routineName)
                instanceof JooqCatalog.RoutineResolution.Resolved fn)) return;

        TableRef targetTable = tableElement.table();
        if (!(BuildContext.deriveRoutineCarrierPairs(fn.resultTable(), targetTable)
                instanceof BuildContext.RoutineCarrierKeying.Pairs pairs)) {
            // The typed Unmatched failure surfaces at classify time, on the mutation field.
            return;
        }

        Class<?> recordClass;
        try {
            // nameability: exempt (jOOQ catalog record class)
            recordClass = Class.forName(
                CatalogRefs.recordClass(targetTable).reflectionName(), false, ctx.codegenLoader());
        } catch (ClassNotFoundException ignored) {
            return;
        }

        Arity arrival =
            GraphQLTypeUtil.unwrapNonNull(admit.dataField().getType()) instanceof GraphQLList
                ? Arity.MANY
                : Arity.ONE;

        routineEmittedMemo.putIfAbsent(payloadSdl, new ProducerBinding.RoutineEmitted(
            recordClass, targetTable, arrival, routineName, fn.resultTable(), pairs.pairs(),
            parent.getName(), field.getName(), locationOf(field)));
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

    /**
     * The single input-object argument of a {@code @mutation} field, or {@code null} when there is
     * not exactly one. The grounder lifts the DML arrival cardinality from this argument's list
     * shape. A well-formed DML mutation carries exactly one input-object argument (the classifier
     * rejects any other shape); zero or multiple leaves the field for the classifier to reject and
     * the grounder to skip.
     */
    private static GraphQLArgument singleInputObjectArg(GraphQLFieldDefinition field) {
        GraphQLArgument found = null;
        for (var arg : field.getArguments()) {
            if (GraphQLTypeUtil.unwrapAll(arg.getType()) instanceof GraphQLInputObjectType) {
                if (found != null) return null;
                found = arg;
            }
        }
        return found;
    }

    // ===== Phase 2: parent-accessor propagation =====

    private void propagateAccessorChains() {
        // Fold the root-producer observations into resultMemo/inputMemo first, so the loop below
        // reads concrete bindings on its first pass. Without this the snapshot (further down)
        // starts empty and the cascade never propagates: a record-backed type reached only through
        // a parent accessor would stay unbound.
        foldAll();
        // Iterate until no new bindings are produced. Each pass walks every SDL Object/Input
        // type with a currently-observed binding and propagates through its accessor edges.
        boolean changed = true;
        int safety = 0;
        while (changed) {
            if (safety++ > 1000) {
                throw new IllegalStateException("record-binding walker did not converge in 1000 passes");
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
            String childSdl = unwrappedTypeName(field.getType());
            if (childSdl == null) continue;
            // Don't re-ground a child SDL type that already has a binding (e.g. an @table type
            // bound via RootTable). The cascade exists to bind types that have no other producer;
            // adding an accessor observation to an already-bound type is at best redundant and at
            // worst a spurious conflict when the accessor's element is heterogeneous (a `film: Film`
            // field whose parent accessor returns LanguageRecord, not FilmRecord, would otherwise
            // ground Film <- LanguageRecord, collide with @table's FilmRecord, and knock Film out of
            // its @table classification). The field classifier handles such a mismatch as an
            // author-error rejection on the TableRecord path; the cascade must not pre-empt it.
            if (resultMemo.get(childSdl) != null) continue;
            // One probe call grounds the child class and names the accessor. The base name is
            // the @field(name:)-resolved accessor name and the argument shape is the SDL field's real
            // shape (both emission-side values), so walk and emission agree on which member reads the
            // field.
            AccessorProbe probe = ClassAccessorResolver.probe(parentClass,
                accessorBaseName(field.getName(), field), paramShapeFor(field),
                ClassAccessorResolver.forBackingClass(parentClass));
            if (probe instanceof AccessorProbe.NoMatch nm) {
                recordAccessorGate(childSdl, parentSdlType, field.getName(), nm, locationOf(field));
                continue;
            }
            var grounded = (AccessorProbe.Grounded) probe;
            Class<?> childCls = peelReturnElement(grounded.genericReturnType());
            if (childCls == null || !shouldBind(childCls)) continue;
            ProducerBinding pb = new ProducerBinding.ParentAccessor(
                childCls, parentSdlType, parentClass.getName(),
                field.getName(), grounded.memberName(),
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
            // Input fields take no arguments, so the probe's shape is always zero-argument; the
            // base name still honours @field(name:) for symmetry with the result axis.
            AccessorProbe probe = ClassAccessorResolver.probe(parentClass,
                accessorBaseName(field.getName(), field),
                new ClassAccessorResolver.PerArgument(List.of()),
                ClassAccessorResolver.forBackingClass(parentClass));
            if (probe instanceof AccessorProbe.NoMatch nm) {
                recordAccessorGate(childSdl, parentSdlType, field.getName(), nm, locationOf(field));
                continue;
            }
            var grounded = (AccessorProbe.Grounded) probe;
            Class<?> childCls = peelReturnElement(grounded.genericReturnType());
            if (childCls == null || !shouldBind(childCls)) continue;
            ProducerBinding pb = new ProducerBinding.ParentAccessor(
                childCls, parentSdlType, parentClass.getName(),
                field.getName(), grounded.memberName(),
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
            new Rejection.AuthorError.RecordBindingMultiProducer(sdlType,
                observed.stream()
                    .map(b -> new Rejection.AuthorError.RecordBindingMultiProducer.Binding(
                        b.describe(), b.reflectedClass().getName()))
                    .toList()));
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
            // nameability: exempt (revalidates @service / @externalField names ServiceCatalog already gated; this observation pass skips silently)
            Class<?> cls = Class.forName(className, false, ctx.codegenLoader());
            Method found = null;
            for (Method m : cls.getDeclaredMethods()) {
                if (!m.getName().equals(methodName)) continue;
                if (found != null) {
                    // Overloaded: ambiguous. This is an observation pass with no rejection channel,
                    // so it takes the first match rather than judging the set. ServiceCatalog is
                    // where the name is judged, and it no longer picks a first match at all: the
                    // directive coordinates reject a shared name, and the @condition coordinate
                    // admits the set on its binding shape.
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
            // A @service batch lift returns Map<ParentKey, Value> (a developer-written batch
            // function: Set<key> -> Map<key, value>); the field's backing is the map's Value, so
            // peel to the second type argument. Without this the raw Map is treated as the
            // element, fails shouldBind, and the field falls back to the whole Map as its value
            // type, producing a doubly-nested Map<key, Map<key, value>> at the loader.
            if (java.util.Map.class.isAssignableFrom(raw)) {
                if (args.length == 2) { current = args[1]; continue; }
                return raw;
            }
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
     * Whether the producer's reflected return type denotes multiple elements (a
     * {@link java.util.List} / {@link java.util.Set} / {@link java.util.Collection} /
     * {@link org.jooq.Result}), peeling a single {@link java.util.Optional} /
     * {@link java.util.concurrent.CompletableFuture} async wrapper first. Used by the cardinality-
     * match guard in {@link #groundServiceField}: a single-element return matches a single-object
     * SDL field (the carrier binds to its record), a multi-element return matches a list SDL field.
     */
    private static boolean isMultiCardinalityReturn(Type t) {
        Type current = t;
        for (int i = 0; i < 4; i++) {
            if (!(current instanceof ParameterizedType pt)) return false;
            if (!(pt.getRawType() instanceof Class<?> raw)) return false;
            if (java.util.List.class.isAssignableFrom(raw)
                || java.util.Set.class.isAssignableFrom(raw)
                || java.util.Collection.class.isAssignableFrom(raw)
                || org.jooq.Result.class.isAssignableFrom(raw)) {
                return true;
            }
            if (java.util.Map.class.isAssignableFrom(raw)) {
                // A @service batch lift Map<ParentKey, Value>: the field's cardinality follows the
                // Value (Map<key, List<X>> is a to-many field; Map<key, X> is single). Peel to Value
                // and continue, mirroring peelReturnElement.
                Type[] args = pt.getActualTypeArguments();
                if (args.length != 2) return false;
                current = args[1];
                continue;
            }
            if (java.util.Optional.class.isAssignableFrom(raw)
                || java.util.concurrent.CompletableFuture.class.isAssignableFrom(raw)) {
                Type[] args = pt.getActualTypeArguments();
                if (args.length != 1) return false;
                current = args[0];
                continue;
            }
            return false;
        }
        return false;
    }

    /**
     * Whether the named SDL output type is {@code @table}-bound (an OBJECT carrying {@code @table},
     * which includes {@code @node} types since those also carry {@code @table}). Such a type takes
     * its backing from its {@code @table} via the {@link ProducerBinding.RootTable} observation, so
     * a {@code @service} producing it must not also ground a {@link ProducerBinding.RootService}
     * result observation for the same SDL type.
     */
    private boolean isTableBackedSdlType(String sdlTypeName) {
        return ctx.schema.getType(sdlTypeName) instanceof GraphQLObjectType obj
            && obj.hasAppliedDirective(DIR_TABLE);
    }

    /**
     * The accessor base name the probe resolves against: the {@code @field(name:)} override when the
     * field carries it, else the raw SDL field name. Single-sourced with the emission side, which
     * resolves the same override before calling {@link ClassAccessorResolver#resolve}.
     */
    private static String accessorBaseName(String rawFieldName, GraphQLDirectiveContainer field) {
        if (field.hasAppliedDirective(DIR_FIELD)) {
            return argString(field, DIR_FIELD, ARG_NAME).orElse(rawFieldName);
        }
        return rawFieldName;
    }

    /**
     * The probe's {@link ClassAccessorResolver.ParamShape} for a result field: one {@link
     * ClassAccessorResolver.ArgShape} per SDL argument in declared order. The argument's Java type is
     * resolved by the phase-safe mapper ({@link #phaseSafeArgType}); arity is authoritative,
     * per-arg type assignability is best-effort at this phase.
     */
    private static ClassAccessorResolver.ParamShape paramShapeFor(GraphQLFieldDefinition field) {
        var args = field.getArguments();
        if (args.isEmpty()) return new ClassAccessorResolver.PerArgument(List.of());
        var shapes = new ArrayList<ClassAccessorResolver.ArgShape>(args.size());
        for (GraphQLArgument arg : args) {
            shapes.add(new ClassAccessorResolver.ArgShape(arg.getName(), phaseSafeArgType(arg.getType())));
        }
        return new ClassAccessorResolver.PerArgument(shapes);
    }

    /**
     * A phase-safe SDL-argument-type mapper for the binding walk, which runs before any classified
     * verdict exists (an input-object argument's backing class is the walk's own output). Resolves a
     * list wrapper to {@link List} and everything else to {@link Object}; an {@code Object} argument
     * degrades that parameter position to an arity-only check in {@link ClassAccessorResolver}, so the
     * walk enforces arity while leaving per-argument type assignability to emission's stricter mapper.
     */
    private static Type phaseSafeArgType(GraphQLInputType t) {
        GraphQLType current = t;
        while (true) {
            if (current instanceof GraphQLNonNull nn) { current = nn.getWrappedType(); continue; }
            if (current instanceof GraphQLList) return List.class;
            break;
        }
        return Object.class;
    }

    /**
     * Reason ledger: records a gated accessor near-miss (a member that name-matched on the parent
     * class but failed a walk tightening's gate) for the child SDL type the field references.
     * Surfaced only when that child type ends the walk with no producer at all, so the failure
     * names the accessor gate rather than a generic no-producer cascade. A plain name-absence is
     * not recorded (it keeps the ordinary no-producer path).
     */
    private void recordAccessorGate(String childSdl, String parentSdlType, String fieldName,
            AccessorProbe.NoMatch noMatch, SourceLocation location) {
        if (!noMatch.gatedNearMiss()) return;
        accessorGateReasons.putIfAbsent(childSdl,
            new AccessorGateReason(parentSdlType, fieldName, noMatch.reason(), location));
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
     * The head slot each {@code argMapping} entry binds to, keyed by Java parameter name: the
     * producer-binding probe walks types, not values, so it wants the outer argument and nothing
     * below it. Reads the head segment off the shared parse rather than a private one, so there is
     * exactly one path from an authored {@code argMapping} string to a resolved binding.
     *
     * <p>A syntax error yields no overrides <em>for the whole reference</em>, not just for the
     * offending entry. The probe has no diagnostic channel: it runs before any classified verdict
     * exists, and the same string is parsed again by the {@code ExternalCodeReference} consumer,
     * which rejects properly, so swallowing here keeps one typo to one message. Losing the
     * well-formed siblings' observations along with it is accepted: a malformed {@code argMapping}
     * fails the build at that consumer, so the reference's observations are moot either way, and
     * salvaging them would mean keeping a second, error-tolerant parser.
     */
    private static Map<String, String> headSlotOverrides(String raw) {
        if (!(ArgBindingMap.parseArgMapping(raw, ArgMappingSigil.Site.RECORD) instanceof ArgBindingMap.ParsedArgMapping.Ok parsed)) {
            return Map.of();
        }
        Map<String, String> overrides = new LinkedHashMap<>();
        parsed.overrides().forEach((javaName, segments) -> overrides.put(javaName, segments.get(0)));
        return overrides;
    }
}
