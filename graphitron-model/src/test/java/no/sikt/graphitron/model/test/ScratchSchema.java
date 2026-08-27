package no.sikt.graphitron.model.test;

import no.sikt.graphitron.model.boot.GraphitronModelStore;
import org.jooq.DSLContext;

/**
 * A private store whose schema the case may add its own relations to: the population for a test
 * whose subject is what the engine does with a <em>definition</em>, rather than what a relation
 * answers given rows.
 *
 * <p>The fifth shape, and it is here because the other four cannot express it. Seeding, writing,
 * capturing and building all populate the fact schema; a case about how a stored definition is
 * parsed needs bodies that are not in the fact schema and never will be, small enough that the
 * expected answer is visible in the three lines that produced it. Asserting that against a real
 * relation would pin the case to that relation's current spelling, so a rewrite changing nothing
 * about the subject would fail it.
 *
 * <p><b>Private, and it has to be.</b> A relation added here is a relation the catalog holds, and
 * the fact schema's own gates enumerate what the catalog holds. The store one test thread shares is
 * cleared between bodies by emptying base tables, which puts rows back but cannot take a view away,
 * so a scratch relation created in a shared store would outlive its case and be visible to every
 * census after it. That is why this boots rather than joining the funnel, and it is the reason a
 * case here should hold one of these open for its whole class rather than one per body: a schema
 * boot is the most expensive thing a fact-store test can do, and this module counts them.
 *
 * <p>Hands out a handle rather than taking a closure so a class can open one in {@code @BeforeAll},
 * declare its bodies once and let every case read them back.
 */
public final class ScratchSchema implements AutoCloseable {

    private final GraphitronModelStore store;

    private ScratchSchema(GraphitronModelStore store) {
        this.store = store;
    }

    /** A fresh private store with the fact schema in it, ready to take relations of its own. */
    public static ScratchSchema open() {
        return new ScratchSchema(FactStores.inMemory());
    }

    /** The store's own context, for reading the catalog back and for the walk under test. */
    public DSLContext dsl() {
        return store.dsl();
    }

    /**
     * Runs one DDL statement against the scratch store, which is how a case states the body it is
     * about. Spelled as a statement rather than as a named factory on purpose: the subject is the
     * SQL, and a factory that assembled it would put its own spelling between the case and the
     * thing the case asserts about.
     */
    public void define(String ddl) {
        store.dsl().execute(ddl);
    }

    @Override
    public void close() {
        store.close();
    }
}
