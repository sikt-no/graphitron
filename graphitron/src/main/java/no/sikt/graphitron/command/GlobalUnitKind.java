package no.sikt.graphitron.command;

/**
 * The key of the global command relation: one constant per global emit family, meaning a family
 * that commits a fixed-name unit set per run (the {@code GeneratedUnits} {@code singleton} /
 * {@code rootUnit} naming schemes) rather than one unit per schema type. Each kind appears at
 * most once in a produced plan; most appear exactly once, and the gated kinds
 * ({@link #ONE_OF_DIRECTIVE_SDL}, {@link #ENTITY_FETCHER_DISPATCH}, {@link #QUERY_NODE_FETCHER},
 * {@link #FACETS})
 * are absent when their producer-side membership predicate excludes them.
 *
 * <p>Ten kinds never appear in a plan: {@link #GRAPHITRON_CONTEXT}, {@link #TRANSACTION_PROVIDER},
 * {@link #CONSTRAINT_VIOLATIONS}, {@link #CLIENT_EXCEPTION}, {@link #ZERO_ROWS_MATCHED_EXCEPTION},
 * {@link #ERROR_ROUTER}, {@link #OUTCOME} and the voucher runtime's {@link #VOUCHER_CODEC},
 * {@link #VOUCHER_KEYS} and {@link #VOUCHER_REJECTED_EXCEPTION} are written by {@link no.sikt.graphitron.rewrite.generators.schema.FixedRuntimeEmitter}, which keys
 * what it wrote by these constants so the compile graph's wiring for them stays one switch.
 *
 * <p>A pure key: where a unit of the kind lands is carried by the command's {@link UnitRef}s,
 * not here. The per-type-emitting families (types, conditions, fetchers, inputs, per-type schema
 * shapes) are a different relation, keyed {@code (typeName, unitKind)}, and deliberately not
 * represented here.
 */
public enum GlobalUnitKind {

    GRAPHITRON_VALUES,
    LIGHT_FETCHER,
    NODE_ID_ENCODER,
    COLUMN_VALUES,
    /** Present exactly when the schema declares federation entities. */
    ENTITY_FETCHER_DISPATCH,
    CONNECTION_RESULT,
    CONNECTION_HELPER,
    /** The page info every connection hands its {@code pageInfo} field, one class shared by all. */
    PAGE_INFO,
    /** Present exactly when the schema has a faceted connection: the facets and facet value records. */
    FACETS,
    /** Present exactly when the schema is federated and uses {@code @oneOf}. */
    ONE_OF_DIRECTIVE_SDL,
    SELECTION_OCCURRENCES,
    /** The schema's nesting coordinates and the result-key alias path walk over them. */
    NESTING_COORDINATES,
    ORDER_BY_RESULT,
    GRAPHITRON_CONTEXT,
    /** Commits the connection-runtime unit set; a session-state hook implementation joins it when configured. */
    CONNECTION_RUNTIME,
    TRANSACTION_PROVIDER,
    CONNECTION_INSTRUMENTATION,
    CONSTRAINT_VIOLATIONS,
    CLIENT_EXCEPTION,
    ZERO_ROWS_MATCHED_EXCEPTION,
    ERROR_ROUTER,
    OUTCOME,
    /** Written exactly when the graph vouches: the voucher format's owner. */
    VOUCHER_CODEC,
    /** Written exactly when the graph vouches: the keys vouchers are signed and verified with. */
    VOUCHER_KEYS,
    /** Written exactly when the graph vouches: the rejection a failed voucher check throws. */
    VOUCHER_REJECTED_EXCEPTION,
    ERROR_MAPPINGS,
    SCHEMA_CLASS,
    /** Present exactly when the schema classifies at least one node type. */
    QUERY_NODE_FETCHER,
    FACADE,
    DEV_EXECUTOR
}
