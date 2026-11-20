package no.sikt.graphitron.rewrite;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import no.sikt.graphitron.rewrite.model.ParamSource;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import no.sikt.graphitron.model.config.RunContext;

/**
 * Unit coverage for {@link ServiceCatalog#reflectTableMethod}'s {@code @condition} binding and the
 * binding inference it shares. Exercises the reflection path in isolation with synthetic
 * {@link TestConditionStub} methods; the classifier does not read {@code BuildContext.schema} or
 * {@code BuildContext.catalog}, so both may be {@code null} here.
 */
@UnitTier
class ServiceCatalogTest {

    private static ServiceCatalog newCatalog() {
        return new ServiceCatalog(new BuildContext(null, null, stubRunContext()));
    }

    /**
     * Minimal {@link RunContext} for unit-tier classifier tests that don't need real schema
     * inputs or output paths. The 6-arg overload defaults {@code classpathRoots} to the empty
     * list and {@code codegenLoader} to the current thread's context classloader; in a JUnit
     * JVM that's the system classloader, the same loader bare {@code Class.forName(name)}
     * resolves through.
     */
    private static RunContext stubRunContext() {
        return new RunContext(
            java.util.List.of(),
            java.nio.file.Path.of("."), "ServiceCatalogTest",
            java.nio.file.Path.of("."),
            "unused",
            "unused");
    }

    /** Test-side shorthand: wrap a raw Java-target → GraphQL-arg map as an {@link ArgBindingMap}. */
    private static ArgBindingMap bindings(Map<String, String> map) {
        var byJavaName = new java.util.LinkedHashMap<String, PathExpr>();
        map.forEach((k, v) -> byJavaName.put(k, PathExpr.head(v)));
        return new ArgBindingMap(byJavaName, java.util.Set.copyOf(map.keySet()));
    }

    @Test
    void reflectTableMethod_overrideTargetingTableSlot_rejected() {
        // An override that points the Java target at the Table<?> parameter is rejected; the
        // Table<?> slot is reserved.
        var argByJavaName = bindings(Map.of("table", "input"));
        var result = newCatalog().reflectTableMethod(
            "no.sikt.graphitron.rewrite.TestConditionStub", "argCondition", argByJavaName, Set.of());

        assertThat(result.failed()).isTrue();
        assertThat(result.rejection().message())
            .contains("argMapping entry 'table: input'")
            .contains("Table<?>");
    }

    @Test
    void reflectTableMethod_typeUniqueSignature_infersBindingWithoutArgMapping() {
        // Arg-level @condition where the Java parameter name (whatever) does not match
        // the GraphQL argument name (opptaksNavn), but the signature is type-unambiguous —
        // exactly one Table<?> parameter, exactly one String parameter, and the GraphQL slot
        // is a single String. The inference pairs them by type without requiring argMapping.
        var slot = new java.util.LinkedHashMap<String, graphql.schema.GraphQLInputType>();
        slot.put("opptaksNavn", graphql.Scalars.GraphQLString);
        var argByJavaName = bindings(Map.of("opptaksNavn", "opptaksNavn"));
        var result = newCatalog().reflectTableMethod(
            "no.sikt.graphitron.rewrite.TestConditionStub", "argConditionTypeUnique",
            argByJavaName, Set.of(), slot, new ConditionSite.Consuming("Query", "f"));

        assertThat(result.failed()).isFalse();
        var params = result.ref().params();
        assertThat(params).hasSize(2);
        assertThat(params.get(0).source()).isInstanceOf(ParamSource.Table.class);
        assertThat(params.get(1).name()).isEqualTo("whatever");
        assertThat(params.get(1).source()).isInstanceOf(ParamSource.Arg.class);
        assertThat(((ParamSource.Arg) params.get(1).source()).path().headName()).isEqualTo("opptaksNavn");
    }

    @Test
    void reflectTableMethod_typeAmbiguousSignature_fallsBackToNameMatchingDiagnostic() {
        // When more than one Java parameter shares a type with the only slot of
        // that type, the inference treats the pairing as ambiguous and falls back to
        // name-based matching. With two String parameters and one String slot, the
        // second parameter remains unbound and the existing diagnostic fires.
        var slot = new java.util.LinkedHashMap<String, graphql.schema.GraphQLInputType>();
        slot.put("first", graphql.Scalars.GraphQLString);
        var argByJavaName = bindings(Map.of("first", "first"));
        var result = newCatalog().reflectTableMethod(
            "no.sikt.graphitron.rewrite.TestConditionStub", "argConditionTwoStrings",
            argByJavaName, Set.of(), slot, new ConditionSite.Consuming("Query", "f"));

        assertThat(result.failed()).isTrue();
        assertThat(result.rejection().message())
            .contains("parameter 'second'")
            .contains("not a GraphQL argument");
    }

    // ===== name-based depth-1 nested-field inference =====

    /** Builds an input-object slot type with one field of the given GraphQL type. */
    private static graphql.schema.GraphQLInputObjectType inputObject(String name, String fieldName,
            graphql.schema.GraphQLInputType fieldType) {
        return graphql.schema.GraphQLInputObjectType.newInputObject()
            .name(name)
            .field(graphql.schema.GraphQLInputObjectField.newInputObjectField()
                .name(fieldName).type(fieldType).build())
            .build();
    }

    @Test
    void inferNestedFieldByName_singleMatchingScalarField_returnsDepth1Step() {
        // One unclaimed input-object slot with a direct field whose name AND mapped Java type
        // match the unbound parameter → the depth-1 Step a hand-written
        // argMapping: "fra: range.fra" would produce, with liftsList=false for a scalar leaf.
        var slot = new java.util.LinkedHashMap<String, graphql.schema.GraphQLInputType>();
        slot.put("range", inputObject("VerdiRange", "fra", graphql.Scalars.GraphQLInt));

        var result = newCatalog().inferNestedFieldByName(
            "fra", "java.lang.Integer", List.of("range"), slot);

        assertThat(result).isEqualTo(PathExpr.step(PathExpr.head("range"), "fra", false));
    }

    @Test
    void inferNestedFieldByName_listShapedField_computesLiftsListTrue() {
        // A [Int] field binds a List<Integer> parameter; the Step's liftsList flag is computed
        // via ArgBindingMap.isListShaped (true), byte-identical to the explicit-argMapping Step.
        var slot = new java.util.LinkedHashMap<String, graphql.schema.GraphQLInputType>();
        slot.put("range", inputObject("VerdiRange", "verdier",
            graphql.schema.GraphQLList.list(graphql.Scalars.GraphQLInt)));

        var result = newCatalog().inferNestedFieldByName(
            "verdier", "java.util.List<java.lang.Integer>", List.of("range"), slot);

        assertThat(result).isEqualTo(PathExpr.step(PathExpr.head("range"), "verdier", true));
    }

    @Test
    void inferNestedFieldByName_nameMatchesButTypeDiffers_returnsNull() {
        // The slot has a field named like the parameter, but its mapped Java type (Integer) does
        // not equal the parameter's (String) — name+type must both match, so no binding.
        var slot = new java.util.LinkedHashMap<String, graphql.schema.GraphQLInputType>();
        slot.put("range", inputObject("VerdiRange", "fra", graphql.Scalars.GraphQLInt));

        var result = newCatalog().inferNestedFieldByName(
            "fra", "java.lang.String", List.of("range"), slot);

        assertThat(result).isNull();
    }

    @Test
    void inferNestedFieldByName_twoSlotsEachWithMatchingField_returnsNullAsAmbiguous() {
        // Two unclaimed input-object slots each carry a field named (and typed) like the
        // parameter → two candidates across slots → ambiguous → null, so the per-parameter
        // rejection / suggestion still fires.
        var slot = new java.util.LinkedHashMap<String, graphql.schema.GraphQLInputType>();
        slot.put("rangeA", inputObject("VerdiRangeA", "fra", graphql.Scalars.GraphQLInt));
        slot.put("rangeB", inputObject("VerdiRangeB", "fra", graphql.Scalars.GraphQLInt));

        var result = newCatalog().inferNestedFieldByName(
            "fra", "java.lang.Integer", List.of("rangeA", "rangeB"), slot);

        assertThat(result).isNull();
    }
}
