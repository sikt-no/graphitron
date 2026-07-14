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
 * R13 Phase 4: structural coverage for the facet emitters, at the method-surface level only
 * (per testing.adoc's ban on code-string assertions against generated method bodies, applied to
 * this class in the R13 review's finding 4). What each fragment and the fetcher's facet plan
 * actually *do* is pinned behaviourally elsewhere: the sakila example's faceted fixture covers
 * the emitted shapes at the compilation tier (including the lifted-local base-fragment shape and
 * the cross-arg name collision), and {@code GraphQLQueryTest}'s {@code filmsFaceted} cases pin
 * the filter-minus-self semantics, the base's retention of non-facet predicates, the NULL-bucket
 * and scrub behaviour, and the failure degrade contract end to end.
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
    void facetedCarrier_emitsBaseAndPerFacetFragments() {
        var conditions = queryConditions(TestSchemaHelper.buildSchema(FACETED));
        assertThat(methodNames(conditions)).contains(
            "filmsCondition", "filmsFacetBaseCondition",
            "filmsFacet_titleCondition", "filmsFacet_lengthCondition");
    }

    @Test
    void unfacetedCarrier_emitsNoFragments() {
        var conditions = queryConditions(TestSchemaHelper.buildSchema(UNFACETED));
        assertThat(methodNames(conditions))
            .contains("filmsCondition")
            .noneMatch(n -> n.contains("Facet"));
    }

    @Test
    void connectionFetchersClass_hasFacetsDelegateOnlyWhenFaceted() {
        assertThat(methodNames(connectionFetchers(TestSchemaHelper.buildSchema(FACETED))))
            .contains("facets");
        assertThat(methodNames(connectionFetchers(TestSchemaHelper.buildSchema(UNFACETED))))
            .doesNotContain("facets");
    }

    private static TypeSpec queryConditions(GraphitronSchema schema) {
        return QueryConditionsGenerator.generate(schema, DEFAULT_OUTPUT_PACKAGE).stream()
            .filter(t -> t.name().equals("QueryConditions"))
            .findFirst().orElseThrow();
    }

    private static TypeSpec connectionFetchers(GraphitronSchema schema) {
        return ConnectionFetcherClassGenerator.generate(schema, DEFAULT_OUTPUT_PACKAGE).stream()
            .filter(t -> t.name().equals("QueryFilmsConnectionFetchers"))
            .findFirst().orElseThrow();
    }

    private static List<String> methodNames(TypeSpec spec) {
        return spec.methodSpecs().stream().map(m -> m.name()).toList();
    }
}
