package no.sikt.graphitron.model.run;

import no.sikt.graphitron.model.boot.GraphitronModelStore;
import no.sikt.graphitron.model.read.StoreHandle;

import java.util.Objects;

/**
 * A store with one graph captured into it, and the two schemas that capture built.
 *
 * <p>The two come back together because a run that generates wants both from one reading of the
 * documents: the facts are in the store, and the schemas are what the gatherers built on the way
 * and do not write down. Closing this closes the store; the schemas are only values.
 */
public record CapturedGraph(GraphitronModelStore store, GraphIdentity graph, CapturedSchema schema)
    implements AutoCloseable {

    public CapturedGraph {
        Objects.requireNonNull(store, "store");
        Objects.requireNonNull(graph, "graph");
        Objects.requireNonNull(schema, "schema");
    }

    /** A reader over this graph's partition of the store. */
    public StoreHandle handle() {
        return new StoreHandle(store.dsl(), graph.name());
    }

    @Override
    public void close() {
        store.close();
    }
}
