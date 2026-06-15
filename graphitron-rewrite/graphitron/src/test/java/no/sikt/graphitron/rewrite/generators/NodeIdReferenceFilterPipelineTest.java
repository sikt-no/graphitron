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
 * R312: a filter input used as the {@code filter:} argument of a reference/list child field that
 * mixes {@code @nodeId}-decoded fields with {@code @condition} fields must generate without
 * throwing. The {@code @nodeId} decode is lifted into a per-class private static helper drained
 * onto the class hosting the filter call site ({@code <Type>} for inline {@code @reference}
 * fields, {@code <Type>Fetchers} for {@code @splitQuery} fields), the same own-and-drain lifecycle
 * {@link QueryConditionsGenerator} uses for root query fields.
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
        input BarFilter @table(name: "bar") {
            ids: [ID!] @nodeId(typeName: "Bar")
            cityNames: String @condition(condition: {className: "no.sikt.graphitron.rewrite.TestConditionStub", method: "argCondition"}, override: true)
        }
        """;

    private static final String CONDITION_ONLY_INPUT = """
        input BarFilter @table(name: "bar") {
            cityNames: String @condition(condition: {className: "no.sikt.graphitron.rewrite.TestConditionStub", method: "argCondition"}, override: true)
        }
        """;

    @Test
    void inlineReferenceField_mixedNodeIdAndConditionFilter_liftsDecodeHelperOntoTypeClass() {
        var schema = TestSchemaHelper.buildSchema(MIXED_FILTER_INPUT + """
            type Bar implements Node @table(name: "bar") @node { id: ID! name: String }
            type Baz implements Node @table(name: "baz") @node {
                id: ID!
                bars(filter: BarFilter): [Bar!] @reference(path: [{key: "bar_id_1_fkey"}])
            }
            type Query { baz: Baz }
            """, FIXTURE_CTX);

        var classes = TypeClassGenerator.generate(schema, DEFAULT_OUTPUT_PACKAGE);
        var baz = classes.stream().filter(t -> t.name().equals("Baz")).findFirst().orElseThrow();

        // The @nodeId decode is lifted to a per-class private static helper on the Baz type class,
        // alongside its $fields method.
        var helper = baz.methodSpecs().stream()
            .filter(m -> m.name().startsWith("decodeBar"))
            .findFirst().orElseThrow();
        assertThat(helper.modifiers()).contains(Modifier.PRIVATE, Modifier.STATIC);
    }

    @Test
    void splitQueryReferenceField_mixedNodeIdAndConditionFilter_liftsDecodeHelperOntoFetchersClass() {
        var schema = TestSchemaHelper.buildSchema(MIXED_FILTER_INPUT + """
            type Bar implements Node @table(name: "bar") @node { id: ID! name: String }
            type Baz implements Node @table(name: "baz") @node {
                id: ID!
                bars(filter: BarFilter): [Bar!] @splitQuery @reference(path: [{key: "bar_id_1_fkey"}])
            }
            type Query { baz: Baz }
            """, FIXTURE_CTX);

        var bazFetchers = TypeFetcherGenerator.generate(schema, DEFAULT_OUTPUT_PACKAGE).stream()
            .filter(t -> t.name().equals("BazFetchers")).findFirst().orElseThrow();

        var helper = bazFetchers.methodSpecs().stream()
            .filter(m -> m.name().startsWith("decodeBar"))
            .findFirst().orElseThrow();
        assertThat(helper.modifiers()).contains(Modifier.PRIVATE, Modifier.STATIC);
    }

    @Test
    void inlineReferenceField_conditionOnlyFilter_generatesWithRealFk() {
        // Part B regression guard: a condition-only filter on a real-FK @reference generates fine
        // (green before R312; pins that the Part A wiring did not regress it).
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
