package no.sikt.graphitron.model.read;

import org.jooq.DSLContext;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static no.sikt.graphitron.model.Tables.STORE_GRAPH_SOURCE;

/**
 * Which graph's partition a source file's facts belong to, resolved once at a request boundary.
 *
 * <p>A consumer that holds a file and wants the store's answer about it holds the wrong key: the
 * source-keyed families say what a file declares, and the graph-keyed ones say what a module
 * captured, so a read that spans both needs a graph before it can start. {@code store_graph_source}
 * is the relation that says which sources a graph's run read, and this is the one place a
 * {@code source_name} is turned into a {@link StoreHandle} through it.
 *
 * <p>Three arms, because that relation puts no uniqueness on {@code source_name}: a schema file
 * shared between two modules is a member of both graphs, and both rows are true. Taking the first
 * would answer a question nobody asked, silently picking whichever module the store happened to
 * capture first and reporting a sibling's classification as this file's. {@link Shared} hands the
 * membership back instead and leaves the choice with the caller, which is the only layer that knows
 * whether the request came from an editor with a project open, a tool naming a graph, or neither.
 *
 * <p>{@link Uncaptured} is the store saying it has never read this file, for any graph, which is
 * the ordinary state of a file created since the last capture. It is not a failure and carries no
 * diagnostic weight of its own: absence is an answer, and a consumer with nothing to say says
 * nothing.
 *
 * <p>Resolve inside the same read transaction as the answer it scopes. A resolution from one
 * snapshot used against another would scope a read by a membership that no longer holds, which is
 * exactly the straddle {@code StoreReader.read} exists to prevent.
 */
public sealed interface SourceGraph {

    /** The source belongs to exactly one captured graph, and here is the handle to read it with. */
    record Scoped(StoreHandle handle) implements SourceGraph {
        public Scoped {
            Objects.requireNonNull(handle, "handle");
        }
    }

    /** No captured graph has read this source, so the store holds no facts keyed to it. */
    record Uncaptured(String sourceName) implements SourceGraph {
        public Uncaptured {
            Objects.requireNonNull(sourceName, "sourceName");
        }
    }

    /**
     * More than one captured graph has read this source. The names are ordered so a caller
     * rendering them (a diagnostic, a picker) renders the same list on every resolution of the
     * same store state.
     */
    record Shared(String sourceName, List<String> graphNames) implements SourceGraph {
        public Shared {
            Objects.requireNonNull(sourceName, "sourceName");
            graphNames = List.copyOf(graphNames);
        }
    }

    /**
     * Resolves {@code sourceName} against {@code store_graph_source}, as the reader spelled it:
     * the relation stores source names verbatim from the walk that met them, so a caller holding
     * an editor URI or a relative path has to render it the way capture did before asking.
     *
     * @param dsl a query surface over a booted store; the handle in a {@link Scoped} answer carries
     *            this same surface, so the caller reads through the connection it resolved on
     */
    static SourceGraph of(DSLContext dsl, String sourceName) {
        Objects.requireNonNull(dsl, "dsl");
        Objects.requireNonNull(sourceName, "sourceName");
        return ofAll(dsl, List.of(sourceName)).get(sourceName);
    }

    /**
     * The same resolution for several sources at once, in one query, keyed by source name and never
     * missing one: a source no graph has read answers {@link Uncaptured} rather than being absent, so a
     * caller reads every source it asked about out of the result.
     *
     * <p>Bulk because a caller resolving a set of documents is answering one request about all of them,
     * and a query per document is the shape that made a whole-workspace recalculation cost two
     * statements per open file before it had read a single fact. The arity rule lives here and
     * {@link #of} reads it through this, so one source and forty are resolved by the same three arms.
     *
     * @param dsl a query surface over a booted store; the handle in a {@link Scoped} answer carries
     *            this same surface, so the caller reads through the connection it resolved on
     */
    static Map<String, SourceGraph> ofAll(DSLContext dsl, Collection<String> sourceNames) {
        Objects.requireNonNull(dsl, "dsl");
        Objects.requireNonNull(sourceNames, "sourceNames");
        if (sourceNames.isEmpty()) return Map.of();
        var graphsBySource = new LinkedHashMap<String, List<String>>();
        dsl.select(STORE_GRAPH_SOURCE.SOURCE_NAME, STORE_GRAPH_SOURCE.GRAPH_NAME)
            .from(STORE_GRAPH_SOURCE)
            .where(STORE_GRAPH_SOURCE.SOURCE_NAME.in(sourceNames))
            .orderBy(STORE_GRAPH_SOURCE.SOURCE_NAME, STORE_GRAPH_SOURCE.GRAPH_NAME)
            .forEach(row -> graphsBySource
                .computeIfAbsent(row.value1(), ignored -> new ArrayList<>())
                .add(row.value2()));
        var resolved = new LinkedHashMap<String, SourceGraph>();
        for (String sourceName : sourceNames) {
            List<String> graphNames = graphsBySource.getOrDefault(sourceName, List.of());
            resolved.put(sourceName, switch (graphNames.size()) {
                case 0 -> new Uncaptured(sourceName);
                case 1 -> new Scoped(new StoreHandle(dsl, graphNames.getFirst()));
                default -> new Shared(sourceName, graphNames);
            });
        }
        return resolved;
    }
}
