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
            .contains(".name(\"shareable\")")
            .contains(".withAppliedDirective(")
            .contains("graphql.parser.Parser.parseValue(\"\\\"id\\\"\")");
    }

    @Test
    void fieldDefinition_emitsWithAppliedDirective_forFieldLevelSurvivors() {
        var userBody = findTypeBody(FEDERATION_SDL, "UserType");
        assertThat(userBody).contains(".name(\"external\")");
    }

    @Test
    void listArgumentValues_emitAsArrayValueLiteral() {
        var queryBody = findTypeBody(CUSTOM_DIR_SDL, "QueryType");
        assertThat(queryBody)
            .contains(".name(\"auth\")")
            .contains(".name(\"roles\")")
            .contains("graphql.parser.Parser.parseValue(")
            .contains("\\\"admin\\\"")
            .contains("\\\"ops\\\"");
    }

    /**
     * Regression guard: the previous hand-rolled emitter rendered {@code FloatValue} as a
     * {@code StringValue} (toPlainString()), changing the AST shape and breaking any consumer
     * (e.g. federation-jvm) that casts the literal to {@link graphql.language.FloatValue}.
     * Delegating to {@code ValuesResolver.valueToLiteral} preserves the FloatValue shape.
     */
    @Test
    void floatArgumentValues_preserveFloatValueShape() {
        String sdl = """
            directive @threshold(min: Float!) on FIELD_DEFINITION
            type Query {
              risky: String @threshold(min: 3.14)
            }
            """;
        var queryBody = findTypeBody(sdl, "QueryType");
        assertThat(queryBody)
            .contains(".name(\"threshold\")")
            .contains(".name(\"min\")")
            .contains("graphql.parser.Parser.parseValue(\"3.14\")");
    }

    /**
     * Regression guard: graphql-java coerces Boolean-valued arguments to Java {@code Boolean}
     * (internal state). Delegating to {@code ValuesResolver.valueToLiteral} renders them as
     * {@link graphql.language.BooleanValue}, which is the shape federation-jvm casts to for
     * arguments like {@code @key(resolvable: false)}.
     */
    @Test
    void booleanArgumentValues_emitAsBooleanValueLiteral() {
        String sdl = """
            directive @flag(on: Boolean!) on FIELD_DEFINITION
            type Query {
              x: String @flag(on: true)
            }
            """;
        var queryBody = findTypeBody(sdl, "QueryType");
        assertThat(queryBody)
            .contains(".name(\"flag\")")
            .contains(".name(\"on\")")
            .contains("graphql.parser.Parser.parseValue(\"true\")");
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
        var bundle = TestSchemaHelper.buildBundle(sdl);
        var specs = ObjectTypeGenerator.generate(bundle.model(), bundle.assembled());
        TypeSpec spec = specs.stream()
            .filter(s -> s.name().equals(typeName))
            .findFirst()
            .orElseThrow(() -> new AssertionError("no " + typeName + " in " + specs));
        return spec.methodSpecs().get(0).code().toString();
    }

    @SuppressWarnings("unused")
    private static List<TypeSpec> all(String sdl) {
        var bundle = TestSchemaHelper.buildBundle(sdl);
        return ObjectTypeGenerator.generate(bundle.model(), bundle.assembled());
    }
}
