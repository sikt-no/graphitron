package no.sikt.graphitron.rewrite;

import org.jooq.Condition;

/**
 * Minimal condition-method stub used by {@link GraphitronSchemaBuilderTest} to test that
 * {@code @reference(path: [{condition: {…}}])} is correctly classified as a {@link
 * no.sikt.graphitron.rewrite.model.JoinStep.ConditionJoin}.
 *
 * <p>Condition methods take two {@code Table<?>} parameters (source and target) and return a
 * {@link Condition}. Both parameters are classified as {@link
 * no.sikt.graphitron.rewrite.model.ParamSource.Table} by
 * {@link ServiceCatalog#reflectTableMethod}.
 */
class TestConditionStub {

    public static Condition join(org.jooq.Table<?> src, org.jooq.Table<?> tgt) {
        throw new UnsupportedOperationException();
    }
}
