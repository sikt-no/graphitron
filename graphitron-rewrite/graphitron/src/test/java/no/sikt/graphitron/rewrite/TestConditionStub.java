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

    /**
     * Field-level {@code @condition} method — receives the target table plus every field argument.
     * Parameter names must match GraphQL argument names (requires the {@code -parameters} flag).
     */
    public static Condition fieldCondition(org.jooq.Table<?> table, String cityNames, String countryId) {
        throw new UnsupportedOperationException();
    }

    /**
     * Arg-level {@code @condition} method — receives the target table plus one argument value.
     * Parameter name must match the GraphQL argument name.
     */
    public static Condition argCondition(org.jooq.Table<?> table, String cityNames) {
        throw new UnsupportedOperationException();
    }

    /**
     * Arg-level {@code @condition} method with a {@code contextArguments} entry — receives the
     * target table, the arg value, then context values as trailing parameters.
     */
    public static Condition argConditionWithContext(org.jooq.Table<?> table, String cityNames, String tenantId) {
        throw new UnsupportedOperationException();
    }

    /**
     * Input-field-level {@code @condition} method — receives the target table plus the SDL input
     * field value. Used on {@code filmId} input fields in Phase 4 tests.
     */
    public static Condition inputColumnCondition(org.jooq.Table<?> table, String filmId) {
        throw new UnsupportedOperationException();
    }

    /**
     * Input-field-level {@code @condition} for a {@code @reference}-navigating field named
     * {@code languageName}.
     */
    public static Condition inputRefCondition(org.jooq.Table<?> table, String languageName) {
        throw new UnsupportedOperationException();
    }

    /**
     * Input-field-level {@code @condition} for a {@code NestingField} named {@code details}.
     */
    public static Condition inputNestingCondition(org.jooq.Table<?> table, String details) {
        throw new UnsupportedOperationException();
    }

    /**
     * Arg-level {@code @condition} method whose Java parameter name diverges from the GraphQL
     * argument name. Used with {@code argMapping: "city: cityNames"} so {@code city} binds to
     * GraphQL arg {@code cityNames} (R53 cross-axis test).
     */
    public static Condition argConditionRenamed(org.jooq.Table<?> table, String city) {
        throw new UnsupportedOperationException();
    }

    /**
     * Field-level {@code @condition} method with renamed Java parameters. Used with
     * {@code argMapping: "city: cityNames, country: countryId"} (R53 cross-axis test).
     */
    public static Condition fieldConditionRenamed(org.jooq.Table<?> table, String city, String country) {
        throw new UnsupportedOperationException();
    }

    /**
     * Input-field-level {@code @condition} method whose Java parameter name diverges from the
     * input-field name. Used with {@code argMapping: "id: filmId"} (R53 cross-axis test).
     */
    public static Condition inputFieldConditionRenamed(org.jooq.Table<?> table, String id) {
        throw new UnsupportedOperationException();
    }

    /**
     * Field-level {@code @condition} method on a field with no GraphQL arguments — receives the
     * target table only. Used by the {@code @batchKeyLifter} + {@code @condition} interaction
     * test where the carrier field is a record-parent table-bound field with no extra args.
     */
    public static Condition lifterFieldCondition(org.jooq.Table<?> table) {
        throw new UnsupportedOperationException();
    }
}
