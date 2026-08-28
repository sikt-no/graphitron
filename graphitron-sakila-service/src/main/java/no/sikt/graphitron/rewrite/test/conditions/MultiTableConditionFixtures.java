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
     * each branch's own table plus the leaf's <em>decoded</em> key.
     *
     * <p>What the author owns here is the predicate, not the wire format. The generated glue decodes
     * before the value reaches this method, which is what makes the parameter an {@code Integer}:
     * {@code LanguageNode}'s key is the single column {@code language.language_id}. A malformed id,
     * or one encoded for another node type, fails the request before this body runs, so there is no
     * parse to guard. That matters because a {@code @condition} class is reflected during generation
     * and compiles upstream of the code the generator emits, so it could never call
     * {@code NodeIdEncoder} itself.
     *
     * <p>The body filters on the {@code film_id} column both {@code film} and {@code inventory}
     * carry, which is a different column on each branch, so a method handed the wrong table returns
     * the wrong rows. That is what the escape promises and what this proves: the method fires per
     * branch, against that branch's own table, with no implicit predicate of the generator's beside
     * it.
     */
    public static Condition stockByDecodedLanguageKey(Table<?> table, Integer languageId) {
        if (languageId == null) {
            return DSL.noCondition();
        }
        return table.field(DSL.name("film_id"), Integer.class).eq(languageId);
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

    /**
     * The per-participant form: one declaration per participant table, differing only in that table
     * parameter. The branch emitter's glue is typed from the coordinate, so each branch's call site
     * is {@code occupantTypedNamePrefix(<Customer|Staff> alias, prefix)} and this module's javac
     * performs the selection. The point of writing it this way is the body: {@code customer.FIRST_NAME}
     * is jOOQ's generated, typed, alias-bearing column handle, where the {@code Table<?>} form has to
     * resolve the same column by name and give the typing up.
     */
    public static Condition occupantTypedNamePrefix(
            no.sikt.graphitron.rewrite.test.jooq.tables.Customer customer, String firstName) {
        if (firstName == null) {
            return DSL.noCondition();
        }
        return customer.FIRST_NAME.like(firstName + "%");
    }

    /** The {@code staff} branch of {@link #occupantTypedNamePrefix}, named for its own table. */
    public static Condition occupantTypedNamePrefix(
            no.sikt.graphitron.rewrite.test.jooq.tables.Staff ansatt, String firstName) {
        if (firstName == null) {
            return DSL.noCondition();
        }
        return ansatt.FIRST_NAME.like(firstName + "%");
    }

    /**
     * The mixed set, and the trade-off it makes. One concrete declaration covers {@code customer};
     * the {@code Table<?>} declaration beside it serves every branch no concrete declaration covers,
     * {@code staff} here, by javac's most-specific rule. That is what makes a mixed set compile: it
     * also means a partial concrete set with a fallback has no compile-time partial-coverage guard,
     * so a mistyped participant table silently falls through to the fallback. The author opts into
     * that by writing the fallback declaration.
     */
    public static Condition occupantMixedNamePrefix(
            no.sikt.graphitron.rewrite.test.jooq.tables.Customer customer, String firstName) {
        if (firstName == null) {
            return DSL.noCondition();
        }
        return customer.FIRST_NAME.like(firstName + "%");
    }

    /** The fallback declaration of {@link #occupantMixedNamePrefix}: every uncovered branch. */
    public static Condition occupantMixedNamePrefix(Table<?> table, String firstName) {
        if (firstName == null) {
            return DSL.noCondition();
        }
        return table.field(DSL.name("first_name"), String.class).like(firstName + "%");
    }
}
