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
 * {@code decode<T>Rows} private static helper on the {@code QueryConditions} class, and both
 * condition methods reference it.
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

        // Exactly one helper, deduplicated across both call sites.
        var helpers = queryConditions.methodSpecs().stream()
            .filter(m -> m.name().equals("decodeBarRows"))
            .toList();
        assertThat(helpers).hasSize(1);
        assertThat(helpers.get(0).modifiers()).contains(javax.lang.model.element.Modifier.PRIVATE,
            javax.lang.model.element.Modifier.STATIC);

        // Both condition methods invoke the shared helper.
        var primaryBody = bodyOf(queryConditions, "barsPrimaryCondition");
        var secondaryBody = bodyOf(queryConditions, "barsSecondaryCondition");
        assertThat(primaryBody).contains("decodeBarRows(");
        assertThat(secondaryBody).contains("decodeBarRows(");
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

        var helperNames = queryConditions.methodSpecs().stream()
            .map(MethodSpec::name)
            .filter(n -> n.startsWith("decodeBar"))
            .toList();
        assertThat(helperNames).containsExactlyInAnyOrder("decodeBarRow", "decodeBarRows");
    }

    private static String bodyOf(TypeSpec spec, String methodName) {
        return spec.methodSpecs().stream()
            .filter(m -> m.name().equals(methodName)).findFirst().orElseThrow()
            .code().toString();
    }
}
