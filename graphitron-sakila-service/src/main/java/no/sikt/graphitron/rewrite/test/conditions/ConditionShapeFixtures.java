package no.sikt.graphitron.rewrite.test.conditions;

import no.sikt.graphitron.rewrite.test.jooq.public_.tables.Customer;
import no.sikt.graphitron.rewrite.test.jooq.public_.tables.Staff;
import org.jooq.Condition;
import org.jooq.Table;
import org.jooq.impl.DSL;

/**
 * {@code @condition} overload sets whose declarations disagree in shape, one axis per name: the
 * fact document {@code condition-overload-shape} names each and the store draws
 * {@code CONDITION_OVERLOAD_SHAPE_DISAGREES} naming the axis. Each pair disagrees on its own axis
 * and agrees on every one before it, so the axis the store names is the pair's.
 *
 * <p>The bodies are not the point and none of them is called.
 */
public final class ConditionShapeFixtures {

    private ConditionShapeFixtures() {}

    /** One table parameter. */
    public static Condition parameterCount(Table<?> table) {
        return DSL.noCondition();
    }

    /** The same with a second parameter: the counts disagree. */
    public static Condition parameterCount(Table<?> table, String title) {
        return DSL.noCondition();
    }

    /** Static. */
    public static Condition staticModifier(Customer customer) {
        return DSL.noCondition();
    }

    /** An instance method beside the static one: the modifiers disagree. */
    public Condition staticModifier(Staff staff) {
        return DSL.noCondition();
    }

    /** Returns a condition. */
    public static Condition returnType(Customer customer) {
        return DSL.noCondition();
    }

    /** Returns something else: the return types disagree. */
    public static Object returnType(Staff staff) {
        return DSL.noCondition();
    }

    /** Declares a checked exception. */
    public static Condition throwsClause(Customer customer) throws java.io.IOException {
        return DSL.noCondition();
    }

    /** Declares none: the throws clauses disagree. */
    public static Condition throwsClause(Staff staff) {
        return DSL.noCondition();
    }

    /** A table at parameter 0. */
    public static Condition tableAtPosition(Customer customer, String name) {
        return DSL.noCondition();
    }

    /** A string at parameter 0: a table in one declaration and not the other. */
    public static Condition tableAtPosition(String name, Staff staff) {
        return DSL.noCondition();
    }

    /** A string at parameter 1. */
    public static Condition typeAtPosition(Customer customer, String value) {
        return DSL.noCondition();
    }

    /** An integer at parameter 1 under the same name: the declared types disagree. */
    public static Condition typeAtPosition(Staff staff, Integer value) {
        return DSL.noCondition();
    }

    /** {@code firstName} at parameter 1. */
    public static Condition nameAtPosition(Customer customer, String firstName) {
        return DSL.noCondition();
    }

    /** {@code lastName} at parameter 1, of the same type: the names disagree. */
    public static Condition nameAtPosition(Staff staff, String lastName) {
        return DSL.noCondition();
    }

    /** One table per declaration and nothing else differing: agrees in shape. */
    public static Condition agreeing(Customer customer, String firstName) {
        return DSL.noCondition();
    }

    /** The staff declaration of {@link #agreeing(Customer, String)}. */
    public static Condition agreeing(Staff staff, String firstName) {
        return DSL.noCondition();
    }
}
