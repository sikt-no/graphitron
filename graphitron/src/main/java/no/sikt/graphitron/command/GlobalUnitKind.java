package no.sikt.graphitron.command;

/**
 * The key of the global command relation: one constant per global emit family, meaning a family
 * that commits a fixed-name unit set per run (the {@code GeneratedUnits} {@code singleton} /
 * {@code rootUnit} naming schemes) rather than one unit per schema type. Each kind appears at
 * most once in a produced plan; most appear exactly once, and the gated kinds
 * ({@link #ONE_OF_DIRECTIVE_SDL}, {@link #ENTITY_FETCHER_DISPATCH}, {@link #QUERY_NODE_FETCHER},
 * {@link #DEV_EXECUTOR}) are absent when their producer-side membership predicate excludes them.
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
    /** Present exactly when the schema declares federation entities. */
    ENTITY_FETCHER_DISPATCH,
    CONNECTION_RESULT,
    CONNECTION_HELPER,
    /** Present exactly when the schema is federated and uses {@code @oneOf}. */
    ONE_OF_DIRECTIVE_SDL,
    POLYMORPHIC_SELECTION_SET,
    SELECTION_OCCURRENCES,
    ORDER_BY_RESULT,
    GRAPHITRON_CONTEXT,
    /** Commits the connection-runtime unit set; a session-state hook implementation joins it when configured. */
    CONNECTION_RUNTIME,
    TRANSACTION_PROVIDER,
    CONNECTION_INSTRUMENTATION,
    CONSTRAINT_VIOLATIONS,
    CLIENT_EXCEPTION,
    ERROR_ROUTER,
    OUTCOME,
    ERROR_MAPPINGS,
    SCHEMA_CLASS,
    /** Present exactly when the schema classifies at least one node type. */
    QUERY_NODE_FETCHER,
    FACADE,
    /** Present exactly when the schema is not federated. */
    DEV_EXECUTOR
}
