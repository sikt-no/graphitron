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
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * R424: an inline (non-{@code @splitQuery}) {@code @reference} list field reads its filter arguments
 * off its own {@code SelectedField}, not the ancestor fetcher's {@code env}. Behaviour is pinned at
 * the execution tier ({@code GraphQLQueryTest}); this pipeline tier pins the structural consequence
 * that keeps the {@code -Werror} consumer compile green: the {@code $fields} host stamps
 * {@code @SuppressWarnings("unchecked")} exactly when an inline filter arg emits an unchecked cast
 * under the {@code FromSelectedField} source (a list-typed arg), and not otherwise. Asserts on the
 * generated {@link MethodSpec}'s annotations, never on body strings (banned at every tier).
 *
 * <p>Uses the {@code nodeidfixture} jOOQ catalog so the composite-key {@code Bar} NodeType
 * (PK {@code (id_1, id_2)}, FK {@code bar_id_1_fkey} → {@code baz}) is reachable from SDL, matching
 * {@link NodeIdReferenceFilterPipelineTest}.
 */
@PipelineTier
class InlineFilterArgumentSourcePipelineTest {

    private static final String FIXTURE_JOOQ_PACKAGE = "no.sikt.graphitron.rewrite.nodeidfixture";
    private static final RewriteContext FIXTURE_CTX = new RewriteContext(
        List.of(), Path.of(""), Path.of(""),
        DEFAULT_OUTPUT_PACKAGE, FIXTURE_JOOQ_PACKAGE,
        Map.of()
    );

    // A list-typed implicit column filter (bar.name IN (names)) — a GeneratedConditionFilter whose
    // call param is a list-typed Direct extraction, which casts to (List) under FromSelectedField.
    private static final String LIST_FILTER_INPUT = """
        input BarListFilter @table(name: "bar") {
            names: [String!] @field(name: "name")
        }
        """;

    // A scalar implicit column filter (bar.name = name) — a Direct extraction that casts to the
    // reifiable (String) under FromSelectedField (checked), so it needs no suppression.
    private static final String SCALAR_FILTER_INPUT = """
        input BarScalarFilter @table(name: "bar") {
            name: String @field(name: "name")
        }
        """;

    private static TypeSpec generateBazType(String extraSdl) {
        var schema = TestSchemaHelper.buildSchema(extraSdl, FIXTURE_CTX);
        return TypeClassGenerator.generate(schema, DEFAULT_OUTPUT_PACKAGE).stream()
            .filter(t -> t.name().equals("Baz")).findFirst().orElseThrow();
    }

    private static MethodSpec fieldsMethod(TypeSpec type) {
        return type.methodSpecs().stream()
            .filter(m -> m.name().equals("$fields")).findFirst().orElseThrow();
    }

    private static boolean stampsUncheckedSuppression(MethodSpec method) {
        return method.annotations().stream()
            .anyMatch(a -> a.type().toString().equals("java.lang.SuppressWarnings"));
    }

    @Test
    void inlineReferenceFilter_listArg_stampsUncheckedSuppressionOnFieldsMethod() {
        // A list-typed @condition filter arg extracts as an unchecked (List<X>) cast under the
        // FromSelectedField source, so the $fields host must carry @SuppressWarnings("unchecked").
        var baz = generateBazType(LIST_FILTER_INPUT + """
            type Bar implements Node @table(name: "bar") @node { id: ID! name: String }
            type Baz implements Node @table(name: "baz") @node {
                id: ID!
                bars(filter: BarListFilter): [Bar!] @reference(path: [{key: "bar_id_1_fkey"}])
            }
            type Query { baz: Baz }
            """);

        assertThat(stampsUncheckedSuppression(fieldsMethod(baz))).isTrue();
    }

    @Test
    void inlineReferenceFilter_scalarArgOnly_doesNotStampUncheckedSuppression() {
        // A scalar @condition filter arg casts to a reifiable type (checked), so no suppression is
        // needed — pins that the stamp is source-aware and not a blanket widening.
        var baz = generateBazType(SCALAR_FILTER_INPUT + """
            type Bar implements Node @table(name: "bar") @node { id: ID! name: String }
            type Baz implements Node @table(name: "baz") @node {
                id: ID!
                bars(filter: BarScalarFilter): [Bar!] @reference(path: [{key: "bar_id_1_fkey"}])
            }
            type Query { baz: Baz }
            """);

        assertThat(stampsUncheckedSuppression(fieldsMethod(baz))).isFalse();
    }

    @Test
    void inlineReferenceFilter_listArg_generatesEndToEndWithoutThrowing() {
        // The full classify + generate path succeeds for the inline list-filter shape.
        assertThatCode(() -> {
            var schema = TestSchemaHelper.buildSchema(LIST_FILTER_INPUT + """
                type Bar implements Node @table(name: "bar") @node { id: ID! name: String }
                type Baz implements Node @table(name: "baz") @node {
                    id: ID!
                    bars(filter: BarListFilter): [Bar!] @reference(path: [{key: "bar_id_1_fkey"}])
                }
                type Query { baz: Baz }
                """, FIXTURE_CTX);
            TypeClassGenerator.generate(schema, DEFAULT_OUTPUT_PACKAGE);
            TypeFetcherGenerator.generate(schema, DEFAULT_OUTPUT_PACKAGE);
        }).doesNotThrowAnyException();
    }
}
