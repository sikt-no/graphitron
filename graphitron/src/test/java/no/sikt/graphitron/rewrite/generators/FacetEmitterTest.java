package no.sikt.graphitron.rewrite.generators;

import no.sikt.graphitron.javapoet.TypeSpec;
import no.sikt.graphitron.rewrite.GraphitronSchema;
import no.sikt.graphitron.rewrite.TestSchemaHelper;
import no.sikt.graphitron.rewrite.generators.util.ConnectionFetcherClassGenerator;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.junit.jupiter.api.Test;

import java.util.List;

import static no.sikt.graphitron.common.configuration.TestConfiguration.DEFAULT_OUTPUT_PACKAGE;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * R13 Phase 4: structural coverage for the facet emitters. Drives the full pipeline over a
 * faceted {@code @asConnection} schema and asserts on the emitted TypeSpecs:
 *
 * <ul>
 *   <li>{@link QueryConditionsGenerator} emits the filter-minus-self fragments
 *       ({@code <field>FacetBaseCondition} suppressing the facet params with {@code null}
 *       literals, {@code <field>Facet_<g>Condition} retaining only that facet's own);</li>
 *   <li>{@link TypeFetcherGenerator} builds the facet plan onto the facet-carrying
 *       {@code ConnectionResult} constructor, and stays byte-free of facet code on an
 *       unfaceted schema (the structural no-op criterion);</li>
 *   <li>{@link ConnectionFetcherClassGenerator} adds the {@code facets} delegate under the
 *       same has-facets gate as the registration emitter.</li>
 * </ul>
 */
@UnitTier
class FacetEmitterTest {

    private static final String FACETED = """
        type Film @table(name: "film") { title: String }
        input FilmFilter @table(name: "film") {
            title: [String!] @field(name: "title") @asFacet
            length: [Int] @field(name: "length") @asFacet
            releaseYear: [Int!] @field(name: "release_year")
        }
        type Query {
            films(filter: FilmFilter): [Film!]! @asConnection @defaultOrder(primaryKey: true)
        }
        """;

    private static final String UNFACETED = """
        type Film @table(name: "film") { title: String }
        input FilmFilter @table(name: "film") {
            title: [String!] @field(name: "title")
        }
        type Query {
            films(filter: FilmFilter): [Film!]! @asConnection @defaultOrder(primaryKey: true)
        }
        """;

    @Test
    void queryConditions_emitFacetFragments() {
        var conditions = queryConditions(TestSchemaHelper.buildSchema(FACETED));

        String base = method(conditions, "filmsFacetBaseCondition");
        // The base suppresses both facet params with null literals but keeps the non-facet one.
        assertThat(base).contains("null");
        assertThat(base).contains("releaseYear");

        String titleOwn = method(conditions, "filmsFacet_titleCondition");
        // The per-facet fragment retains only its own param; every other slot is a null literal.
        assertThat(titleOwn).contains("title");
        assertThat(titleOwn).doesNotContain("releaseYear\")");

        assertThat(methodNames(conditions)).contains(
            "filmsCondition", "filmsFacetBaseCondition",
            "filmsFacet_titleCondition", "filmsFacet_lengthCondition");
    }

    @Test
    void queryConditions_unfacetedSchema_hasNoFragments() {
        var conditions = queryConditions(TestSchemaHelper.buildSchema(UNFACETED));
        assertThat(methodNames(conditions))
            .contains("filmsCondition")
            .noneMatch(n -> n.contains("Facet"));
    }

    @Test
    void connectionFetcher_buildsFacetPlan() {
        var schema = TestSchemaHelper.buildSchema(FACETED);
        var query = TypeFetcherGenerator.generate(schema, DEFAULT_OUTPUT_PACKAGE).stream()
            .filter(t -> t.name().equals("QueryFetchers"))
            .findFirst().orElseThrow();
        String films = method(query, "films");
        assertThat(films).contains("filmsFacetBaseCondition");
        assertThat(films).contains("facetConditions.put(\"title\"");
        assertThat(films).contains("facetConditions.put(\"length\"");
        assertThat(films).contains("ConnectionResult.FacetSpec(\"title\", \"title\", false)");
        assertThat(films).contains("ConnectionResult.FacetSpec(\"length\", \"length\", true)");
        assertThat(films).contains("facetBase, facetConditions, facetSpecs");
    }

    @Test
    void connectionFetcher_unfacetedSchema_isFacetFree() {
        var schema = TestSchemaHelper.buildSchema(UNFACETED);
        var query = TypeFetcherGenerator.generate(schema, DEFAULT_OUTPUT_PACKAGE).stream()
            .filter(t -> t.name().equals("QueryFetchers"))
            .findFirst().orElseThrow();
        String films = method(query, "films");
        assertThat(films).doesNotContain("Facet");
        assertThat(films).contains(".ConnectionResult(result, page,");
    }

    @Test
    void connectionFetchersClass_hasFacetsDelegateOnlyWhenFaceted() {
        var faceted = ConnectionFetcherClassGenerator
            .generate(TestSchemaHelper.buildSchema(FACETED), DEFAULT_OUTPUT_PACKAGE).stream()
            .filter(t -> t.name().equals("QueryFilmsConnectionFetchers"))
            .findFirst().orElseThrow();
        assertThat(methodNames(faceted)).contains("facets");
        assertThat(method(faceted, "facets")).contains("ConnectionHelper.facets(env)");

        var unfaceted = ConnectionFetcherClassGenerator
            .generate(TestSchemaHelper.buildSchema(UNFACETED), DEFAULT_OUTPUT_PACKAGE).stream()
            .filter(t -> t.name().equals("QueryFilmsConnectionFetchers"))
            .findFirst().orElseThrow();
        assertThat(methodNames(unfaceted)).doesNotContain("facets");
    }

    private static TypeSpec queryConditions(GraphitronSchema schema) {
        return QueryConditionsGenerator.generate(schema, DEFAULT_OUTPUT_PACKAGE).stream()
            .filter(t -> t.name().equals("QueryConditions"))
            .findFirst().orElseThrow();
    }

    private static String method(TypeSpec spec, String name) {
        return spec.methodSpecs().stream()
            .filter(m -> m.name().equals(name))
            .findFirst()
            .orElseThrow(() -> new AssertionError(
                "no method '" + name + "' on " + spec.name() + "; has " + methodNames(spec)))
            .toString();
    }

    private static List<String> methodNames(TypeSpec spec) {
        return spec.methodSpecs().stream().map(m -> m.name()).toList();
    }
}
