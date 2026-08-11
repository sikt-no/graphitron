package no.sikt.graphitron.rewrite.generators;

import graphql.schema.GraphQLSchema;
import no.sikt.graphitron.javapoet.CodeBlock;

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
 *
 * <p>The {@link #assembledSchema()} accessor lets the validator pre-step resolve each SDL arg's
 * input-type-ness without re-walking the schema from the entry point each call. {@code null}
 * when the caller did not provide an assembled schema (some unit-tier tests use the
 * model-only build via {@code TestSchemaHelper.buildSchema}); the validator pre-step falls
 * back to the legacy Map-based walk in that case.
 */
final class TypeFetcherEmissionContext {

    /** Helpers a {@code *Fetchers} class may emit at assembly time. */
    enum HelperKind {
        /** {@code private static GraphitronContext graphitronContext(DataFetchingEnvironment env)}. */
        GRAPHITRON_CONTEXT
    }

    private final EnumSet<HelperKind> requested = EnumSet.noneOf(HelperKind.class);
    private final GraphQLSchema assembledSchema;
    private final String parentTypeName;
    private final no.sikt.graphitron.rewrite.GraphitronSchema graphitronSchema;

    // The helper-name resolver for this <Type>Fetchers class, covering the create* (jOOQ record +
    // bean) and decode* method-name namespaces. Defaults to the bare resolver so schema-free / unit /
    // out-of-band contexts (which carry at most one class per simple name) behave exactly as before;
    // TypeFetcherGenerator installs a populated one up front.
    private FetchersHelperNames fetchersHelperNames = FetchersHelperNames.bare();

    private final no.sikt.graphitron.render.ArgPathHelperRegistry argPathHelpers =
        new no.sikt.graphitron.render.ArgPathHelperRegistry();

    TypeFetcherEmissionContext(GraphQLSchema assembledSchema, String parentTypeName) {
        this(assembledSchema, parentTypeName, null);
    }

    TypeFetcherEmissionContext(GraphQLSchema assembledSchema, String parentTypeName,
            no.sikt.graphitron.rewrite.GraphitronSchema graphitronSchema) {
        this.assembledSchema = assembledSchema;
        this.parentTypeName = parentTypeName;
        this.graphitronSchema = graphitronSchema;
    }

    /**
     * Convenience no-arg overload for callers that emit out-of-band (helpers, inline
     * subqueries, etc.) and don't need the schema-aware machinery the validator
     * pre-step requires. The assembled schema and parent-type name accessors return
     * {@code null} for such contexts.
     */
    TypeFetcherEmissionContext() {
        this(null, null, null);
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

    boolean isRequested(HelperKind kind) {
        return requested.contains(kind);
    }

    /**
     * The graphql-java assembled schema this fetcher class is being emitted for. {@code null}
     * when the caller provided no assembled schema; consumers must fall back to a schema-free
     * code path in that case.
     */
    GraphQLSchema assembledSchema() {
        return assembledSchema;
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

    /**
     * The classified {@link no.sikt.graphitron.rewrite.GraphitronSchema} being generated, or
     * {@code null} for schema-free callers (unit-tier model-only tests, nested-type emission).
     * The joined-table interface fetcher reads each participant's classified fields off this
     * to partition base-resident ({@code ColumnBackedReferenceField}) from detail-resident
     * ({@code ColumnBackedField}) fields per "the emitter reads the field variant".
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
    private final java.util.List<no.sikt.graphitron.javapoet.MethodSpec> companionMethods =
        new java.util.ArrayList<>();

    void addCompanionMethod(no.sikt.graphitron.javapoet.MethodSpec method) {
        companionMethods.add(method);
    }

    java.util.List<no.sikt.graphitron.javapoet.MethodSpec> drainCompanionMethods() {
        var out = java.util.List.copyOf(companionMethods);
        companionMethods.clear();
        return out;
    }
}
