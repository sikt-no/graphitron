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
     * The {@code @nodeId} override escape: no route from either participant's table to the node
     * type's table resolves, so this method owns the whole {@code WHERE} contribution and is handed
     * each branch's own table plus the <em>raw</em> wire id, undecoded.
     *
     * <p>A production author decodes that id with the generated {@code NodeIdEncoder}
     * ({@code peekTypeId} reads the discriminator without committing to a type,
     * {@code decode<Type>} returns the typed key record). A {@code @condition} class cannot: it is
     * reflected during generation, so it compiles upstream of the code the generator emits. This
     * fixture therefore treats the id as the plain integer the test supplies and filters on the
     * {@code film_id} column both {@code film} and {@code inventory} carry, which is enough to prove
     * what the escape promises: the method fires per branch, against that branch's own table, with
     * no implicit predicate of the generator's beside it.
     */
    public static Condition stockByRawNodeId(Table<?> table, String languageId) {
        if (languageId == null) {
            return DSL.noCondition();
        }
        try {
            return table.field(DSL.name("film_id"), Integer.class).eq(Integer.valueOf(languageId));
        } catch (NumberFormatException e) {
            return DSL.falseCondition();
        }
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
