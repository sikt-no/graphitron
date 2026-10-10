package no.sikt.graphitron.model;

import graphql.schema.GraphQLEnumType;
import graphql.schema.GraphQLInputObjectType;
import graphql.schema.GraphQLInterfaceType;
import graphql.schema.GraphQLNamedType;
import graphql.schema.GraphQLObjectType;
import graphql.schema.GraphQLSchema;
import no.sikt.graphitron.model.schema.SchemaAssembly;
import no.sikt.graphitron.model.schema.input.SchemaInput;
import no.sikt.graphitron.model.schema.input.SchemaSource;
import no.sikt.graphitron.model.test.CapturedStore;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_ARGUMENT;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_ENUM_VALUE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_FIELD;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_TYPE;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a {@code <schemaInput descriptionNote>} does to a file's elements, read off the two schemas
 * capture hands back: the post-synthesis schema appends the note to every element the file
 * declares, after a blank line where the element has a description and alone where it has none,
 * and the schema as written carries none of it.
 *
 * <p>The elements are the fields, input fields, arguments and enum values the file declares, and
 * the object, interface, input, enum and union types whose base definition it holds. A type's
 * description is its base definition's, so a note reaches a type only through the file declaring
 * it, and reaches a field through the file declaring the field, extension or not. A scalar takes no
 * note.
 */
class ConfiguredNoteTest {

    private static final String NOTE = "Part of cinema.";

    private static final String CINEMA = """
        "The root."
        type Query {
          film(id: ID): Film
          search(filter: FilmFilter): [Result]
          rated: Rating
        }
        "A film."
        type Film {
          "Its title."
          title: String
          year: Int
        }
        extend type Studio { founded: Int }
        interface Named { name: String }
        input FilmFilter { title: String }
        enum Rating { G "Parental." PG }
        union Result = Film
        scalar Date
        """;

    private static final String SHARED = """
        type Studio { name: String }
        extend type Film { length: Int }
        """;

    private static GraphQLSchema emitted;
    private static GraphQLSchema written;
    private static Map<String, String> anchored;

    @BeforeAll
    static void capture(@TempDir Path tmp) throws IOException {
        var cinema = Files.writeString(tmp.resolve("cinema.graphqls"), CINEMA);
        var shared = Files.writeString(tmp.resolve("shared.graphqls"), SHARED);
        try (var store = CapturedStore.configured(tmp, List.of(
                new SchemaInput(SchemaSource.file(cinema), Optional.empty(), Optional.of(NOTE)),
                new SchemaInput(SchemaSource.file(shared), Optional.empty(), Optional.empty())))) {
            emitted = ((SchemaAssembly.Assembled) store.schema().synthesised().orElseThrow()).schema();
            written = ((SchemaAssembly.Assembled) store.schema().written().assembly()).schema();
            var dsl = store.dsl();
            anchored = new HashMap<>();
            dsl.select(GRAPHITRON_TYPE.COORDINATE, GRAPHITRON_TYPE.DESCRIPTION).from(GRAPHITRON_TYPE)
                .forEach(r -> anchored.put(r.value1(), r.value2()));
            dsl.select(GRAPHITRON_FIELD.COORDINATE, GRAPHITRON_FIELD.DESCRIPTION).from(GRAPHITRON_FIELD)
                .forEach(r -> anchored.put(r.value1(), r.value2()));
            dsl.select(GRAPHITRON_ARGUMENT.COORDINATE, GRAPHITRON_ARGUMENT.DESCRIPTION)
                .from(GRAPHITRON_ARGUMENT)
                .forEach(r -> anchored.put(r.value1(), r.value2()));
            dsl.select(GRAPHITRON_ENUM_VALUE.COORDINATE, GRAPHITRON_ENUM_VALUE.DESCRIPTION)
                .from(GRAPHITRON_ENUM_VALUE)
                .forEach(r -> anchored.put(r.value1(), r.value2()));
        }
    }

    @Test
    @DisplayName("a type the file declares takes the note, after its own description or alone")
    void types() {
        assertThat(description("Query")).isEqualTo("The root.\n\n" + NOTE);
        assertThat(description("Film")).isEqualTo("A film.\n\n" + NOTE);
        assertThat(description("Named")).isEqualTo(NOTE);
        assertThat(description("FilmFilter")).isEqualTo(NOTE);
        assertThat(description("Rating")).isEqualTo(NOTE);
        assertThat(description("Result")).isEqualTo(NOTE);
        assertThat(description("Date")).as("a scalar takes no note").isNullOrEmpty();
        assertThat(description("Studio")).as("its base definition is in the other file").isNullOrEmpty();
    }

    @Test
    @DisplayName("a field, argument, input field or enum value the file declares takes the note")
    void members() {
        var query = (GraphQLObjectType) emitted.getType("Query");
        assertThat(query.getFieldDefinition("film").getDescription()).isEqualTo(NOTE);
        assertThat(query.getFieldDefinition("film").getArgument("id").getDescription()).isEqualTo(NOTE);
        var film = (GraphQLObjectType) emitted.getType("Film");
        assertThat(film.getFieldDefinition("title").getDescription()).isEqualTo("Its title.\n\n" + NOTE);
        assertThat(film.getFieldDefinition("year").getDescription()).isEqualTo(NOTE);
        assertThat(((GraphQLInterfaceType) emitted.getType("Named")).getFieldDefinition("name")
            .getDescription()).isEqualTo(NOTE);
        assertThat(((GraphQLInputObjectType) emitted.getType("FilmFilter")).getFieldDefinition("title")
            .getDescription()).isEqualTo(NOTE);
        var rating = (GraphQLEnumType) emitted.getType("Rating");
        assertThat(rating.getValue("G").getDescription()).isEqualTo(NOTE);
        assertThat(rating.getValue("PG").getDescription()).isEqualTo("Parental.\n\n" + NOTE);
    }

    @Test
    @DisplayName("a field takes the note of the file declaring it, extension or not")
    void extensions() {
        var studio = (GraphQLObjectType) emitted.getType("Studio");
        assertThat(studio.getFieldDefinition("founded").getDescription()).isEqualTo(NOTE);
        assertThat(studio.getFieldDefinition("name").getDescription()).isNull();
        var film = (GraphQLObjectType) emitted.getType("Film");
        assertThat(film.getFieldDefinition("length").getDescription()).isNull();
    }

    /**
     * The store states the description the generator emits, so a reader of the anchors and a reader
     * of the emitted schema agree without either composing the note.
     */
    @Test
    @DisplayName("the part relations carry the description the emitted schema does")
    void theAnchorsCarryTheEmittedDescription() {
        assertThat(anchored).containsEntry("Film", "A film.\n\n" + NOTE)
            .containsEntry("Film.title", "Its title.\n\n" + NOTE)
            .containsEntry("Film.year", NOTE)
            .containsEntry("Query.film(id:)", NOTE)
            .containsEntry("Rating.PG", "Parental.\n\n" + NOTE)
            .containsEntry("Studio.name", null);
    }

    @Test
    @DisplayName("the schema as written carries no note")
    void theWrittenSchemaIsAsWritten() {
        assertThat(((GraphQLNamedType) written.getType("Film")).getDescription()).isEqualTo("A film.");
        var film = (GraphQLObjectType) written.getType("Film");
        assertThat(film.getFieldDefinition("year").getDescription()).isNull();
        assertThat(((GraphQLEnumType) written.getType("Rating")).getValue("G").getDescription()).isNull();
    }

    private static String description(String type) {
        return ((GraphQLNamedType) emitted.getType(type)).getDescription();
    }
}
