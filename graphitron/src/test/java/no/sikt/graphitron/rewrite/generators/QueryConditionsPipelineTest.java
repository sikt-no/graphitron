package no.sikt.graphitron.rewrite.generators;

import no.sikt.graphitron.javapoet.MethodSpec;
import no.sikt.graphitron.javapoet.TypeSpec;
import no.sikt.graphitron.rewrite.RewriteContext;
import no.sikt.graphitron.rewrite.TestSchemaHelper;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static no.sikt.graphitron.common.configuration.TestConfiguration.DEFAULT_OUTPUT_PACKAGE;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pipeline-tier coverage for {@link QueryConditionsGenerator}'s composite-key NodeId helper
 * registry: when two distinct {@code QueryTableField}s on the same root type consume the same
 * NodeId type via {@code [ID!] @nodeId(typeName: T)}, the generator emits exactly one shared
 * {@code decode<T>RowsOrThrow} private static helper on the {@code QueryConditions} class (R378:
 * authored filters throw on a bad id), and both condition methods reference it.
 *
 * <p>Uses the {@code nodeidfixture} jOOQ catalog so the composite-key {@code Bar} NodeType
 * (PK {@code (id_1, id_2)}) is available; sakila has no composite-key NodeType usable from
 * SDL alone.
 */
@PipelineTier
class QueryConditionsPipelineTest {

    private static final String FIXTURE_JOOQ_PACKAGE = "no.sikt.graphitron.rewrite.nodeidfixture";
    private static final RewriteContext FIXTURE_CTX = new RewriteContext(
        List.of(), Path.of(""), Path.of(""),
        DEFAULT_OUTPUT_PACKAGE, FIXTURE_JOOQ_PACKAGE,
        Map.of()
    );

    @Test
    void twoQueryFields_sharingNodeIdType_emitOneSharedHelper() {
        var schema = TestSchemaHelper.buildSchema("""
            type Bar implements Node @table(name: "bar") @node { id: ID! }
            input BarFilter @table(name: "bar") {
                ids: [ID!] @nodeId(typeName: "Bar")
            }
            type Query {
                barsPrimary(filter: BarFilter): [Bar!]!
                barsSecondary(filter: BarFilter): [Bar!]!
            }
            """, FIXTURE_CTX);

        var classes = QueryConditionsGenerator.generate(schema, DEFAULT_OUTPUT_PACKAGE);
        var queryConditions = classes.stream()
            .filter(t -> t.name().equals("QueryConditions")).findFirst().orElseThrow();

        // Exactly one helper, deduplicated across both call sites. R378: an authored input-field
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
        // R114 pipeline-tier emitter check: a 2-hop @reference path on @nodeId that satisfies
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

        var classes = QueryConditionsGenerator.generate(schema, DEFAULT_OUTPUT_PACKAGE);
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
            input BarListFilter @table(name: "bar") {
                ids: [ID!] @nodeId(typeName: "Bar")
            }
            input BarScalarFilter @table(name: "bar") {
                id: ID @nodeId(typeName: "Bar")
            }
            type Query {
                barsByIds(filter: BarListFilter): [Bar!]!
                barById(filter: BarScalarFilter): [Bar!]!
            }
            """, FIXTURE_CTX);

        var classes = QueryConditionsGenerator.generate(schema, DEFAULT_OUTPUT_PACKAGE);
        var queryConditions = classes.stream()
            .filter(t -> t.name().equals("QueryConditions")).findFirst().orElseThrow();

        // R378: both authored input-field @nodeId filters classify to ThrowOnMismatch, so both
        // helpers are the throwing form (scalar `Row`, list `Rows`).
        var helperNames = queryConditions.methodSpecs().stream()
            .map(MethodSpec::name)
            .filter(n -> n.startsWith("decodeBar"))
            .toList();
        assertThat(helperNames).containsExactlyInAnyOrder("decodeBarRowOrThrow", "decodeBarRowsOrThrow");
    }

    private static String bodyOf(TypeSpec spec, String methodName) {
        return spec.methodSpecs().stream()
            .filter(m -> m.name().equals(methodName)).findFirst().orElseThrow()
            .code().toString();
    }
}
