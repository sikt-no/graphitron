package no.sikt.graphitron.rewrite.generators;

import no.sikt.graphitron.javapoet.TypeSpec;
import no.sikt.graphitron.rewrite.RewriteContext;
import no.sikt.graphitron.rewrite.TestSchemaHelper;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.Test;

import javax.lang.model.element.Modifier;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static no.sikt.graphitron.common.configuration.TestConfiguration.DEFAULT_OUTPUT_PACKAGE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * A filter input used as the {@code filter:} argument of a reference/list child field that
 * mixes {@code @nodeId}-decoded fields with {@code @condition} fields must generate without
 * throwing. The {@code @nodeId} decode is lifted into a per-class private static helper drained
 * onto the coordinate's own condition glue class ({@code <Parent>Conditions}) — since call-site
 * convergence the hosting {@code <Type>} / {@code <Type>Fetchers} classes emit only the one-line
 * glue call and carry no decode machinery, so the inline and the {@code @splitQuery} shape share
 * one helper on one class.
 *
 * <p>Part B: a condition-only filter input (no {@code @nodeId}/key fields) must also generate,
 * including the degenerate empty-join-path (same-table) reference that previously crashed with an
 * {@code Index -1 out of bounds} on an empty alias list.
 *
 * <p>Uses the {@code nodeidfixture} jOOQ catalog so the composite-key {@code Bar} NodeType
 * (PK {@code (id_1, id_2)}, FK {@code bar_id_1_fkey} → {@code baz}) is reachable from SDL.
 */
@PipelineTier
class NodeIdReferenceFilterPipelineTest {

    private static final String FIXTURE_JOOQ_PACKAGE = "no.sikt.graphitron.rewrite.nodeidfixture";
    private static final RewriteContext FIXTURE_CTX = new RewriteContext(
        List.of(), Path.of(""), Path.of(""),
        DEFAULT_OUTPUT_PACKAGE, FIXTURE_JOOQ_PACKAGE,
        Map.of()
    );

    private static final String MIXED_FILTER_INPUT = """
        input BarFilter {
            ids: [ID!] @nodeId(typeName: "Bar")
            cityNames: String @condition(condition: {className: "no.sikt.graphitron.rewrite.TestConditionStub", method: "argCondition"}, override: true)
        }
        """;

    private static final String CONDITION_ONLY_INPUT = """
        input BarFilter {
            cityNames: String @condition(condition: {className: "no.sikt.graphitron.rewrite.TestConditionStub", method: "argCondition"}, override: true)
        }
        """;

    @Test
    void inlineReferenceField_mixedNodeIdAndConditionFilter_liftsDecodeHelperOntoGlueClass() {
        var schema = TestSchemaHelper.buildSchema(MIXED_FILTER_INPUT + """
            type Bar implements Node @table(name: "bar") @node { id: ID! name: String }
            type Baz implements Node @table(name: "baz") @node {
                id: ID!
                bars(filter: BarFilter): [Bar!] @reference(path: [{key: "bar_id_1_fkey"}])
            }
            type Query { baz: Baz }
            """, FIXTURE_CTX);

        // The @nodeId decode is lifted to a per-class private static helper on the coordinate's
        // glue class; the Baz type class emits only the glue call and carries no helper.
        assertDecodeHelperOnGlueClassOnly(schema,
            TypeClassGenerator.generate(schema, DEFAULT_OUTPUT_PACKAGE).stream()
                .filter(t -> t.name().equals("Baz")).findFirst().orElseThrow());
    }

    @Test
    void splitQueryReferenceField_mixedNodeIdAndConditionFilter_liftsDecodeHelperOntoGlueClass() {
        var schema = TestSchemaHelper.buildSchema(MIXED_FILTER_INPUT + """
            type Bar implements Node @table(name: "bar") @node { id: ID! name: String }
            type Baz implements Node @table(name: "baz") @node {
                id: ID!
                bars(filter: BarFilter): [Bar!] @splitQuery @reference(path: [{key: "bar_id_1_fkey"}])
            }
            type Query { baz: Baz }
            """, FIXTURE_CTX);

        // Same landing as the inline twin: one helper on the glue class, none on the rows
        // method's host.
        assertDecodeHelperOnGlueClassOnly(schema,
            TypeFetcherGenerator.generate(schema, DEFAULT_OUTPUT_PACKAGE).stream()
                .filter(t -> t.name().equals("BazFetchers")).findFirst().orElseThrow());
    }

    private static void assertDecodeHelperOnGlueClassOnly(
            no.sikt.graphitron.rewrite.GraphitronSchema schema, TypeSpec host) {
        var bazConditions = no.sikt.graphitron.rewrite.ConditionRenderTestSupport
            .renderCommittedConditions(schema, DEFAULT_OUTPUT_PACKAGE).stream()
            .filter(t -> t.name().equals("BazConditions")).findFirst().orElseThrow();
        var helper = bazConditions.methodSpecs().stream()
            .filter(m -> m.name().startsWith("decodeBar"))
            .findFirst().orElseThrow();
        assertThat(helper.modifiers()).contains(Modifier.PRIVATE, Modifier.STATIC);
        assertThat(host.methodSpecs())
            .noneMatch(m -> m.name().startsWith("decodeBar"));
    }

    @Test
    void inlineReferenceField_conditionOnlyFilter_generatesWithRealFk() {
        // Part B regression guard: a condition-only filter on a real-FK @reference generates fine
        // (green before the Part A wiring landed; pins that adding it did not regress it).
        var schema = TestSchemaHelper.buildSchema(CONDITION_ONLY_INPUT + """
            type Bar implements Node @table(name: "bar") @node { id: ID! name: String }
            type Baz implements Node @table(name: "baz") @node {
                id: ID!
                bars(filter: BarFilter): [Bar!] @reference(path: [{key: "bar_id_1_fkey"}])
            }
            type Query { baz: Baz }
            """, FIXTURE_CTX);

        assertThatCode(() -> {
            TypeClassGenerator.generate(schema, DEFAULT_OUTPUT_PACKAGE);
            TypeFetcherGenerator.generate(schema, DEFAULT_OUTPUT_PACKAGE);
        }).doesNotThrowAnyException();
    }

    @Test
    void inlineReferenceField_conditionOnlyFilter_emptyJoinPath_standaloneSubquery() {
        // Part B: a same-table reference (start table == target table) yields an empty joinPath
        // (standalone-lookup shape, parentCorrelation == null). A condition-only filter on it must
        // emit a conditions-only subquery with no key projection rather than crashing with
        // Index -1 on the empty alias list.
        var schema = TestSchemaHelper.buildSchema(CONDITION_ONLY_INPUT + """
            type Bar implements Node @table(name: "bar") @node {
                id: ID! name: String
                related(filter: BarFilter): [Bar!]
            }
            type Query { bar: Bar }
            """, FIXTURE_CTX);

        assertThatCode(() -> TypeClassGenerator.generate(schema, DEFAULT_OUTPUT_PACKAGE))
            .doesNotThrowAnyException();
    }
}
