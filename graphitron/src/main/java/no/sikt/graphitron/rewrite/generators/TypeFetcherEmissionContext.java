package no.sikt.graphitron.rewrite.generators;

import com.palantir.javapoet.CodeBlock;

import java.util.EnumSet;

/**
 * Per-class emission scratchpad for {@link TypeFetcherGenerator}. One instance lives for the
 * duration of a single {@code generateTypeSpec} call and accumulates the set of helpers any
 * emitted method body has requested. Class assembly drains the set at the end of
 * {@code generateTypeSpec} and emits the corresponding helper methods.
 *
 * <p>The carrier replaces a previous post-scan over emitted method bodies that string-grepped for
 * {@code graphitronContext(env)}. The bug class that motivated the carrier ; an emitter writes a
 * {@code graphitronContext(env)} call but the gating predicate doesn't enumerate that field
 * variant ; becomes structurally impossible: the call only exists as the return value of
 * {@link #graphitronContextCall()}, which records the dependency on the way out.
 */
final class TypeFetcherEmissionContext {

    /** Helpers a {@code *Fetchers} class may emit at assembly time. */
    enum HelperKind {
        /** {@code private static GraphitronContext graphitronContext(DataFetchingEnvironment env)}. */
        GRAPHITRON_CONTEXT,
        /**
         * {@code private static <T> DataType<T> withoutGenerator(DataType<T> type)}, see
         * {@link MultiTablePolymorphicEmitter#buildWithoutGeneratorHelper()}.
         */
        WITHOUT_GENERATOR,
        /** {@code static Field<?> outboxJsonValue(Object value)}, see {@code OutboxEmitter.jsonValueHelper()}. */
        OUTBOX_JSON_VALUE
    }

    private final EnumSet<HelperKind> requested = EnumSet.noneOf(HelperKind.class);
    private final String parentTypeName;
    private final no.sikt.graphitron.rewrite.GraphitronSchema graphitronSchema;

    // The helper-name resolver for this <Type>Fetchers class, covering the create* (jOOQ record +
    // bean) and decode* method-name namespaces. Defaults to the bare resolver so schema-free / unit /
    // out-of-band contexts (which carry at most one class per simple name) behave exactly as before;
    // TypeFetcherGenerator installs a populated one up front.
    private FetchersHelperNames fetchersHelperNames = FetchersHelperNames.bare();

    private final no.sikt.graphitron.render.ArgPathHelperRegistry argPathHelpers =
        new no.sikt.graphitron.render.ArgPathHelperRegistry();

    // This class's node-id decode-helper collector, installed by TypeFetcherGenerator from the same
    // collectInto bracket the filter sites register through. Held here rather than threaded so a
    // @nodeId argument's slot and a filter decoding the same node type land on one helper body; null
    // in contexts that never opened the bracket, which the slot emitter turns into a throw rather
    // than a call to a helper nothing drains.
    private no.sikt.graphitron.render.CompositeDecodeHelperRegistry nodeIdDecodeHelpers;

    // The graph's projected argMapping bindings, for the renderers that emit a decode-and-project
    // read. Empty by default so out-of-band and unit contexts behave as they did; TypeFetcherGenerator
    // installs the plan's relation up front, alongside the helper-name resolver the host allocates
    // decode<TypeName>Record names from.
    private no.sikt.graphitron.command.KeyProjectionRelation keyProjections =
        no.sikt.graphitron.command.KeyProjectionRelation.empty();

    // The store's error channels, for the catch arms naming their outcome type's dispatch table.
    private no.sikt.graphitron.emit.ErrorChannelRelation errorChannels =
        no.sikt.graphitron.emit.ErrorChannelRelation.empty();

    // The store's @outbox rows, for the DML sites that write a message beside their write. Empty by
    // default so out-of-band and unit contexts behave as they did.
    private no.sikt.graphitron.emit.OutboxQuery.Rows outboxes =
        no.sikt.graphitron.emit.OutboxQuery.Rows.empty();

    // The store's record accessors, for the VALIDATION pre-step's per-column check on a jOOQ record.
    // Empty by default, so a context with no store checks no record column.
    private no.sikt.graphitron.emit.RecordAccessorQuery.Rows recordAccessors =
        no.sikt.graphitron.emit.RecordAccessorQuery.Rows.empty();

    TypeFetcherEmissionContext(String parentTypeName,
            no.sikt.graphitron.rewrite.GraphitronSchema graphitronSchema) {
        this.parentTypeName = parentTypeName;
        this.graphitronSchema = graphitronSchema;
    }

    /**
     * Convenience no-arg overload for callers that emit out-of-band (helpers, inline
     * subqueries, etc.) and are not tied to one parent type. The parent-type name accessor
     * returns {@code null} for such contexts.
     */
    TypeFetcherEmissionContext() {
        this(null, null);
    }

    /**
     * Returns the literal {@code graphitronContext(env)} call expression and records that the
     * class needs the {@code graphitronContext} helper. Format-string callers should
     * interpolate the returned {@link CodeBlock} via {@code $L}.
     */
    CodeBlock graphitronContextCall() {
        requested.add(HelperKind.GRAPHITRON_CONTEXT);
        return CodeBlock.of("graphitronContext(env)");
    }

    /**
     * This class's {@code graphitronContext(env)} seam as a value, for the {@code render/}
     * emitters that need the call but must not hold this context. Recording is
     * {@link #graphitronContextCall()}'s, so a renderer's request drains through the same
     * {@link HelperKind#GRAPHITRON_CONTEXT} set an in-package emitter's does.
     */
    no.sikt.graphitron.render.RequestContextRead requestContextRead() {
        return this::graphitronContextCall;
    }

    /**
     * Returns the {@code withoutGenerator(<dataType>)} call expression and records that the class
     * needs the {@code withoutGenerator} helper, the way {@link #graphitronContextCall()} records
     * its own.
     */
    CodeBlock withoutGeneratorCall(CodeBlock dataType) {
        requested.add(HelperKind.WITHOUT_GENERATOR);
        return CodeBlock.of("$L($L)", MultiTablePolymorphicEmitter.WITHOUT_GENERATOR, dataType);
    }

    boolean isRequested(HelperKind kind) {
        return requested.contains(kind);
    }

    /** The SDL parent type name (the type whose fields are being emitted as fetchers). */
    String parentTypeName() {
        return parentTypeName;
    }

    /**
     * This class's nested-argument descent registry: a {@code @routine} binding authored as a
     * dot-path registers its walk here and the helper drains onto the {@code <Type>Fetchers}
     * class at assembly. Carried on the emission context rather than threaded as a parameter
     * because the routine call sites are spread across the fetcher, launcher and companion
     * emitters, all of which already hold the context.
     */
    no.sikt.graphitron.render.ArgPathHelperRegistry argPathHelpers() {
        return argPathHelpers;
    }

    /** Installs the graph's projected {@code argMapping} bindings for this class's emission. */
    void setKeyProjections(no.sikt.graphitron.command.KeyProjectionRelation keyProjections) {
        this.keyProjections = keyProjections;
    }

    /** Installs the store's {@code @outbox} rows for this class's emission. */
    void setOutboxes(no.sikt.graphitron.emit.OutboxQuery.Rows outboxes) {
        this.outboxes = outboxes;
    }

    /**
     * The store's {@code @outbox} row for this class's field {@code fieldName}, empty where the
     * field carries none. A present row obliges the caller to register the companion method it
     * renders ({@link #addCompanionMethod}) and place the call.
     */
    java.util.Optional<no.sikt.graphitron.emit.OutboxQuery.Row> outboxFor(String fieldName) {
        return outboxes.rowFor(parentTypeName, fieldName);
    }

    /**
     * Records that this class's companion methods name the {@code outboxJsonValue} helper, which
     * class assembly then emits once.
     */
    void requestOutboxJsonValue() {
        requested.add(HelperKind.OUTBOX_JSON_VALUE);
    }

    /** Installs the store's record accessors for this class's emission. */
    void setRecordAccessors(no.sikt.graphitron.emit.RecordAccessorQuery.Rows recordAccessors) {
        this.recordAccessors = recordAccessors;
    }

    /**
     * The Bean Validation property each catalog record reads a column through, which decides the
     * columns a VALIDATION pre-step checks on a jOOQ record parameter.
     */
    no.sikt.graphitron.emit.RecordAccessorQuery.Rows recordAccessors() {
        return recordAccessors;
    }

    /** Installs the store's error channels for this class's emission. */
    void setErrorChannels(no.sikt.graphitron.emit.ErrorChannelRelation errorChannels) {
        this.errorChannels = errorChannels;
    }

    /**
     * The {@code ErrorMappings} constant a catch arm on this class's field {@code fieldName} names:
     * the dispatch table of the outcome type the store says that producer catches into. A field
     * the walk gave a channel and the store gave no outcome is drift between the two, refused
     * rather than given a name nothing emits.
     */
    String mappingsConstant(String fieldName) {
        return errorChannels.outcomeOf(parentTypeName, fieldName)
            .map(no.sikt.graphitron.rewrite.generators.schema.ErrorMappingsClassGenerator::channelConstantName)
            .orElseThrow(() -> new IllegalStateException("Graphitron generator bug (error channel): '"
                + parentTypeName + "." + fieldName + "' catches into an outcome the classified schema"
                + " states and graphitron_producer_outcome does not; the walk and the store have"
                + " drifted"));
    }

    /**
     * What this {@code <Type>Fetchers} class brings to a projected key read: the graph's projections,
     * and this class's own {@code decode<TypeName>Record} name for a decoded record. The name is resolved
     * lazily through {@link #fetchersHelperNames()} because the resolver is installed after this
     * context is constructed, and it is the class's rather than a renderer's for the reason that
     * resolver exists: two schema packages can hold same-simple-named record classes, and the
     * {@code decode*} namespace is allocated across the whole class.
     */
    no.sikt.graphitron.render.ProjectedKeyHost projectedKeyHost() {
        return new no.sikt.graphitron.render.ProjectedKeyHost(keyProjections,
            projection -> fetchersHelperNames().decodeSingular(projection.nodeTypeName()));
    }

    /**
     * The classified {@link no.sikt.graphitron.rewrite.GraphitronSchema} being generated, or
     * {@code null} for schema-free callers (unit-tier model-only tests). The joined-table
     * interface fetcher reads each participant's classified fields off this to partition
     * base-resident ({@code ColumnBackedReferenceField}) from detail-resident
     * ({@code ColumnBackedField}) fields per "the emitter reads the field variant", and the
     * tenant-routing emitter reads every field's tenant binding off it, nested-type fields
     * included.
     */
    no.sikt.graphitron.rewrite.GraphitronSchema graphitronSchema() {
        return graphitronSchema;
    }

    /**
     * The helper-name resolver for this class, the single home for the {@code create*} (jOOQ record +
     * bean) and {@code decode*} method-name decisions. Consulted by the two call-site emitters
     * ({@link ArgCallEmitter}, {@link ServiceMethodCallEmitter}), the bean / decode helper emitters
     * ({@link InputBeanInstantiationEmitter}), and the helper-emission drain in
     * {@link TypeFetcherGenerator}, so a call site and its helper always agree on the name. Defaults
     * to the bare resolver until {@link #setFetchersHelperNames} installs a populated one.
     */
    FetchersHelperNames fetchersHelperNames() {
        return fetchersHelperNames;
    }

    /**
     * This class's node-id decode-helper collector, or {@code null} in a context that never opened
     * the collect-and-drain bracket (unit-tier and out-of-band emission). Read by the
     * {@code @service} slot emitter, which needs the same collector the filter sites use so one
     * decode body serves both.
     */
    no.sikt.graphitron.render.CompositeDecodeHelperRegistry nodeIdDecodeHelpers() {
        return nodeIdDecodeHelpers;
    }

    /**
     * Install the collector for this class, called by {@link TypeFetcherGenerator} at the top of the
     * bracket that drains it.
     */
    void setNodeIdDecodeHelpers(no.sikt.graphitron.render.CompositeDecodeHelperRegistry registry) {
        this.nodeIdDecodeHelpers = registry;
    }

    /**
     * The shape-aware {@code create<Record>} jOOQ-record arm of {@link #fetchersHelperNames()}, the
     * seam the two jOOQ-record call-site emitters read.
     */
    JooqRecordHelperNames jooqRecordHelperNames() {
        return fetchersHelperNames.jooqRecord();
    }

    /**
     * Install the populated {@link FetchersHelperNames} resolver for this class. Called once by
     * {@link TypeFetcherGenerator} <em>before</em> any field body emits, so every call site and the
     * helper drain read the same resolver.
     */
    void setFetchersHelperNames(FetchersHelperNames names) {
        this.fetchersHelperNames = names;
    }

    // Companion methods a field-body emitter declares alongside the fetcher method it is
    // building (the DML reentry rows methods). Class assembly drains this list
    // into the TypeSpec after the field loop, mirroring the HelperKind drain: a companion can
    // never be silently dropped because the only way to reference one is through the name the
    // declaring call returned.
    private final java.util.List<com.palantir.javapoet.MethodSpec> companionMethods =
        new java.util.ArrayList<>();

    void addCompanionMethod(com.palantir.javapoet.MethodSpec method) {
        companionMethods.add(method);
    }

    java.util.List<com.palantir.javapoet.MethodSpec> drainCompanionMethods() {
        var out = java.util.List.copyOf(companionMethods);
        companionMethods.clear();
        return out;
    }

    // The ConstraintViolations.Paths constants this class's validation pre-steps name, keyed by the
    // translation they build, so two fields binding one class through the same SDL layout share one
    // and the same class bound through two layouts gets two.
    private final java.util.Map<String, String> validationPathNames = new java.util.LinkedHashMap<>();
    private final java.util.List<com.palantir.javapoet.FieldSpec> validationPathFields =
        new java.util.ArrayList<>();

    /**
     * The name of this class's {@code private static final ConstraintViolations.Paths} constant
     * translating {@code subject}'s Java members to the SDL input fields they bind, declaring it on
     * first request: a bound class's members, or a jOOQ record's {@code checks}. Class assembly drains
     * the declarations through {@link #drainValidationPathFields()}.
     */
    String validationPathsConstant(no.sikt.graphitron.rewrite.model.ServiceMethodCall.ValidationSubject subject,
            java.util.List<no.sikt.graphitron.rewrite.model.ServiceMethodCall.ValidationSubject.ColumnCheck> checks,
            String outputPackage) {
        var initializer = no.sikt.graphitron.rewrite.generators.schema.ConstraintViolationsClassGenerator
            .pathsInitializer(subject, checks, outputPackage);
        var javaClass = (com.palantir.javapoet.ClassName) subject.javaType();
        return validationPathNames.computeIfAbsent(initializer.toString(), key -> {
            String stem = "VALIDATION_PATHS_"
                + javaClass.simpleName().replaceAll("([a-z0-9])([A-Z])", "$1_$2").toUpperCase(java.util.Locale.ROOT);
            String name = stem;
            for (int n = 2; validationPathNames.containsValue(name); n++) {
                name = stem + "_" + n;
            }
            validationPathFields.add(com.palantir.javapoet.FieldSpec.builder(
                    no.sikt.graphitron.rewrite.generators.schema.ConstraintViolationsClassGenerator.pathsClass(outputPackage),
                    name, javax.lang.model.element.Modifier.PRIVATE, javax.lang.model.element.Modifier.STATIC,
                    javax.lang.model.element.Modifier.FINAL)
                .initializer(initializer)
                .build());
            return name;
        });
    }

    java.util.List<com.palantir.javapoet.FieldSpec> drainValidationPathFields() {
        var out = java.util.List.copyOf(validationPathFields);
        validationPathFields.clear();
        return out;
    }
}
