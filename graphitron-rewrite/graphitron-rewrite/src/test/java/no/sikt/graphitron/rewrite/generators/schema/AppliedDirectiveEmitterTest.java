package no.sikt.graphitron.rewrite.generators.schema;

import no.sikt.graphitron.javapoet.TypeSpec;
import no.sikt.graphitron.rewrite.TestSchemaHelper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AppliedDirectiveEmitterTest {

    private static final String FEDERATION_SDL = """
        directive @key(fields: String!) on OBJECT
        directive @external on FIELD_DEFINITION
        directive @shareable on OBJECT | FIELD_DEFINITION
        type Query { user: User }
        type User @key(fields: "id") @shareable {
          id: ID!
          name: String @external
        }
        """;

    private static final String CUSTOM_DIR_SDL = """
        directive @auth(roles: [String!]) on FIELD_DEFINITION
        type Query {
          secret: String @auth(roles: ["admin", "ops"])
        }
        """;

    @Test
    void objectType_emitsWithAppliedDirective_forSurvivorDirectives() {
        var userBody = findTypeBody(FEDERATION_SDL, "UserType");
        assertThat(userBody)
            .contains("graphql.schema.GraphQLAppliedDirective.newDirective()")
            .contains(".name(\"key\")")
            .contains(".valueProgrammatic(\"id\")")
            .contains(".name(\"shareable\")")
            .contains(".withAppliedDirective(");
    }

    @Test
    void fieldDefinition_emitsWithAppliedDirective_forFieldLevelSurvivors() {
        var userBody = findTypeBody(FEDERATION_SDL, "UserType");
        assertThat(userBody).contains(".name(\"external\")");
    }

    @Test
    void listArgumentValues_translateViaListOf() {
        var queryBody = findTypeBody(CUSTOM_DIR_SDL, "QueryType");
        assertThat(queryBody)
            .contains(".name(\"auth\")")
            .contains(".name(\"roles\")")
            .contains("java.util.List.of(\"admin\", \"ops\")");
    }

    @Test
    void generatorOnlyDirectives_areSkipped() {
        String sdl = """
            type Query @table(name: "query_root") { x: String }
            """;
        var queryBody = findTypeBody(sdl, "QueryType");
        assertThat(queryBody).doesNotContain(".name(\"table\")");
        assertThat(queryBody).doesNotContain("query_root");
    }

    @Test
    void typesWithoutSurvivors_emitNoWithAppliedDirective() {
        String sdl = "type Query { x: String } type Film { id: ID! }";
        var filmBody = findTypeBody(sdl, "FilmType");
        assertThat(filmBody).doesNotContain(".withAppliedDirective(");
    }

    private static String findTypeBody(String sdl, String typeName) {
        var specs = ObjectTypeGenerator.generate(TestSchemaHelper.buildBundle(sdl).assembled());
        TypeSpec spec = specs.stream()
            .filter(s -> s.name().equals(typeName))
            .findFirst()
            .orElseThrow(() -> new AssertionError("no " + typeName + " in " + specs));
        return spec.methodSpecs().get(0).code().toString();
    }

    @SuppressWarnings("unused")
    private static List<TypeSpec> all(String sdl) {
        return ObjectTypeGenerator.generate(TestSchemaHelper.buildBundle(sdl).assembled());
    }
}
