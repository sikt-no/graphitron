package no.sikt.graphitron.rewrite.test.conditions;

import no.sikt.graphitron.rewrite.test.jooq.tables.Category;
import org.jooq.Condition;
import org.jooq.Table;
import org.jooq.impl.DSL;

/**
 * {@code @condition} method stub used by the G5 {@code ConditionJoin} schema fixture.
 *
 * <p>The classifier reflects on the two-arg signature {@code (Table<?>, Table<?>) -> Condition}
 * to build a {@link no.sikt.graphitron.rewrite.model.JoinStep.ConditionJoin}. The method body is
 * never invoked: G5 C3's emitter emits a runtime-throwing stub for any path containing a
 * {@code ConditionJoin} step, pending classification-vocabulary item 5 resolving condition-method
 * target tables.
 *
 * <p>When item 5 lands, this class expands with production-shaped {@code @condition} methods
 * exercised by real execution tests; until then the class exists solely so the schema fixture's
 * {@code @reference(path: [{condition: {…}}])} can classify without the builder rejecting.
 */
public final class CategoryConditions {

    /**
     * Unused at runtime. Holds a typed reference to a generated table so this file fails
     * to compile if the jOOQ catalog is empty or missing, rather than letting the module
     * install a fixtures jar with no table classes.
     */
    @SuppressWarnings("unused")
    private static final Table<?> CATEGORY_CATALOG_GUARD = Category.CATEGORY;

    private CategoryConditions() {}

    /**
     * Non-functional placeholder — classifier-only. Method body is never reached at runtime
     * because G5's emitter short-circuits ConditionJoin paths with a runtime-throwing stub.
     */
    public static Condition sameNamePrefix(Table<?> src, Table<?> tgt) {
        return DSL.noCondition();
    }
}
