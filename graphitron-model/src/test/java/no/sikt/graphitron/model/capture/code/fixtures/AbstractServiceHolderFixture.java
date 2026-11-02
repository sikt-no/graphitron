package no.sikt.graphitron.model.capture.code.fixtures;

import org.jooq.DSLContext;

/**
 * A service class whose methods run on an instance and which cannot be made: abstract, with a
 * public constructor taking the jOOQ context, the shape a rule reading constructors alone would
 * call a holder.
 */
public abstract class AbstractServiceHolderFixture {

    /** Public and takes the jOOQ context, and still makes no object of this class. */
    public AbstractServiceHolderFixture(DSLContext dsl) {}

    /** An instance method a @service may name. */
    public String tenant() {
        return null;
    }
}
