package no.sikt.graphql;

import org.jooq.DSLContext;

public class GraphitronContext {
    private final DSLContext ctx;

    public GraphitronContext(DSLContext ctx) {
        this.ctx = ctx;
    }

    public DSLContext getDslContext() {
        return ctx;
    }
}
