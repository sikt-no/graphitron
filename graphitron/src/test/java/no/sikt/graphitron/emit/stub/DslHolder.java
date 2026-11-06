package no.sikt.graphitron.emit.stub;

import org.jooq.DSLContext;

/** An object an instance method runs on, made with the jOOQ context. */
public final class DslHolder {

    private final DSLContext dsl;

    public DslHolder(DSLContext dsl) {
        this.dsl = dsl;
    }

    public String greet(String name) {
        return (dsl == null ? "no context" : "hello") + " " + name;
    }
}
