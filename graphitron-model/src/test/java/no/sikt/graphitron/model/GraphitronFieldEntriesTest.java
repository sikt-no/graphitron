package no.sikt.graphitron.model;

import no.sikt.graphitron.model.capture.document.SdlCapture;
import no.sikt.graphitron.model.run.GraphIdentity;
import no.sikt.graphitron.model.run.SubjectConfig;
import no.sikt.graphitron.model.schema.input.SchemaRecipe;
import org.jooq.DSLContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_AST_FIELD_CONDITION_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_AST_FIELD_REFERENCE_STEP_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_AST_SERVICE_CONTEXT_ARG_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_AST_SERVICE_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHQL_AST_FIELD_DEFINITION_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHQL_AST_FIELD_DIRECTIVE_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHQL_AST_INPUT_VALUE_DIRECTIVE_ENTRY;
import static no.sikt.graphitron.model.test.SeededStore.withSeededStore;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * The field site's decode, keyed where the directive was written.
 *
 * <p>What these stand up is what the site split and the key make possible together. A row says what
 * one application's arguments meant, under the position of the {@code @} token, so the coordinate it
 * was written at is a join and a repeated application is repeated rows rather than an ordinal the
 * writer had to assign.
 */
class GraphitronFieldEntriesTest {

    private static final String GRAPH = "decoded";

    /**
     * The row, the row it decodes, and the field that row was written on. Asserted through the join
     * rather than by reading columns back: what the relation buys is that the coordinate is one
     * join away and no column here repeats it.
     */
    @Test
    @DisplayName("a field application's decode reaches its coordinate through the row it decodes")
    void theDecodeReachesItsCoordinate(@TempDir Path tmp) {
        write(tmp, "film.graphqls", """
            type Film {
              summary: String @condition(condition: {className: "no.example.C", method: "visible"})
            }
            """);

        withSeededStore(GRAPH, dsl -> {
            read(dsl, tmp);
            var decode = GRAPHITRON_AST_FIELD_CONDITION_ENTRY;
            var applied = GRAPHQL_AST_FIELD_DIRECTIVE_ENTRY;
            var declared = GRAPHQL_AST_FIELD_DEFINITION_ENTRY;

            assertThat(dsl.select(applied.NAME, declared.COORDINATE, decode.CLASS_NAME,
                        decode.METHOD, decode.OVERRIDE)
                    .from(decode)
                    .join(applied)
                    .on(decode.GRAPH_NAME.eq(applied.GRAPH_NAME))
                    .and(decode.SOURCE_NAME.eq(applied.SOURCE_NAME))
                    .and(decode.SOURCE_LINE.eq(applied.SOURCE_LINE))
                    .and(decode.SOURCE_COLUMN.eq(applied.SOURCE_COLUMN))
                    .join(declared)
                    .on(applied.GRAPH_NAME.eq(declared.GRAPH_NAME))
                    .and(applied.SOURCE_NAME.eq(declared.SOURCE_NAME))
                    .and(applied.PARENT_LINE.eq(declared.SOURCE_LINE))
                    .and(applied.PARENT_COLUMN.eq(declared.SOURCE_COLUMN))
                    .fetch())
                .as("the decode, the application it decodes, and the field it was written on, none "
                    + "of which repeats what another holds; the flag the author left out is null "
                    + "rather than the definition's own default")
                .extracting(r -> r.value1(), r -> r.value2(), r -> r.value3(), r -> r.value4(),
                    r -> r.value5())
                .containsExactly(tuple("condition", "Film.summary", "no.example.C", "visible", null));
        });
    }

    /**
     * The site split, which is the one place this writer disagrees with the walk it replaces. An
     * input object's field is an input value to the parser, so a directive written on one is a row
     * of the input-value relation; the field site is object and interface fields and nothing else.
     */
    @Test
    @DisplayName("a directive on an input object's field is not a field-site decode")
    void anInputObjectFieldIsNotTheFieldSite(@TempDir Path tmp) {
        write(tmp, "filter.graphqls", """
            input FilmFilter {
              guarded: String @condition(condition: {className: "no.example.C", method: "guard"})
            }
            """);

        withSeededStore(GRAPH, dsl -> {
            read(dsl, tmp);

            // Restricted to this case's own document, the bundled directive vocabulary being read
            // as part of every corpus and applying @deprecated to an input value of its own.
            var written = GRAPHQL_AST_INPUT_VALUE_DIRECTIVE_ENTRY.SOURCE_NAME.like("%filter.graphqls");
            assertThat(dsl.select(GRAPHQL_AST_INPUT_VALUE_DIRECTIVE_ENTRY.NAME)
                    .from(GRAPHQL_AST_INPUT_VALUE_DIRECTIVE_ENTRY).where(written)
                    .fetch(GRAPHQL_AST_INPUT_VALUE_DIRECTIVE_ENTRY.NAME))
                .as("the application is transcribed at the site the parser puts it")
                .containsExactly("condition");
            assertThat(dsl.fetchCount(GRAPHQL_AST_FIELD_DIRECTIVE_ENTRY))
                .as("and not at the field site").isZero();
            assertThat(dsl.fetchCount(GRAPHITRON_AST_FIELD_CONDITION_ENTRY))
                .as("so the field site's decode has nothing to say about it, which is what the "
                    + "foreign key already required: a row here references a row there")
                .isZero();
        });
    }

    /**
     * A repeatable directive, decoded without an ordinal. Two applications are two positions and
     * the steps of each are numbered from zero inside their own, so nothing here has to decide
     * which application came first; the anchors rank the positions when they need to.
     */
    @Test
    @DisplayName("two @reference applications on one field are told apart by position, not ordinal")
    void repeatedApplicationsAreToldApartByPosition(@TempDir Path tmp) {
        write(tmp, "film.graphqls", """
            type Film {
              actors: [Actor!]
                @reference(path: [{table: "film_actor"}])
                @reference(path: [{table: "actor", key: "film_actor_actor_id_fk"}])
            }
            """);

        withSeededStore(GRAPH, dsl -> {
            read(dsl, tmp);
            var step = GRAPHITRON_AST_FIELD_REFERENCE_STEP_ENTRY;

            assertThat(dsl.selectDistinct(step.SOURCE_LINE).from(step).fetch(step.SOURCE_LINE))
                .as("the two applications sit at two positions, which is their whole identity here")
                .hasSize(2);
            assertThat(dsl.select(step.POSITION, step.TABLE_REF, step.KEY_REF).from(step).fetch())
                .as("and each numbers its own path from zero, the index being the author's order "
                    + "inside one application rather than across the field")
                .extracting(r -> r.value1(), r -> r.value2(), r -> r.value3())
                .containsExactlyInAnyOrder(
                    tuple(0, "film_actor", null),
                    tuple(0, "actor", "film_actor_actor_id_fk"));
        });
    }

    /**
     * The four names this site decodes into no relation. Three declare nothing to decode and the
     * fourth carries only its path, so a row for any of them would say what the applied-directive
     * row already says.
     */
    @Test
    @DisplayName("a directive whose decode would be its key alone is the applied row and nothing more")
    void anEmptyDecodeIsTheAppliedRowAlone(@TempDir Path tmp) {
        write(tmp, "film.graphqls", """
            type Film {
              reviews: [Review!] @splitQuery
              stock: [Stock!] @tenantFanOut
            }
            """);

        withSeededStore(GRAPH, dsl -> {
            read(dsl, tmp);

            assertThat(dsl.select(GRAPHQL_AST_FIELD_DIRECTIVE_ENTRY.NAME)
                    .from(GRAPHQL_AST_FIELD_DIRECTIVE_ENTRY)
                    .fetch(GRAPHQL_AST_FIELD_DIRECTIVE_ENTRY.NAME))
                .as("both applications are transcribed, which is the whole of what they say")
                .containsExactlyInAnyOrder("splitQuery", "tenantFanOut");
            assertThat(decodeRelationsHoldingRows(dsl))
                .as("and no decode relation holds a row, there being nothing to decode")
                .isEmpty();
        });
    }

    /**
     * A list argument that hangs off the decode rather than off the application, which is the
     * second of the two parent shapes this site uses. Shrinking the list has to delete the row the
     * author dropped, and the cascade from the application cannot see that edit: the application
     * survives it.
     */
    @Test
    @DisplayName("an element the author dropped from a list argument is gone after the next reading")
    void aDroppedListElementIsGone(@TempDir Path tmp) {
        write(tmp, "mutation.graphqls", """
            type Mutation {
              importFilms: Boolean
                @service(
                  service: {className: "no.example.S", method: "importFilms"}
                  contextArguments: ["tenantId", "localeId"]
                )
            }
            """);

        withSeededStore(GRAPH, dsl -> {
            read(dsl, tmp);
            var arg = GRAPHITRON_AST_SERVICE_CONTEXT_ARG_ENTRY;
            assertThat(dsl.select(arg.POSITION, arg.NAME).from(arg).fetch())
                .as("both elements, at the indices the author wrote them at")
                .extracting(r -> r.value1(), r -> r.value2())
                .containsExactlyInAnyOrder(tuple(0, "tenantId"), tuple(1, "localeId"));

            write(tmp, "mutation.graphqls", """
                type Mutation {
                  importFilms: Boolean
                    @service(
                      service: {className: "no.example.S", method: "importFilms"}
                      contextArguments: ["tenantId"]
                    )
                }
                """);
            read(dsl, tmp);

            assertThat(dsl.fetchCount(GRAPHITRON_AST_SERVICE_ENTRY))
                .as("the application is still there, so nothing cascaded").isEqualTo(1);
            assertThat(dsl.select(arg.POSITION, arg.NAME).from(arg).fetch())
                .as("and the element the author dropped is not a fact any more, which only this "
                    + "writer's own sweep could have found")
                .extracting(r -> r.value1(), r -> r.value2())
                .containsExactly(tuple(0, "tenantId"));
        });
    }

    /**
     * The decode relations that hold a row, named off the generated model rather than listed, so a
     * relation added to the decode is one this case sees without being told about it.
     */
    private static List<String> decodeRelationsHoldingRows(DSLContext dsl) {
        return Public.PUBLIC.getTables().stream()
            .filter(table -> table.getName().toLowerCase(Locale.ROOT).startsWith("graphitron_ast_"))
            .filter(table -> dsl.fetchCount(table) > 0)
            .map(table -> table.getName().toLowerCase(Locale.ROOT))
            .sorted()
            .toList();
    }

    /** One reading of everything the directory holds, which is what a run does. */
    private static void read(DSLContext dsl, Path baseDir) {
        SdlCapture.capture(dsl, new GraphIdentity(GRAPH, baseDir),
            SubjectConfig.of(new SchemaRecipe(baseDir.resolve("pom.xml"),
                List.of(SchemaRecipe.Binding.pattern("*.graphqls")), List.of("graphqls"))),
            LocalDateTime.now());
    }

    private static void write(Path directory, String name, String sdl) {
        try {
            Files.writeString(directory.resolve(name), sdl, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
