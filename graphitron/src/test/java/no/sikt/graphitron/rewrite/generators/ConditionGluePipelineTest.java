package no.sikt.graphitron.rewrite.generators;

import no.sikt.graphitron.javapoet.MethodSpec;
import no.sikt.graphitron.javapoet.TypeSpec;
import no.sikt.graphitron.rewrite.RewriteContext;
import no.sikt.graphitron.rewrite.TestSchemaHelper;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static no.sikt.graphitron.common.configuration.TestConfiguration.DEFAULT_OUTPUT_PACKAGE;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pipeline-tier coverage for the condition glue's composite-key NodeId helper registry
 * ({@link no.sikt.graphitron.render.ConditionGlueRenderer} over the produced rows): when two
 * distinct {@code QueryTableField}s on the same root type consume the same
 * NodeId type via {@code [ID!] @nodeId(typeName: T)}, the renderer emits exactly one shared
 * {@code decode<T>RowsOrThrow} private static helper on the {@code QueryConditions} class (authored
 * filters throw on a bad id), and both condition methods reference it.
 *
 * <p>The class also covers a second shape: a remotely-bound {@code @nodeId} carrier whose
 * predicate lives inside a correlated {@code EXISTS} rather than on a lifted local tuple, at
 * both key arities. There the claim is that a decode arm fired at all, read off the helper set
 * the class carries.
 *
 * <p>Uses the {@code nodeidfixture} jOOQ catalog so the composite-key {@code Bar} NodeType
 * (PK {@code (id_1, id_2)}) is available; sakila has no composite-key NodeType usable from
 * SDL alone. The junction-chain test below is the exception and runs against sakila, because
 * that is where a junction table lives.
 */
@PipelineTier
class ConditionGluePipelineTest {

    private static final String FIXTURE_JOOQ_PACKAGE = "no.sikt.graphitron.rewrite.nodeidfixture";
    private static final RewriteContext FIXTURE_CTX = new RewriteContext(
        List.of(), Path.of(""), "ConditionGluePipelineTest", Path.of(""),
        DEFAULT_OUTPUT_PACKAGE, FIXTURE_JOOQ_PACKAGE
    );

    @Test
    void twoQueryFields_sharingNodeIdType_emitOneSharedHelper() {
        var schema = TestSchemaHelper.buildSchema("""
            type Bar implements Node @table(name: "bar") @node { id: ID! }
            input BarFilter {
                ids: [ID!] @nodeId(typeName: "Bar")
            }
            type Query {
                barsPrimary(filter: BarFilter): [Bar!]!
                barsSecondary(filter: BarFilter): [Bar!]!
            }
            """, FIXTURE_CTX);

        var classes = no.sikt.graphitron.rewrite.ConditionRenderTestSupport.renderCommittedConditions(schema, DEFAULT_OUTPUT_PACKAGE);
        var queryConditions = classes.stream()
            .filter(t -> t.name().equals("QueryConditions")).findFirst().orElseThrow();

        // Exactly one helper, deduplicated across both call sites. An authored input-field
        // [ID!] @nodeId filter classifies to ThrowOnMismatch, so the shared helper is the throwing
        // form (`…OrThrow`); a bad filter id surfaces an error rather than dropping silently.
        var helpers = queryConditions.methodSpecs().stream()
            .filter(m -> m.name().equals("decodeBarRowsOrThrow"))
            .toList();
        assertThat(helpers).hasSize(1);
        assertThat(helpers.get(0).modifiers()).contains(javax.lang.model.element.Modifier.PRIVATE,
            javax.lang.model.element.Modifier.STATIC);
        // The skip-form helper must not be emitted for an authored filter.
        assertThat(queryConditions.methodSpecs()).extracting(MethodSpec::name)
            .doesNotContain("decodeBarRows");

        // Both condition methods invoke the shared throwing helper.
        var primaryBody = bodyOf(queryConditions, "barsPrimaryCondition");
        var secondaryBody = bodyOf(queryConditions, "barsSecondaryCondition");
        assertThat(primaryBody).contains("decodeBarRowsOrThrow(");
        assertThat(secondaryBody).contains("decodeBarRowsOrThrow(");
    }

    @Test
    void multiHopIdentityCarryingLift_emitsHelperOnLiftedTuple() {
        // Pipeline-tier emitter check: a 2-hop @reference path on @nodeId that satisfies
        // the lift predicate produces a generated `<Type>Conditions` method that takes the
        // decoded record list as input. The structural shape (helper exists with the expected
        // signature) is what differentiates the lift case from a hypothetical EXISTS-subquery
        // follow-on; the lifted-tuple identity itself is asserted at the L3 carrier-level test
        // (NodeIdPipelineTest.MULTI_HOP_IDENTITY_CARRYING) and the per-row execution at L6
        // (GraphQLQueryTest.multiHopReferenceFilter_returnsRows).
        var schema = TestSchemaHelper.buildSchema("""
            type LevelA implements Node @table(name: "level_a") @node { id: ID! }
            type LevelC @table(name: "level_c") {
                cId: String! @field(name: "c")
            }
            type Query {
                levelCsByLevelA(
                    levelAIds: [ID!]! @nodeId(typeName: "LevelA") @reference(path: [
                        {key: "level_c_level_b_fk"},
                        {key: "level_b_level_a_fk"}
                    ])
                ): [LevelC!]!
            }
            """, FIXTURE_CTX);

        var classes = no.sikt.graphitron.rewrite.ConditionRenderTestSupport.renderCommittedConditions(schema, DEFAULT_OUTPUT_PACKAGE);
        var queryConditions = classes.stream()
            .filter(t -> t.name().equals("QueryConditions")).findFirst().orElseThrow();

        var conditionMethod = queryConditions.methodSpecs().stream()
            .filter(m -> m.name().equals("levelCsByLevelACondition"))
            .findFirst().orElseThrow();
        // Method exists. The condition method's body wraps the helper call against the lifted
        // tuple; per the test-tier rules code-string assertions on bodies are banned, so the
        // emitter shape is locked at the L3 BodyParam-level test and the L6 execution test.
        assertThat(conditionMethod.parameters()).isNotEmpty();
    }

    @Test
    void twoQueryFields_oneScalarOneList_emitDistinctHelpers() {
        // Same NodeId type but different list axis → registry key differs → two helpers.
        var schema = TestSchemaHelper.buildSchema("""
            type Bar implements Node @table(name: "bar") @node { id: ID! }
            input BarListFilter {
                ids: [ID!] @nodeId(typeName: "Bar")
            }
            input BarScalarFilter {
                id: ID @nodeId(typeName: "Bar")
            }
            type Query {
                barsByIds(filter: BarListFilter): [Bar!]!
                barById(filter: BarScalarFilter): [Bar!]!
            }
            """, FIXTURE_CTX);

        var classes = no.sikt.graphitron.rewrite.ConditionRenderTestSupport.renderCommittedConditions(schema, DEFAULT_OUTPUT_PACKAGE);
        var queryConditions = classes.stream()
            .filter(t -> t.name().equals("QueryConditions")).findFirst().orElseThrow();

        // Both authored input-field @nodeId filters classify to ThrowOnMismatch, so both
        // helpers are the throwing form (scalar `Row`, list `Rows`).
        var helperNames = queryConditions.methodSpecs().stream()
            .map(MethodSpec::name)
            .filter(n -> n.startsWith("decodeBar"))
            .toList();
        assertThat(helperNames).containsExactlyInAnyOrder("decodeBarRowOrThrow", "decodeBarRowsOrThrow");
    }

    // ===== Remotely-bound carriers: the decode arm fires inside the correlated EXISTS =====
    //
    // Both tests below assert which decode helpers the rendered class carries, and nothing about
    // any method body. That is a total statement about which renderer arm ran, because helper
    // emission is call-driven: CompositeDecodeHelperRegistry.register is reached from one site in
    // ConditionGlueRenderer (its decodeCall helper), itself reached only from the two
    // NodeIdDecodeKeys arms, and the drain adds nothing else. So for a fixture whose only
    // @nodeId carrier is the coordinate under test, the class carries a decode helper if and only
    // if a decode arm fired for that binding. The arm's alternative, the fall-through that casts
    // the raw wire traversal to the binding's local type, registers nothing and is therefore
    // visible here as an empty helper set rather than as a per-request ClassCastException
    // (https://github.com/sikt-no/graphitron/issues/536).
    //
    // Asserting the whole decode set rather than mere presence is what keeps that biconditional
    // honest: a fixture that grew a second carrier would fail here rather than quietly weaken the
    // claim. The helper's returnType() pins the type the local is declared against, which is the
    // axis (Keys vs Rows, list vs scalar) CompositeDecodeHelperRegistry.helperName reads.

    @Test
    void junctionChainInputField_bindingRemotely_emitsItsDecodeInTheGlue() {
        // The input-field twin of the junction chain: film -> film_category -> category, whose
        // classification NodeIdPipelineTest.junctionChain_inputField_bindsRemotelyOverTwoHops pins
        // over the same SDL, and whose end-to-end read
        // TranslatedFkTargetFilterExecutionTest.junctionChain_inputFieldForm_returnsTheSameRows
        // runs against PostgreSQL. This is the tier between them: the glue the renderer emits.
        //
        // Runs against the sakila catalog rather than nodeidfixture, because that is where the
        // junction table lives: film_category is the child of both film and category.
        var schema = TestSchemaHelper.buildSchema("""
            type Category implements Node @table(name: "category") @node { id: ID! }
            type Film @table(name: "film") { title: String! }
            input FilmFilterInput {
                categoryIds: [ID!] @nodeId(typeName: "Category") @reference(path: [
                    {key: "film_category_film_id_fkey"},
                    {key: "film_category_category_id_fkey"}
                ])
            }
            type Query { films(in: FilmFilterInput): [Film!] }
            """);

        assertThat(schema.diagnostics()).isEmpty();
        var queryConditions = renderQueryConditions(schema);

        assertThat(queryConditions.methodSpecs()).extracting(MethodSpec::name)
            .as("the coordinate's own glue method")
            .contains("filmsCondition");
        assertDecodeHelper(queryConditions, "decodeCategoryKeysOrThrow", "java.util.List<java.lang.Integer>");
    }

    @Test
    void multiHopTranslatingChainInputField_bindingRemotely_emitsItsCompositeDecodeInTheGlue() {
        // The composite-key arity of the same shape, and the input-field twin of the arg-side
        // resolution NodeIdLeafResolverTest.multiHopTranslatingChain_bindsRemotely pins: the
        // lift_fail_c -> lift_fail_b -> lift_fail_a chain lands no key position on lift_fail_c,
        // so LiftFailA's two-column key (k1, k2) binds inside a correlated EXISTS with a Row2 in
        // place of a scalar IN.
        var schema = TestSchemaHelper.buildSchema("""
            type LiftFailA implements Node @table(name: "lift_fail_a") @node { id: ID! }
            type LiftFailC @table(name: "lift_fail_c") {
                cId: String! @field(name: "c_id")
            }
            input LiftFailCFilter {
                aIds: [ID!] @nodeId(typeName: "LiftFailA") @reference(path: [
                    {key: "lift_fail_c_b_fk"},
                    {key: "lift_fail_b_a_fk"}
                ])
            }
            type Query { liftFailCs(in: LiftFailCFilter): [LiftFailC!] }
            """, FIXTURE_CTX);

        assertThat(schema.diagnostics()).isEmpty();
        var queryConditions = renderQueryConditions(schema);

        assertThat(queryConditions.methodSpecs()).extracting(MethodSpec::name)
            .as("the coordinate's own glue method")
            .contains("liftFailCsCondition");
        assertDecodeHelper(queryConditions, "decodeLiftFailARowsOrThrow",
            "java.util.List<org.jooq.Row2<java.lang.String, java.lang.String>>");
    }

    /**
     * Asserts that the rendered class carries exactly one {@code decode}-prefixed helper, that it
     * is the expected one, and that it has the drain contract's modifiers and the decoded return
     * type the local is declared against.
     */
    private static void assertDecodeHelper(TypeSpec queryConditions, String expectedName, String expectedReturnType) {
        var decodeHelpers = queryConditions.methodSpecs().stream()
            .filter(m -> m.name().startsWith("decode"))
            .toList();
        assertThat(decodeHelpers).extracting(MethodSpec::name)
            .as("a decode arm fired for the carrier, and nothing else registered a helper")
            .containsExactly(expectedName);
        assertThat(decodeHelpers.get(0).modifiers())
            .as("the drain contract")
            .contains(javax.lang.model.element.Modifier.PRIVATE, javax.lang.model.element.Modifier.STATIC);
        assertThat(decodeHelpers.get(0).returnType().toString())
            .as("the decoded type, the axis the helper name is derived from")
            .isEqualTo(expectedReturnType);
    }

    private static TypeSpec renderQueryConditions(no.sikt.graphitron.rewrite.GraphitronSchema schema) {
        return no.sikt.graphitron.rewrite.ConditionRenderTestSupport
            .renderCommittedConditions(schema, DEFAULT_OUTPUT_PACKAGE).stream()
            .filter(t -> t.name().equals("QueryConditions")).findFirst().orElseThrow();
    }

    private static String bodyOf(TypeSpec spec, String methodName) {
        return spec.methodSpecs().stream()
            .filter(m -> m.name().equals(methodName)).findFirst().orElseThrow()
            .code().toString();
    }
}
