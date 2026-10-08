package no.sikt.graphitron.mcp;

import no.sikt.graphitron.model.boot.StoreReader;
import no.sikt.graphitron.model.read.StoreHandle;

/**
 * What a case reads a captured store through: the surfaces a tool takes and nothing that changes
 * the store. {@link StoreFixture} offers it beside its mutators, and {@link StoreFixture.Shared}
 * offers nothing else, so a case on a class-wide store cannot reach for {@code makeRunaway},
 * {@code recaptureCatalog} or {@code andGraph} by accident.
 *
 * <p>A convenience rather than a guarantee: {@link StoreHandle#dsl()} hands back a writable
 * context. What guarantees a shared store is unchanged is the check {@link StoreFixture.Shared}
 * runs when its class is done.
 */
interface FixtureView {

    /** The graph this fixture captured under. */
    String graphName();

    /** The scoped query surface a single-query tool takes. */
    StoreHandle handle();

    /** The same store seen as another graph, for asserting one graph cannot read another's rows. */
    StoreHandle handleFor(String otherGraph);

    /** The reader a tool whose answer is several queries takes. Never closed by the caller. */
    StoreReader reader();
}
