package no.sikt.graphitron.model;

import graphql.schema.GraphQLDirectiveContainer;
import graphql.schema.GraphQLEnumType;
import graphql.schema.GraphQLInputObjectType;
import graphql.schema.GraphQLInterfaceType;
import graphql.schema.GraphQLObjectType;
import graphql.schema.GraphQLSchema;
import no.sikt.graphitron.model.schema.SchemaAssembly;
import no.sikt.graphitron.model.test.CapturedStore;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What {@code <schemaInput feature>} puts on a file's elements, read off the two schemas capture
 * hands back: the post-synthesis schema carries {@code @feature(flags: ["<feature>"])} on every kind
 * of element the rule names, and the schema as written carries none of it.
 *
 * <p>One rule produces the applications, {@code graphitron_configured_feature}, which the emitted
 * schema applies. The kinds are the ones {@code @feature}'s locations admit: fields of objects and
 * interfaces, wherever the type's sites declare them, input fields, field arguments and enum values.
 * A type is never flagged, a union included, and an element carrying a {@code @feature} of its own
 * keeps that one alone.
 */
class ConfiguredFeatureTest {

    static final String DEFINITION = """
        directive @feature(flags: [String!]!) on FIELD_DEFINITION | ARGUMENT_DEFINITION \
        | INPUT_FIELD_DEFINITION | ENUM_VALUE
        """;

    private static final String SDL = DEFINITION + """
        type Query {
          film(id: ID): Film
          search(filter: FilmFilter): [Result]
          rated: Rating
        }
        type Film {
          title: String
          year: Int @feature(flags: ["own"])
        }
        extend type Film { length: Int }
        interface Named { name: String }
        input FilmFilter { title: String }
        enum Rating { G PG }
        union Result = Film
        """;

    private static GraphQLSchema emitted;
    private static GraphQLSchema written;
    private static GraphQLSchema both;

    @BeforeAll
    static void capture(@TempDir Path tmp) {
        try (var store = CapturedStore.featured(tmp.resolve("feature"), SDL, "catalog", null)) {
            emitted = ((SchemaAssembly.Assembled) store.schema().synthesised().orElseThrow()).schema();
            written = ((SchemaAssembly.Assembled) store.schema().written().assembly()).schema();
        }
        try (var store = CapturedStore.featured(tmp.resolve("both"), SDL, "catalog", "public")) {
            both = ((SchemaAssembly.Assembled) store.schema().synthesised().orElseThrow()).schema();
        }
    }

    @Test
    @DisplayName("fields, wherever the type's sites declare them, and arguments take the file's feature")
    void fieldsAndArguments() {
        var query = (GraphQLObjectType) emitted.getType("Query");
        assertThat(flags(query.getFieldDefinition("film"))).containsExactly(List.of("catalog"));
        assertThat(flags(query.getFieldDefinition("film").getArgument("id")))
            .containsExactly(List.of("catalog"));
        var film = (GraphQLObjectType) emitted.getType("Film");
        assertThat(flags(film.getFieldDefinition("title"))).containsExactly(List.of("catalog"));
        assertThat(flags(film.getFieldDefinition("length"))).as("declared by the extension")
            .containsExactly(List.of("catalog"));
        var named = (GraphQLInterfaceType) emitted.getType("Named");
        assertThat(flags(named.getFieldDefinition("name"))).containsExactly(List.of("catalog"));
    }

    @Test
    @DisplayName("input fields and enum values take the file's feature, and types, unions included, none")
    void theOtherKinds() {
        var filter = (GraphQLInputObjectType) emitted.getType("FilmFilter");
        assertThat(flags(filter.getFieldDefinition("title"))).containsExactly(List.of("catalog"));
        var rating = (GraphQLEnumType) emitted.getType("Rating");
        assertThat(flags(rating.getValue("G"))).containsExactly(List.of("catalog"));
        assertThat(flags(rating.getValue("PG"))).containsExactly(List.of("catalog"));
        assertThat(flags((GraphQLDirectiveContainer) emitted.getType("Result"))).isEmpty();
        assertThat(flags((GraphQLDirectiveContainer) emitted.getType("Film"))).isEmpty();
        assertThat(flags(rating)).isEmpty();
        assertThat(flags(filter)).isEmpty();
    }

    @Test
    @DisplayName("an element with a @feature of its own keeps exactly what the author wrote")
    void anAuthoredFeatureStands() {
        var film = (GraphQLObjectType) emitted.getType("Film");
        assertThat(flags(film.getFieldDefinition("year"))).containsExactly(List.of("own"));
    }

    @Test
    @DisplayName("the schema as written carries no configured feature")
    void theWrittenSchemaIsAsWritten() {
        var film = (GraphQLObjectType) written.getType("Film");
        assertThat(flags(film.getFieldDefinition("title"))).isEmpty();
        assertThat(flags(film.getFieldDefinition("year"))).containsExactly(List.of("own"));
        assertThat(flags(((GraphQLEnumType) written.getType("Rating")).getValue("G"))).isEmpty();
    }

    @Test
    @DisplayName("a feature and a tag on one entry both land, independently")
    void aFeatureBesideATag() {
        var film = (GraphQLObjectType) both.getType("Film");
        assertThat(flags(film.getFieldDefinition("title"))).containsExactly(List.of("catalog"));
        assertThat(tags(film.getFieldDefinition("title"))).containsExactly("public");
        assertThat(flags(film.getFieldDefinition("year"))).containsExactly(List.of("own"));
        assertThat(tags(film.getFieldDefinition("year"))).containsExactly("public");
        assertThat(flags((GraphQLDirectiveContainer) both.getType("Result"))).isEmpty();
        assertThat(tags((GraphQLDirectiveContainer) both.getType("Result"))).containsExactly("public");
    }

    @SuppressWarnings("unchecked")
    private static List<List<String>> flags(GraphQLDirectiveContainer element) {
        return element.getAppliedDirectives("feature").stream()
            .map(d -> (List<String>) d.getArgument("flags").getValue())
            .toList();
    }

    private static List<String> tags(GraphQLDirectiveContainer element) {
        return element.getAppliedDirectives("tag").stream()
            .map(d -> (String) d.getArgument("name").getValue())
            .toList();
    }
}
