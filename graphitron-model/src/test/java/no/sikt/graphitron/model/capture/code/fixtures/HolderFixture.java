package no.sikt.graphitron.model.capture.code.fixtures;

import org.jooq.Configuration;
import org.jooq.DSLContext;

/**
 * A stand-in for a service class whose methods run on an instance, so a generated fetcher has to
 * make one: its public constructors are the candidates a holder is chosen from, and one that is
 * not public is no candidate at all.
 */
public class HolderFixture {

    private final String tenantId;

    /** The jOOQ context alone. */
    public HolderFixture(DSLContext dsl) {
        this.tenantId = null;
    }

    /** The context and a value a context key may fill. */
    public HolderFixture(DSLContext dsl, String tenantId) {
        this.tenantId = tenantId;
    }

    /** The context in its other form. */
    public HolderFixture(Configuration configuration) {
        this.tenantId = null;
    }

    /** Not public, so not a constructor a generated fetcher can call. */
    HolderFixture() {
        this.tenantId = null;
    }

    /** An instance method a @service may name, which makes this class a holder. */
    public String tenant() {
        return tenantId;
    }
}
