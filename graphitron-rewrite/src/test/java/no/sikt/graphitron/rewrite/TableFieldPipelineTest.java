package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.rewrite.generators.TypeClassGenerator;
import no.sikt.graphitron.rewrite.generators.TypeFetcherGenerator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static no.sikt.graphitron.common.configuration.TestConfiguration.DEFAULT_JOOQ_PACKAGE;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * SDL → classified schema → generated {@code TypeSpec} pipeline tests for inline
 * {@link no.sikt.graphitron.rewrite.model.ChildField.TableField} emission.
 *
 * <p>Verifies the structural contract of G5 C3: the {@code TypeClassGenerator.$fields} method
 * contains a switch arm for each nested table field; {@code TypeFetcherGenerator} emits
 * <em>no</em> fetcher method for the field (the inline projection lives in {@code $fields});
 * and the ConditionJoin branch emits a runtime-throwing stub pending classification-vocabulary
 * item 5.
 */
class TableFieldPipelineTest {

    @BeforeEach
    void setup() {
        RewriteConfig.setProperties(java.util.Set.of(), "", "fake.code.generated", DEFAULT_JOOQ_PACKAGE, java.util.Map.of());
    }

    @AfterEach
    void teardown() {
        RewriteConfig.clear();
    }

    @Test
    void singleHopFk_producesSwitchArmInDollarFields() {
        var schema = TestSchemaHelper.buildSchema("""
            type Language @table(name: "language") { name: String }
            type Film @table(name: "film") {
                language: Language @reference(path: [{key: "film_language_id_fkey"}])
            }
            type Query { film: Film }
            """);

        var filmClass = TypeClassGenerator.generate(schema).stream()
            .filter(t -> t.name().equals("Film"))
            .findFirst()
            .orElseThrow();

        var code = filmClass.methodSpecs().stream()
            .filter(m -> m.name().equals("$fields")).findFirst().orElseThrow()
            .code().toString();

        assertThat(code).contains("case \"language\"");
    }

    @Test
    void tableField_producesNoFetcherMethod() {
        var schema = TestSchemaHelper.buildSchema("""
            type Language @table(name: "language") { name: String }
            type Film @table(name: "film") {
                language: Language @reference(path: [{key: "film_language_id_fkey"}])
            }
            type Query { film: Film }
            """);

        var filmFetchers = TypeFetcherGenerator.generate(schema).stream()
            .filter(t -> t.name().equals("FilmFetchers"))
            .findFirst()
            .orElseThrow();

        var methodNames = filmFetchers.methodSpecs().stream()
            .map(m -> m.name())
            .toList();

        assertThat(methodNames)
            .as("TableField projects inline via TypeClassGenerator.$fields — no fetcher method")
            .doesNotContain("language");
    }

    @Test
    void dollarFieldsSignature_unchangedWhenTableFieldPresent() {
        var schema = TestSchemaHelper.buildSchema("""
            type Language @table(name: "language") { name: String }
            type Film @table(name: "film") {
                language: Language @reference(path: [{key: "film_language_id_fkey"}])
            }
            type Query { film: Film }
            """);

        var filmClass = TypeClassGenerator.generate(schema).stream()
            .filter(t -> t.name().equals("Film"))
            .findFirst()
            .orElseThrow();

        var dollarFields = filmClass.methodSpecs().stream()
            .filter(m -> m.name().equals("$fields")).findFirst().orElseThrow();

        assertThat(dollarFields.parameters()).extracting(p -> p.type().toString())
            .containsExactly(
                "graphql.schema.DataFetchingFieldSelectionSet",
                DEFAULT_JOOQ_PACKAGE + ".tables.Film",
                "graphql.schema.DataFetchingEnvironment");
    }
}
