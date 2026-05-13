package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.rewrite.generators.TypeClassGenerator;
import no.sikt.graphitron.rewrite.generators.TypeFetcherGenerator;
import no.sikt.graphitron.rewrite.generators.util.TypeSpecAssertions;
import org.junit.jupiter.api.Test;

import static no.sikt.graphitron.common.configuration.TestConfiguration.DEFAULT_OUTPUT_PACKAGE;
import static org.assertj.core.api.Assertions.assertThat;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;

/**
 * SDL → classified schema → generated {@code TypeSpec} pipeline tests for the child-site
 * {@link no.sikt.graphitron.rewrite.model.ChildField.TableMethodField} fetcher (R43 commit 3:
 * lift out of {@code TypeFetcherGenerator.STUBBED_VARIANTS}).
 *
 * <p>Two accepted-shape cases mirror the spec's pipeline-tier coverage: an explicit
 * {@code @reference(path:)} with a single FK hop, and a single-FK auto-inference where the
 * classifier resolves the unique parent → target FK from the jOOQ catalog. Both cases assert that
 * a fetcher method is emitted under the field's name with the standard
 * {@code DataFetchingEnvironment env} signature and the {@code Result<Record>} (List) /
 * {@code Record} (Single) return shape.
 */
@PipelineTier
class TableMethodFieldPipelineTest {

    @Test
    void singleFkAutoInferred_emitsFetcherMethod() {
        var schema = TestSchemaHelper.buildSchema("""
            type Film @table(name: "film") { title: String }
            type Inventory @table(name: "inventory") {
                film: Film @tableMethod(className: "no.sikt.graphitron.rewrite.TestTableMethodStub", method: "getFilm")
            }
            type Query { inventory: Inventory }
            """);

        var inventoryFetchers = TypeFetcherGenerator.generate(schema, DEFAULT_OUTPUT_PACKAGE).stream()
            .filter(t -> t.name().equals("InventoryFetchers"))
            .findFirst()
            .orElseThrow();

        var fetcher = inventoryFetchers.methodSpecs().stream()
            .filter(m -> m.name().equals("film"))
            .findFirst()
            .orElseThrow();

        assertThat(fetcher.parameters()).extracting(p -> p.type().toString())
            .containsExactly("graphql.schema.DataFetchingEnvironment");
        assertThat(fetcher.returnType().toString())
            .isEqualTo("graphql.execution.DataFetcherResult<org.jooq.Record>");
        // Body sanity: the developer's static method is invoked. Behavioural round-trip is
        // covered at the execution tier in graphitron-sakila-example.
        assertThat(fetcher.code().toString())
            .as("body dispatches to the developer-authored static @tableMethod method")
            .contains("TestTableMethodStub")
            .contains("getFilm");
    }

    @Test
    void explicitReferencePathSingleHopFk_emitsFetcherMethod() {
        var schema = TestSchemaHelper.buildSchema("""
            type Language @table(name: "language") { name: String }
            type Film @table(name: "film") {
                language: Language
                    @tableMethod(className: "no.sikt.graphitron.rewrite.TestTableMethodStub", method: "getLanguage")
                    @reference(path: [{key: "film_language_id_fkey"}])
            }
            type Query { film: Film }
            """);

        var filmFetchers = TypeFetcherGenerator.generate(schema, DEFAULT_OUTPUT_PACKAGE).stream()
            .filter(t -> t.name().equals("FilmFetchers"))
            .findFirst()
            .orElseThrow();

        var fetcher = filmFetchers.methodSpecs().stream()
            .filter(m -> m.name().equals("language"))
            .findFirst()
            .orElseThrow();

        assertThat(fetcher.parameters()).extracting(p -> p.type().toString())
            .containsExactly("graphql.schema.DataFetchingEnvironment");
        assertThat(fetcher.returnType().toString())
            .isEqualTo("graphql.execution.DataFetcherResult<org.jooq.Record>");
        assertThat(fetcher.code().toString())
            .as("body dispatches to the developer-authored static @tableMethod method")
            .contains("TestTableMethodStub")
            .contains("getLanguage");
    }

    /**
     * FK-projection injection: the parent type class's {@code $fields} method must project the
     * FK source-side column even when the user's SDL selection doesn't request it, otherwise the
     * fetcher's {@code parentRecord.get(DSL.name("<src>"), …)} read throws
     * {@code IllegalArgumentException} at runtime. Mirrors the synthesis that Split* fields
     * already get via {@code TypeClassGenerator.collectRequiredProjectionColumns}.
     */
    @Test
    void singleFkAutoInferred_parentDollarFieldsProjectsFkSourceColumn() {
        var schema = TestSchemaHelper.buildSchema("""
            type Film @table(name: "film") { title: String }
            type Inventory @table(name: "inventory") {
                film: Film @tableMethod(className: "no.sikt.graphitron.rewrite.TestTableMethodStub", method: "getFilm")
            }
            type Query { inventory: Inventory }
            """);

        var inventoryClass = TypeClassGenerator.generate(schema, DEFAULT_OUTPUT_PACKAGE).stream()
            .filter(t -> t.name().equals("Inventory"))
            .findFirst()
            .orElseThrow();

        assertThat(TypeSpecAssertions.appendsRequiredColumn(inventoryClass, "FILM_ID"))
            .as("parent $fields injects the FK source-side column the child @tableMethod fetcher reads")
            .isTrue();
    }

    @Test
    void explicitReferencePathSingleHopFk_parentDollarFieldsProjectsFkSourceColumn() {
        var schema = TestSchemaHelper.buildSchema("""
            type Language @table(name: "language") { name: String }
            type Film @table(name: "film") {
                language: Language
                    @tableMethod(className: "no.sikt.graphitron.rewrite.TestTableMethodStub", method: "getLanguage")
                    @reference(path: [{key: "film_language_id_fkey"}])
            }
            type Query { film: Film }
            """);

        var filmClass = TypeClassGenerator.generate(schema, DEFAULT_OUTPUT_PACKAGE).stream()
            .filter(t -> t.name().equals("Film"))
            .findFirst()
            .orElseThrow();

        assertThat(TypeSpecAssertions.appendsRequiredColumn(filmClass, "LANGUAGE_ID"))
            .as("parent $fields injects the FK source-side column resolved from the explicit @reference path")
            .isTrue();
    }
}
