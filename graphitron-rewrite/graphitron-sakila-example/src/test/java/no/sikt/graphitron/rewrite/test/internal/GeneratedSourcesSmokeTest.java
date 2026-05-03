package no.sikt.graphitron.rewrite.test.internal;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThatCode;
import no.sikt.graphitron.rewrite.test.tier.CompilationTier;

/**
 * Verifies that the code generator produced all expected classes.
 *
 * <p>The compilation tier catches type errors in generated code, but it cannot detect
 * a generator bug that silently drops a class — an empty output still compiles.
 * This test enumerates the classes the schema should produce and fails if any are missing.
 */
@CompilationTier
class GeneratedSourcesSmokeTest {

    private static final String PKG = "no.sikt.graphitron.generated";

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
        // Public entrypoint at output-package root
        PKG + ".Graphitron",
        // Internal assembler + generated context interface
        PKG + ".schema.GraphitronSchema",
        PKG + ".schema.GraphitronContext",
        // <TypeName>Type classes — one per GraphQL type (five representative categories)
        // Category 1: regular @table type
        PKG + ".schema.FilmType",
        // Category 2: nested type with BatchKeyField leaf (FilmInfo.cast is @splitQuery)
        PKG + ".schema.FilmInfoType",
        // Category 3: nested type with column-only leaves (no BatchKeyField)
        PKG + ".schema.FilmSummaryType",
        // Category 4: connection type (structural / hand-written)
        PKG + ".schema.FilmsConnectionType",
        // Category 5: edge type (structural / hand-written)
        PKG + ".schema.FilmsEdgeType",
        // Category 6: synthesised connection type (directive-driven @asConnection)
        PKG + ".schema.QueryStoresConnectionType",
        // Category 7: synthesised edge type
        PKG + ".schema.QueryStoresEdgeType",
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
