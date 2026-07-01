package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.javapoet.MethodSpec;
import no.sikt.graphitron.javapoet.TypeSpec;
import no.sikt.graphitron.rewrite.generators.TypeClassGenerator;
import no.sikt.graphitron.rewrite.generators.TypeFetcherGenerator;
import no.sikt.graphitron.rewrite.generators.schema.FetcherRegistrationsEmitter;
import no.sikt.graphitron.rewrite.generators.util.TypeSpecAssertions;
import no.sikt.graphitron.rewrite.generators.util.TypeSpecAssertions.DataFetcherKind;
import org.junit.jupiter.api.Test;

import static no.sikt.graphitron.common.configuration.TestConfiguration.DEFAULT_OUTPUT_PACKAGE;
import static org.assertj.core.api.Assertions.assertThat;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;

/**
 * SDL → classified schema → generated {@code TypeSpec} pipeline tests for inline
 * {@link no.sikt.graphitron.rewrite.model.ChildField.ColumnReferenceField} emission with
 * {@code CallSiteCompaction.Direct} (R42 lift).
 *
 * <p>Asserts the structural contract: no per-field fetcher method on {@code *Fetchers}, the
 * {@code $fields} switch routes the field, and the {@code DataFetcher} value wires through
 * {@code ColumnFetcher}. Body-level SQL correctness is the compile and execution tiers' job.
 */
@PipelineTier
class ColumnReferenceFieldPipelineTest {

    private static final String SINGLE_HOP_SDL = """
        type Language @table(name: "language") { name: String }
        type Film @table(name: "film") {
            title: String
            languageName: String @field(name: "name") @reference(path: [{key: "film_language_id_fkey"}])
        }
        type Query { films: [Film!]! }
        """;

    @Test
    void directColumnReference_singleHop_reifiesReadMethod() {
        var filmFetchers = TypeFetcherGenerator.generate(
                TestSchemaHelper.buildSchema(SINGLE_HOP_SDL), DEFAULT_OUTPUT_PACKAGE).stream()
            .filter(t -> t.name().equals("FilmFetchers"))
            .findFirst()
            .orElseThrow();
        assertThat(filmFetchers.methodSpecs()).extracting(MethodSpec::name)
            .as("R303: Direct ColumnReferenceField projects inline via TypeClassGenerator.$fields; "
                + "the read of the aliased projection is reified as a named source-only method")
            .contains("languageName");
    }

    @Test
    void directColumnReference_singleHop_fetcherValueIsColumnFetcher() {
        var bodies = FetcherRegistrationsEmitter.emit(
            TestSchemaHelper.buildSchema(SINGLE_HOP_SDL), DEFAULT_OUTPUT_PACKAGE);
        assertThat(TypeSpecAssertions.wiringFor(bodies, "Film", "languageName"))
            .contains(DataFetcherKind.COLUMN_FETCHER);
    }

    @Test
    void directColumnReference_singleHop_typeClassFieldsMethodRoutesField() {
        var filmClass = findTypeClass(SINGLE_HOP_SDL, "Film");
        assertThat(TypeSpecAssertions.hasFieldsArm(filmClass, "languageName"))
            .as("Film.$fields must contain a case arm for languageName")
            .isTrue();
    }

    @Test
    void directColumnReference_multiHop_typeClassFieldsMethodRoutesField() {
        // film → film_actor → actor; project actor.first_name aliased as actorFirstName.
        var sdl = """
            type Actor @table(name: "actor") { firstName: String @field(name: "first_name") }
            type Film @table(name: "film") {
                title: String
                actorFirstName: String @field(name: "first_name")
                    @reference(path: [
                        {key: "film_actor_film_id_fkey"},
                        {key: "film_actor_actor_id_fkey"}
                    ])
            }
            type Query { films: [Film!]! }
            """;
        var filmClass = findTypeClass(sdl, "Film");
        assertThat(TypeSpecAssertions.hasFieldsArm(filmClass, "actorFirstName"))
            .as("Film.$fields must contain a case arm for the multi-hop reference")
            .isTrue();
    }

    private static TypeSpec findTypeClass(String sdl, String typeName) {
        return TypeClassGenerator.generate(TestSchemaHelper.buildSchema(sdl), DEFAULT_OUTPUT_PACKAGE).stream()
            .filter(t -> t.name().equals(typeName))
            .findFirst()
            .orElseThrow(() -> new AssertionError("Type class not found: " + typeName));
    }
}
