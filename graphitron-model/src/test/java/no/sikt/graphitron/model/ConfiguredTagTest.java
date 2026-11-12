package no.sikt.graphitron.model;

import graphql.schema.GraphQLDirectiveContainer;
import graphql.schema.GraphQLEnumType;
import graphql.schema.GraphQLInputObjectType;
import graphql.schema.GraphQLInterfaceType;
import graphql.schema.GraphQLObjectType;
import graphql.schema.GraphQLSchema;
import no.sikt.graphitron.model.schema.SchemaAssembly;
import no.sikt.graphitron.model.test.FactGraphs;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What {@code <schemaInput tag>} puts on a file's elements, read off the two schemas capture hands
 * back for the {@code scenarios/configured-tag} document: the post-synthesis schema carries the tag
 * on every kind of element the rule names, and the schema as written carries none of it.
 *
 * <p>One rule produces the tags, {@code graphitron_configured_tag}, which the document claims and
 * which the emitted schema applies; the written schema is what the author wrote. The kinds are the
 * ones the rule states: fields of objects and interfaces, wherever the type's sites declare them,
 * input fields, arguments, enum values and unions. A type declaration is never tagged, and an element
 * carrying a {@code @tag} of its own keeps that one alone.
 */
class ConfiguredTagTest {

    @RegisterExtension
    static final FactGraphs.Reader FACTS = FactGraphs.reader();

    private static GraphQLSchema emitted;
    private static GraphQLSchema written;

    @BeforeAll
    static void read() {
        FACTS.read("scenarios/configured-tag", graph -> {
            emitted = ((SchemaAssembly.Assembled) graph.schema().synthesised().orElseThrow()).schema();
            written = ((SchemaAssembly.Assembled) graph.schema().written().assembly()).schema();
            return null;
        });
    }

    @Test
    @DisplayName("fields, wherever the type's sites declare them, and arguments take the file's tag")
    void fieldsAndArguments() {
        var query = (GraphQLObjectType) emitted.getType("Query");
        assertThat(tags(query.getFieldDefinition("films"))).containsExactly("catalog");
        assertThat(tags(query.getFieldDefinition("films").getArgument("title"))).containsExactly("catalog");
        var film = (GraphQLObjectType) emitted.getType("Film");
        assertThat(tags(film.getFieldDefinition("title"))).containsExactly("catalog");
        assertThat(tags(film.getFieldDefinition("length"))).as("declared by the extension")
            .containsExactly("catalog");
        var node = (GraphQLInterfaceType) emitted.getType("Node");
        assertThat(tags(node.getFieldDefinition("id"))).containsExactly("catalog");
    }

    @Test
    @DisplayName("input fields, enum values and unions take the file's tag, and type declarations none")
    void theOtherKinds() {
        var filter = (GraphQLInputObjectType) emitted.getType("FilmFilter");
        assertThat(tags(filter.getFieldDefinition("title"))).containsExactly("catalog");
        var rating = (GraphQLEnumType) emitted.getType("MpaaRating");
        assertThat(tags(rating.getValue("G"))).containsExactly("catalog");
        assertThat(tags(rating.getValue("PG"))).containsExactly("catalog");
        assertThat(tags((GraphQLDirectiveContainer) emitted.getType("AddressOccupant")))
            .containsExactly("catalog");
        assertThat(tags((GraphQLDirectiveContainer) emitted.getType("Film"))).isEmpty();
        assertThat(tags(rating)).isEmpty();
        assertThat(tags(filter)).isEmpty();
    }

    @Test
    @DisplayName("an element with a @tag of its own keeps it alone")
    void anAuthoredTagStands() {
        var film = (GraphQLObjectType) emitted.getType("Film");
        assertThat(tags(film.getFieldDefinition("releaseYear"))).containsExactly("own");
    }

    @Test
    @DisplayName("the schema as written carries no configured tag")
    void theWrittenSchemaIsAsWritten() {
        var film = (GraphQLObjectType) written.getType("Film");
        assertThat(tags(film.getFieldDefinition("title"))).isEmpty();
        assertThat(tags(film.getFieldDefinition("releaseYear"))).containsExactly("own");
        assertThat(tags(((GraphQLEnumType) written.getType("MpaaRating")).getValue("G"))).isEmpty();
        assertThat(tags((GraphQLDirectiveContainer) written.getType("AddressOccupant"))).isEmpty();
    }

    private static List<String> tags(GraphQLDirectiveContainer element) {
        return element.getAppliedDirectives("tag").stream()
            .map(d -> (String) d.getArgument("name").getValue())
            .toList();
    }
}
