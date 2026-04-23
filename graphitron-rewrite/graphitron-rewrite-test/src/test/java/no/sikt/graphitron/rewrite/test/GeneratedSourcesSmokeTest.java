package no.sikt.graphitron.rewrite.test;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Verifies that the code generator produced all expected classes.
 *
 * <p>The compilation test (Level 5) catches type errors in generated code, but it cannot detect
 * a generator bug that silently drops a class — an empty output still compiles.
 * This test enumerates the classes the schema should produce and fails if any are missing.
 */
class GeneratedSourcesSmokeTest {

    private static final String PKG = "no.sikt.graphitron.rewrite.test.generated.rewrite";

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
        // Wiring entry point
        PKG + ".GraphitronWiring",
        // Wiring classes — one per GraphQL object type (five representative categories)
        // Category 1: regular @table type
        PKG + ".wiring.FilmWiring",
        // Category 2: nested type with BatchKeyField leaf (FilmInfo.cast is @splitQuery)
        PKG + ".wiring.FilmInfoWiring",
        // Category 3: nested type with column-only leaves (no BatchKeyField)
        PKG + ".wiring.FilmSummaryWiring",
        // Category 4: connection type
        PKG + ".wiring.FilmsConnectionWiring",
        // Category 5: edge type
        PKG + ".wiring.FilmsEdgeWiring",
        // Fetcher classes — one per GraphQL object type
        PKG + ".fetchers.QueryFetchers",
        PKG + ".fetchers.FilmFetchers",
        PKG + ".fetchers.CustomerFetchers",
        PKG + ".fetchers.LanguageFetchers",
        // Table classes — one per distinct SQL table referenced by a @table type
        PKG + ".types.Film",
        PKG + ".types.Customer",
        PKG + ".types.Language",
        // Query-root condition orchestrator (env-aware shim layer over the entity-scoped
        // *Conditions classes). Generated whenever a root Query has any QueryTableField.
        PKG + ".conditions.QueryConditions"
    })
    void generatedClassIsPresent(String className) {
        assertThatCode(() -> Class.forName(className))
            .as("Expected generated class: %s", className)
            .doesNotThrowAnyException();
    }
}
