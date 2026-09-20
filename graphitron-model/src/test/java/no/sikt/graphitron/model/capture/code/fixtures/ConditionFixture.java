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

    /** A position written as the bare interface: it takes whatever table the site supplies. */
    public static Condition onAnyTable(org.jooq.Table<?> table, String needle) {
        return DSL.trueCondition();
    }

    /**
     * A position written as one generated table, which names that table and no other. Declares a
     * checked exception too, the clause being what same-named declarations must agree on before
     * they are one target.
     */
    public static Condition onOneTable(ExternalFieldFixture.FixtureTable table,
                                       java.util.Map<String, Object> filter)
            throws java.io.IOException {
        return DSL.trueCondition();
    }

    /**
     * A value position typed as an enum, which decides how a bound value is coerced into it. The
     * enum is a nested one, so the reading skips it by name and no row describes it: only a loader
     * can answer, which is the same reach a generated jOOQ enum needs.
     */
    public static Condition onAnEnum(org.jooq.Table<?> table,
                                     no.sikt.graphitron.model.config.ClasspathEntry.Origin origin) {
        return DSL.trueCondition();
    }

    /** An array of that enum, which is not an enum: the component is a step down, not the type. */
    public static Condition onAnEnumArray(
            org.jooq.Table<?> table,
            no.sikt.graphitron.model.config.ClasspathEntry.Origin[] origins) {
        return DSL.trueCondition();
    }

    /** A position written as a type variable, whose erasure is a bound the source never wrote. */
    public static <T extends org.jooq.Table<?>> Condition onAVariableTable(T table) {
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
