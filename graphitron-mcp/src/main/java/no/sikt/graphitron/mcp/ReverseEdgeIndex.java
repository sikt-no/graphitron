package no.sikt.graphitron.mcp;

import no.sikt.graphitron.rewrite.catalog.CatalogFacts;
import no.sikt.graphitron.rewrite.catalog.CompletionData;
import no.sikt.graphitron.rewrite.catalog.LspSchemaSnapshot;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The reverse-edge index, the real deliverable here: the impact-analysis
 * directions agents cannot cheaply walk forward ("what schema fields break if I touch this column /
 * method / table?"). Maps each stable column / table / method ID back to the field coordinates that
 * bind it, by <b>inverting the same per-field switch the forward producer uses</b> ({@link
 * EdgeProducer#fieldEdges}), so the two directions cannot disagree.
 *
 * <p>Lazy and module-local: it is a pure derived function of projections already on the
 * {@code Workspace} ({@code snapshot} for field bindings, {@code catalogFacts} for the FK side), so
 * it needs no {@code BuildArtifacts} component, no {@code Workspace} field, and no new dev trigger.
 * Built on first reverse traversal after a build, not eagerly on every build swap (forward edges,
 * stage 1, need no index; only the reverse direction does).
 *
 * <p>It deliberately does <b>not</b> index the {@code PARTICIPATES} (type -> type) direction: that
 * is cheaply walkable forward through the {@code schema} tool, so it stays a stage-1 forward-only
 * edge. The table -> table FK reverse direction is read straight off {@code CatalogFacts.incoming()}
 * at query time (the catalog already stores it per table), so it is not materialised here either.
 */
final class ReverseEdgeIndex {

    private static final ReverseEdgeIndex EMPTY = new ReverseEdgeIndex(Map.of());

    /** Stable target ID -> the reverse edges whose {@code target} is the binding field. */
    private final Map<String, List<Edge>> byTargetWireId;

    private ReverseEdgeIndex(Map<String, List<Edge>> byTargetWireId) {
        this.byTargetWireId = byTargetWireId;
    }

    /** The reverse edges for one node ID (its binding fields); empty when nothing binds it. */
    List<Edge> reverseEdges(String wireId) {
        return byTargetWireId.getOrDefault(wireId, List.of());
    }

    /**
     * Builds the index by inverting every field's forward edges: a forward edge field -> column /
     * table / method becomes a reverse edge keyed by that column / table / method ID, whose
     * {@code target} is the binding field (the direction-as-query-axis contract: the endpoint slot
     * holds the field, not the queried node). Type-target ({@code PARTICIPATES}) edges are skipped.
     */
    static ReverseEdgeIndex build(LspSchemaSnapshot.Built snapshot, EdgeProducer.Context ctx) {
        var map = new HashMap<String, List<Edge>>();
        for (var entry : snapshot.fieldClassificationsByCoord().entrySet()) {
            var source = new NodeRef.FieldNode(entry.getKey());
            for (var edge : EdgeProducer.fieldEdges(entry.getValue(), ctx)) {
                if (edge.target() instanceof NodeRef.TypeNode) continue;
                var reverse = new Edge(edge.kind(), source, edge.joinPath());
                map.computeIfAbsent(edge.target().wireId(), k -> new ArrayList<>()).add(reverse);
            }
        }
        return new ReverseEdgeIndex(map);
    }

    /**
     * Snapshot-memoised holder. The memo key is the {@code (snapshot, catalogFacts)} reference pair,
     * not the snapshot alone: the index derives from two distinct {@code volatile} projections that
     * ride different cadences in the MCP server's stability gradient (schema vs classpath), even though one
     * {@code setBuildOutput} bundles them. Keying on the held-reference identity of both rebuilds the
     * index whenever <em>either</em> is swapped (including a {@code demoteSnapshot} that mints a fresh
     * {@code Built.Previous}); a torn read against the non-atomic multi-field swap self-heals on the
     * next call once the second write lands and the key misses.
     */
    static final class Cache {
        private LspSchemaSnapshot keySnapshot;
        private CatalogFacts keyFacts;
        private ReverseEdgeIndex index = EMPTY;

        synchronized ReverseEdgeIndex get(
            LspSchemaSnapshot snapshot, CatalogFacts facts,
            List<CompletionData.ExternalReference> externalReferences
        ) {
            if (snapshot != keySnapshot || facts != keyFacts) {
                keySnapshot = snapshot;
                keyFacts = facts;
                index = snapshot instanceof LspSchemaSnapshot.Built b
                    ? build(b, new EdgeProducer.Context(facts, externalReferences))
                    : EMPTY;
            }
            return index;
        }
    }
}
