package no.sikt.graphitron.model;

import graphql.schema.GraphQLEnumType;
import graphql.schema.GraphQLInputObjectType;
import graphql.schema.GraphQLInterfaceType;
import graphql.schema.GraphQLNamedType;
import graphql.schema.GraphQLObjectType;
import graphql.schema.GraphQLSchema;
import no.sikt.graphitron.model.schema.SchemaAssembly;
import no.sikt.graphitron.model.test.FactGraphs;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a {@code <schemaInput descriptionNote>} does to a file's elements, read off the two schemas
 * capture hands back for the {@code scenarios/configured-note} document: the post-synthesis schema
 * appends the note to every element the file declares, after a blank line where the element has a
 * description and alone where it has none, and the schema as written carries none of it.
 *
 * <p>The elements are the fields, input fields, arguments and enum values the file declares, and
 * the object, interface, input, enum and union types whose base definition it holds. A type's
 * description is its base definition's, so a note reaches a type only through the file declaring
 * it, and reaches a field through the file declaring the field, extension or not. A scalar takes no
 * note. The document claims the descriptions the part relations carry; this reads the schema the
 * generator emits from them.
 */
class ConfiguredNoteTest {

    private static final String NOTE = "Part of cinema.";

    @RegisterExtension
    static final FactGraphs.Reader FACTS = FactGraphs.reader();

    private static GraphQLSchema emitted;
    private static GraphQLSchema written;

    @BeforeAll
    static void read() {
        FACTS.read("scenarios/configured-note", graph -> {
            emitted = ((SchemaAssembly.Assembled) graph.schema().synthesised().orElseThrow()).schema();
            written = ((SchemaAssembly.Assembled) graph.schema().written().assembly()).schema();
            return null;
        });
    }

    @Test
    @DisplayName("a type the file declares takes the note, after its own description or alone")
    void types() {
        assertThat(description("Query")).isEqualTo("The root.\n\n" + NOTE);
        assertThat(description("Film")).isEqualTo("A film.\n\n" + NOTE);
        assertThat(description("Node")).isEqualTo(NOTE);
        assertThat(description("FilmFilter")).isEqualTo(NOTE);
        assertThat(description("MpaaRating")).isEqualTo(NOTE);
        assertThat(description("AddressOccupant")).isEqualTo(NOTE);
        assertThat(description("BigDecimal")).as("a scalar takes no note").isNullOrEmpty();
        assertThat(description("Store")).as("its base definition is in the other file")
            .isEqualTo(((GraphQLNamedType) written.getType("Store")).getDescription())
            .doesNotContain(NOTE);
    }

    @Test
    @DisplayName("a field, argument, input field or enum value the file declares takes the note")
    void members() {
        var query = (GraphQLObjectType) emitted.getType("Query");
        assertThat(query.getFieldDefinition("films").getDescription()).isEqualTo(NOTE);
        assertThat(query.getFieldDefinition("films").getArgument("title").getDescription()).isEqualTo(NOTE);
        var film = (GraphQLObjectType) emitted.getType("Film");
        assertThat(film.getFieldDefinition("title").getDescription()).isEqualTo("Its title.\n\n" + NOTE);
        assertThat(film.getFieldDefinition("releaseYear").getDescription()).isEqualTo(NOTE);
        assertThat(((GraphQLInterfaceType) emitted.getType("Node")).getFieldDefinition("id")
            .getDescription()).isEqualTo(NOTE);
        assertThat(((GraphQLInputObjectType) emitted.getType("FilmFilter")).getFieldDefinition("title")
            .getDescription()).isEqualTo(NOTE);
        var rating = (GraphQLEnumType) emitted.getType("MpaaRating");
        assertThat(rating.getValue("G").getDescription()).isEqualTo(NOTE);
        assertThat(rating.getValue("PG").getDescription()).isEqualTo("Parental.\n\n" + NOTE);
    }

    @Test
    @DisplayName("a field takes the note of the file declaring it, extension or not")
    void extensions() {
        var store = (GraphQLObjectType) emitted.getType("Store");
        assertThat(store.getFieldDefinition("storeId").getDescription()).isEqualTo(NOTE);
        assertThat(store.getFieldDefinition("lastUpdate").getDescription()).isNull();
        var film = (GraphQLObjectType) emitted.getType("Film");
        assertThat(film.getFieldDefinition("length").getDescription()).isNull();
    }

    @Test
    @DisplayName("the schema as written carries no note")
    void theWrittenSchemaIsAsWritten() {
        assertThat(((GraphQLNamedType) written.getType("Film")).getDescription()).isEqualTo("A film.");
        var film = (GraphQLObjectType) written.getType("Film");
        assertThat(film.getFieldDefinition("releaseYear").getDescription()).isNull();
        assertThat(((GraphQLEnumType) written.getType("MpaaRating")).getValue("G").getDescription()).isNull();
    }

    private static String description(String type) {
        return ((GraphQLNamedType) emitted.getType(type)).getDescription();
    }
}
