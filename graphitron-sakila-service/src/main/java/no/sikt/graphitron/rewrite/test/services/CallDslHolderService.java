package no.sikt.graphitron.rewrite.test.services;

import org.jooq.DSLContext;

/** An object an instance method runs on, made with the jOOQ context; it answers without a database. */
public final class CallDslHolderService {

    private final DSLContext dsl;

    public CallDslHolderService(DSLContext dsl) {
        this.dsl = dsl;
    }

    public String greet(String name) {
        return (dsl == null ? "no context" : "hello") + " " + name;
    }
}
