package no.sikt.graphitron.model.capture.code.fixtures;

import org.jooq.Condition;
import org.jooq.impl.DSL;

/**
 * A stand-in for the consumer code {@code @condition(condition:)} names, compiled into this
 * module's own test output so the arm has a reactor entry to admit from.
 *
 * <p>Written rather than borrowed because the arm's cases are about what it leaves out as much as
 * what it takes, and a fixture is what lets a non-static condition, a method returning something
 * else, and a non-public one sit side by side on one class.
 */
public final class ConditionFixture {

    private ConditionFixture() {}

    /** The ordinary shape: static, returning a condition, taking the table and a value. */
    public static Condition titleContains(String table, String needle) {
        return DSL.condition(DSL.trueCondition());
    }

    /** An overload of the name above, which is what the descriptor in the key tells apart. */
    public static Condition titleContains(String table) {
        return DSL.trueCondition();
    }

    /**
     * A condition an author may name that the generator will have to refuse or construct for. Its
     * candidacy is not the arm's to deny: an author can write it, and a refusal that named no
     * method would be a worse answer than one that named this.
     */
    public Condition nonStatic(String table) {
        return DSL.trueCondition();
    }

    /** Returns something else, so the arm's one admission rule leaves it out. */
    public static String notACondition(String table) {
        return table;
    }

    /** Not public, so neither the census nor the arm sees it. */
    static Condition hidden(String table) {
        return DSL.trueCondition();
    }
}
