package no.sikt.graphitron.rewrite.test.conditions;

import org.jooq.Condition;
import org.jooq.Table;
import org.jooq.impl.DSL;

/**
 * Condition-method fixtures for developer {@code @condition} on multitable interface/union query
 * fields.
 *
 * <p>The polymorphic branch emitter calls a {@code @condition} method once per UNION branch,
 * passing that participant's own stage-1 table local, so a multitable condition method declares its
 * first parameter as {@code Table<?>} and resolves shared columns by name ({@code first_name}
 * exists on both {@code customer} and {@code staff}). A concrete participant-table parameter would
 * instead surface a mismatched branch at this module's javac, mirroring the concrete-parameter
 * semantics.
 */
public final class MultiTableConditionFixtures {

    private MultiTableConditionFixtures() {}

    /**
     * Field-level {@code @condition} with no GraphQL arguments: restricts every branch to rows
     * whose shared {@code first_name} column starts with {@code M} (customer Mary, staff Mike).
     */
    public static Condition firstNameStartsWithM(Table<?> table) {
        return table.field(DSL.name("first_name"), String.class).like("M%");
    }

    /**
     * Arg-level {@code @condition(override: true)} on a {@code firstName} argument: prefix match
     * instead of the suppressed implicit equality, so {@code firstName: "M"} returning Mary and
     * Mike proves the developer method ran (equality on "M" would match no row).
     */
    public static Condition firstNamePrefix(Table<?> table, String firstName) {
        if (firstName == null) {
            return DSL.noCondition();
        }
        return table.field(DSL.name("first_name"), String.class).like(firstName + "%");
    }

    /**
     * Input-field {@code @condition(override: true)} on {@code OccupantFilter.namePrefix}: the
     * nested value reaches the method through the branch emitter's Map traversal.
     */
    public static Condition occupantNamePrefix(Table<?> table, String namePrefix) {
        if (namePrefix == null) {
            return DSL.noCondition();
        }
        return table.field(DSL.name("first_name"), String.class).like(namePrefix + "%");
    }
}
