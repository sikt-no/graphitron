package no.sikt.graphitron.rewrite.capture;

import graphql.schema.idl.TypeDefinitionRegistry;
import no.sikt.graphitron.model.boot.GraphitronModelStore;
import no.sikt.graphitron.rewrite.JooqCatalog;
import no.sikt.graphitron.rewrite.NodeDeclaration;
import no.sikt.graphitron.rewrite.catalog.CompletionData;
import org.jooq.DSLContext;

import java.util.List;

/**
 * Entry point for the generator's capture loads: opens a fact store for the run and fills it from
 * the parsed SDL, the jOOQ catalog, and the consumer's compiled extension classes.
 *
 * <p>Both loads are infallible by construction, and construction is the only guarantee in play.
 * The {@link TypeDefinitionRegistry} validates nothing, so every capture path is tolerant: what
 * does not fit records raw and located rather than throwing. Capture is total, with no
 * reachability pruning; a primary-key violation on any base relation is therefore a capture bug,
 * never something an author's schema can provoke.
 *
 * <p>Nothing reads the store yet. It is populated beside the live pipeline and dies with the run,
 * so a capture that produced nothing useful cannot change what the build accepts, rejects, emits,
 * or reports. Consumers migrate onto it one at a time.
 */
public final class FactCapture {

    private FactCapture() {}

    /**
     * Runs both loads against a fresh store and discards it. This is the shape the pipeline calls:
     * the store's whole lifetime is this method, because no consumer has migrated onto it yet.
     */
    public static void run(TypeDefinitionRegistry registry, JooqCatalog jooq,
                           List<CompletionData.ExternalReference> extensions, NodeDeclaration nodes) {
        try (GraphitronModelStore store = GraphitronModelStore.open()) {
            capture(store.dsl(), registry, jooq, extensions, nodes);
        }
    }

    /**
     * Fills {@code dsl}'s store from all three inputs. Separate from {@link #run} so a caller that
     * wants to query the result (the agreement and gate tests) can own the store's lifetime.
     *
     * @param jooq  the catalog to walk, or {@code null} for a caller with none in hand. The catalog
     *              itself rather than the {@code CatalogFacts} projection over it: that projection
     *              is shaped for the MCP catalog tools, and a narrowing it makes for them would
     *              land here as a fact about the consumer's database.
     * @param nodes the nodehood predicate macro expansion needs, since federation's key synthesis
     *              fires on nodes and nodehood can be inferred from the catalog rather than
     *              declared. A predicate built on a null catalog reduces it to {@code @node}
     *              presence, which is what a caller with no catalog in hand should get.
     */
    public static void capture(DSLContext dsl, TypeDefinitionRegistry registry,
                               JooqCatalog jooq,
                               List<CompletionData.ExternalReference> extensions,
                               NodeDeclaration nodes) {
        var sink = new FactSink(dsl);
        SdlFactCapture.capture(sink, registry, nodes);
        CatalogFactCapture.capture(sink, jooq, extensions);
        sink.flush();
    }

    /** SDL-only capture, for callers with no catalog in hand. */
    public static void capture(DSLContext dsl, TypeDefinitionRegistry registry) {
        capture(dsl, registry, null, List.of(), new NodeDeclaration(null));
    }
}
