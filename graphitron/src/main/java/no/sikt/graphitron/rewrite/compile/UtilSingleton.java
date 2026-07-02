package no.sikt.graphitron.rewrite.compile;

import java.util.List;

/**
 * R410 slice 2 — classifies each fixed-name runtime singleton by how its bytecode behaves across a
 * schema edit, because that determines how a {@code <Type>Fetchers} node may depend on it in the
 * compile graph.
 *
 * <p>This is the type-carried form of the "frozen vs growing" distinction: a blanket over-approximated
 * edge (every fetcher → the singleton) is a correct superset only for a singleton whose <em>public
 * ABI does not move on a schema edit</em> ({@link FrozenScaffold}). A singleton whose ABI grows with
 * the schema ({@link PerTypeGrowing}) must instead be reached by <em>precise</em> edges sourced from
 * the model leaves that actually use it, or a schema edit would drag every fetcher into the recompile
 * set (a clause-(b) pruning failure). Making the split a sealed variant with an exhaustive consumer
 * switch forces a deliberate frozen-or-growing choice for any singleton added later, rather than
 * silently degrading pruning; the {@link #ALL} membership and each variant choice are pinned by the
 * clause-(b) recompile-set test (slice 5) and the {@link TypeSpecReferenceWalk} completeness oracle.
 */
sealed interface UtilSingleton {

    /** Sub-package the singleton is emitted into. */
    String subPackage();

    /** Fixed simple class name of the singleton (mirrors the generator's {@code CLASS_NAME}). */
    String simpleName();

    /**
     * A schema-independent runtime helper: the same bytecode is emitted for every schema, so its
     * public ABI never moves on a schema edit. A blanket edge into it from every fetcher is a
     * pruning-harmless superset (slice-3 ABI-gating never fires on a node whose ABI is stable).
     */
    record FrozenScaffold(String subPackage, String simpleName) implements UtilSingleton {}

    /**
     * A singleton whose bytecode grows with the schema in a way that moves its <em>public</em> ABI,
     * so a blanket edge would over-propagate. The only such singleton today is {@code NodeIdEncoder},
     * which gains an {@code encode<Type>} / {@code decode<Type>} method pair per {@code @node} type;
     * the builder reaches it with precise edges from the {@code CallSiteCompaction.NodeIdEncodeKeys}
     * leaves and the {@code NodeType} nodes instead of blanketing it.
     */
    record PerTypeGrowing(String subPackage, String simpleName) implements UtilSingleton {}

    /**
     * Every runtime singleton a {@code <Type>Fetchers} unit may reference. The {@link FrozenScaffold}
     * entries drive the blanket over-approximation; the sole {@link PerTypeGrowing} entry
     * ({@code NodeIdEncoder}) is deliberately excluded from blanketing and reached precisely.
     */
    List<UtilSingleton> ALL = List.of(
        new FrozenScaffold(GeneratedUnits.SUB_UTIL, "LightFetcher"),
        new FrozenScaffold(GeneratedUnits.SUB_UTIL, "ConnectionResult"),
        new FrozenScaffold(GeneratedUnits.SUB_UTIL, "ConnectionHelper"),
        new FrozenScaffold(GeneratedUnits.SUB_UTIL, "OrderByResult"),
        new FrozenScaffold(GeneratedUnits.SUB_UTIL, "PolymorphicSelectionSet"),
        new FrozenScaffold(GeneratedUnits.SUB_UTIL, "GraphitronValues"),
        new FrozenScaffold(GeneratedUnits.SUB_SCHEMA, "Outcome"),
        new FrozenScaffold(GeneratedUnits.SUB_SCHEMA, "ConstraintViolations"),
        new FrozenScaffold(GeneratedUnits.SUB_SCHEMA, "GraphitronClientException"),
        new FrozenScaffold(GeneratedUnits.SUB_SCHEMA, "ErrorRouter"),
        new FrozenScaffold(GeneratedUnits.SUB_SCHEMA, "ErrorMappings"),
        new PerTypeGrowing(GeneratedUnits.SUB_UTIL, "NodeIdEncoder")
    );

    /** The {@code NodeIdEncoder} singleton, reached by precise edges (see {@link PerTypeGrowing}). */
    UtilSingleton NODE_ID_ENCODER = new PerTypeGrowing(GeneratedUnits.SUB_UTIL, "NodeIdEncoder");
}
