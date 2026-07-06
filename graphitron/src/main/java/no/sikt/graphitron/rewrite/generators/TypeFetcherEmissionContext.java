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
 * <p>R94 added {@link #assembledSchema()} so the validator pre-step can resolve each SDL arg's
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

    /**
     * Shape-aware naming for this class's {@code create<Record>} jOOQ-record {@code @service} helpers
     * (R437). Populated once, before the field bodies are emitted, from the carriers collected on this
     * class; both the call-site emitters ({@code ArgCallEmitter} / {@code ServiceMethodCallEmitter}) and
     * the helper-emission drain read it so a call site and its helper always agree on the name. Defaults
     * to the bare (single-shape) naming so schema-free / out-of-band contexts behave as before.
     */
    private JooqRecordHelperNames jooqRecordHelperNames = JooqRecordHelperNames.bare();

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
     * subqueries, etc.) and don't need the schema-aware machinery the {@code R94} validator
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
     * The classified {@link no.sikt.graphitron.rewrite.GraphitronSchema} being generated, or
     * {@code null} for schema-free callers (unit-tier model-only tests, nested-type emission).
     * The R389 joined-table interface fetcher reads each participant's classified fields off this
     * to partition base-resident ({@code ColumnReferenceField}) from detail-resident
     * ({@code ColumnField}) fields per "the emitter reads the field variant".
     */
    no.sikt.graphitron.rewrite.GraphitronSchema graphitronSchema() {
        return graphitronSchema;
    }

    /** Record the shape-aware jOOQ-record helper naming for this class (see the field javadoc, R437). */
    void setJooqRecordHelperNames(JooqRecordHelperNames names) {
        this.jooqRecordHelperNames = names;
    }

    /** The shape-aware jOOQ-record helper naming for this class; bare (single-shape) until set. */
    JooqRecordHelperNames jooqRecordHelperNames() {
        return jooqRecordHelperNames;
    }
}
