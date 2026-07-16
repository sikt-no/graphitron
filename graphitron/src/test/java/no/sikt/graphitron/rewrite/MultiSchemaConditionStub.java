package no.sikt.graphitron.rewrite;

import org.jooq.Condition;

/**
 * Condition-method stub whose table parameters are typed with the multi-schema jOOQ
 * fixture's {@code Event} classes ({@code no.sikt.graphitron.rewrite.multischemafixture}). The bare
 * name {@code event} collides across {@code multischema_a} and {@code multischema_b}, so a concrete
 * parameter typed {@code multischema_a.tables.Event} vs {@code multischema_b.tables.Event} can only
 * be told apart by jOOQ class identity, not the bare-vs-qualified name string
 * {@code checkConcreteParamTable} previously compared.
 *
 * <p>Each method wildcards the operand it is not exercising ({@code Table<?>}) so exactly one
 * positional check fires, isolating the source- vs target-side coverage the plan calls for.
 * The sibling {@link TestConditionStub} covers the single-schema (sakila) cases.
 */
class MultiSchemaConditionStub {

    /** Source parameter typed with {@code multischema_a}'s {@code Event}; target wildcarded. */
    public static Condition sourceMultiA(
            no.sikt.graphitron.rewrite.multischemafixture.multischema_a.tables.Event src,
            org.jooq.Table<?> tgt) {
        throw new UnsupportedOperationException();
    }

    /** Source parameter typed with {@code multischema_b}'s {@code Event} (wrong schema, same bare name). */
    public static Condition sourceMultiB(
            no.sikt.graphitron.rewrite.multischemafixture.multischema_b.tables.Event src,
            org.jooq.Table<?> tgt) {
        throw new UnsupportedOperationException();
    }

    /** Target parameter typed with {@code multischema_a}'s {@code Event}; source wildcarded. */
    public static Condition targetMultiA(
            org.jooq.Table<?> src,
            no.sikt.graphitron.rewrite.multischemafixture.multischema_a.tables.Event tgt) {
        throw new UnsupportedOperationException();
    }

    /** Target parameter typed with {@code multischema_b}'s {@code Event} (wrong schema, same bare name). */
    public static Condition targetMultiB(
            org.jooq.Table<?> src,
            no.sikt.graphitron.rewrite.multischemafixture.multischema_b.tables.Event tgt) {
        throw new UnsupportedOperationException();
    }
}
