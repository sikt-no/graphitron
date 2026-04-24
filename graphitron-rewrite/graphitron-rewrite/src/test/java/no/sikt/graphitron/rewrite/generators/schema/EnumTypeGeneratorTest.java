package no.sikt.graphitron.rewrite.generators.schema;

import no.sikt.graphitron.javapoet.TypeSpec;
import no.sikt.graphitron.rewrite.TestSchemaHelper;
import org.junit.jupiter.api.Test;

import javax.lang.model.element.Modifier;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EnumTypeGeneratorTest {

    private static final String ENUM_SCHEMA = """
        type Query { x: String }
        enum Status {
          ACTIVE
          INACTIVE
        }
        """;

    private static final String DEPRECATED_ENUM_SCHEMA = """
        type Query { x: String }
        "Status of something."
        enum Mood {
          HAPPY
          "Only used by legacy code."
          GLOOMY @deprecated(reason: "renamed to HAPPY")
        }
        """;

    @Test
    void generate_emitsOneClassPerSchemaEnum() {
        var types = generateFor(ENUM_SCHEMA);
        assertThat(types).extracting(TypeSpec::name).contains("StatusType");
    }

    @Test
    void generate_classIsPublicFinalAndContainsSingleTypeMethod() {
        var spec = findByName(generateFor(ENUM_SCHEMA), "StatusType");
        assertThat(spec.modifiers()).contains(Modifier.PUBLIC, Modifier.FINAL);
        assertThat(spec.methodSpecs()).extracting(m -> m.name()).containsExactly("type");
    }

    @Test
    void typeMethod_returnsGraphQLEnumType() {
        var method = findByName(generateFor(ENUM_SCHEMA), "StatusType")
            .methodSpecs().get(0);
        assertThat(method.returnType().toString()).isEqualTo("graphql.schema.GraphQLEnumType");
        assertThat(method.modifiers()).contains(Modifier.PUBLIC, Modifier.STATIC);
    }

    @Test
    void typeMethod_emitsNewEnumWithNameAndEachValue() {
        var body = findByName(generateFor(ENUM_SCHEMA), "StatusType")
            .methodSpecs().get(0).code().toString();
        assertThat(body).contains("GraphQLEnumType.newEnum()");
        assertThat(body).contains(".name(\"Status\")");
        assertThat(body).contains(".name(\"ACTIVE\")");
        assertThat(body).contains(".name(\"INACTIVE\")");
        assertThat(body).contains(".build()");
    }

    @Test
    void typeMethod_preservesDescriptionAndDeprecation() {
        var body = findByName(generateFor(DEPRECATED_ENUM_SCHEMA), "MoodType")
            .methodSpecs().get(0).code().toString();
        assertThat(body).contains(".description(\"Status of something.\")");
        assertThat(body).contains(".description(\"Only used by legacy code.\")");
        assertThat(body).contains(".deprecationReason(\"renamed to HAPPY\")");
    }

    @Test
    void generate_skipsIntrospectionAndFederationInjectedEnums() {
        generateFor(ENUM_SCHEMA).forEach(spec ->
            assertThat(spec.name()).doesNotStartWith("__").doesNotStartWith("_"));
    }

    @Test
    void generate_resultsAreAlphabeticallySorted() {
        var names = generateFor("""
            type Query { x: String }
            enum ZebraStatus { Z }
            enum AlphaStatus { A }
            """).stream().map(TypeSpec::name).toList();
        assertThat(names).containsSubsequence("AlphaStatusType", "ZebraStatusType");
    }

    private static List<TypeSpec> generateFor(String sdl) {
        return EnumTypeGenerator.generate(TestSchemaHelper.buildBundle(sdl).model());
    }

    private static TypeSpec findByName(List<TypeSpec> specs, String name) {
        return specs.stream()
            .filter(s -> s.name().equals(name))
            .findFirst()
            .orElseThrow(() -> new AssertionError("no TypeSpec named " + name + " in " + specs));
    }
}
