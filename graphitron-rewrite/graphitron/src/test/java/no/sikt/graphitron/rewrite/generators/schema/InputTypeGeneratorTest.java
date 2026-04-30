package no.sikt.graphitron.rewrite.generators.schema;

import no.sikt.graphitron.javapoet.TypeSpec;
import no.sikt.graphitron.rewrite.TestSchemaHelper;
import org.junit.jupiter.api.Test;

import javax.lang.model.element.Modifier;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;

@UnitTier
class InputTypeGeneratorTest {

    private static final String INPUT_SCHEMA = """
        type Query { x: String }
        "A search filter."
        input FilterInput {
          keyword: String
          limit: Int!
          flags: [String!]
          nested: NestedInput
        }
        input NestedInput {
          label: String!
        }
        """;

    @Test
    void generate_emitsOneClassPerInputType() {
        var names = generateFor(INPUT_SCHEMA).stream().map(TypeSpec::name).toList();
        assertThat(names).contains("FilterInputType", "NestedInputType");
    }

    @Test
    void generatedClass_isPublicFinalWithSingleTypeMethod() {
        var spec = findByName(generateFor(INPUT_SCHEMA), "FilterInputType");
        assertThat(spec.modifiers()).contains(Modifier.PUBLIC, Modifier.FINAL);
        assertThat(spec.methodSpecs()).extracting(m -> m.name()).containsExactly("type");
    }

    @Test
    void typeMethod_returnsGraphQLInputObjectType() {
        var method = findByName(generateFor(INPUT_SCHEMA), "FilterInputType")
            .methodSpecs().get(0);
        assertThat(method.returnType().toString()).isEqualTo("graphql.schema.GraphQLInputObjectType");
        assertThat(method.modifiers()).contains(Modifier.PUBLIC, Modifier.STATIC);
    }

    @Test
    void typeMethod_emitsNameAndDescription() {
        var body = findByName(generateFor(INPUT_SCHEMA), "FilterInputType")
            .methodSpecs().get(0).code().toString();
        assertThat(body).contains("GraphQLInputObjectType.newInputObject()");
        assertThat(body).contains(".name(\"FilterInput\")");
        assertThat(body).contains(".description(\"A search filter.\")");
    }

    @Test
    void typeMethod_emitsEachField_withTypeReferenceByName() {
        var body = findByName(generateFor(INPUT_SCHEMA), "FilterInputType")
            .methodSpecs().get(0).code().toString();
        assertThat(body).contains(".name(\"keyword\")");
        assertThat(body).contains("GraphQLTypeReference.typeRef(\"String\")");
        assertThat(body).contains(".name(\"limit\")");
        assertThat(body).contains("GraphQLTypeReference.typeRef(\"Int\")");
    }

    @Test
    void typeMethod_wrapsNonNullAndListTypes() {
        var body = findByName(generateFor(INPUT_SCHEMA), "FilterInputType")
            .methodSpecs().get(0).code().toString();
        assertThat(body).contains("graphql.schema.GraphQLNonNull.nonNull(graphql.schema.GraphQLTypeReference.typeRef(\"Int\"))");
        assertThat(body).contains("graphql.schema.GraphQLList.list(graphql.schema.GraphQLNonNull.nonNull(graphql.schema.GraphQLTypeReference.typeRef(\"String\")))");
    }

    @Test
    void typeMethod_referencesOtherInputTypesByName() {
        var body = findByName(generateFor(INPUT_SCHEMA), "FilterInputType")
            .methodSpecs().get(0).code().toString();
        assertThat(body).contains("GraphQLTypeReference.typeRef(\"NestedInput\")");
    }

    @Test
    void generate_skipsDirectiveSupportInputTypes() {
        var names = generateFor(INPUT_SCHEMA).stream().map(TypeSpec::name).toList();
        InputDirectiveInputTypes.NAMES.forEach(directiveInput ->
            assertThat(names)
                .as("internal directive input %s must not reach emitted schema", directiveInput)
                .doesNotContain(directiveInput + "Type"));
    }

    @Test
    void generate_resultsAreAlphabeticallySorted() {
        var names = generateFor("""
            type Query { x: String }
            input ZebraFilter { x: String }
            input AlphaFilter { x: String }
            """).stream().map(TypeSpec::name).toList();
        assertThat(names).containsSubsequence("AlphaFilterType", "ZebraFilterType");
    }

    private static List<TypeSpec> generateFor(String sdl) {
        var bundle = TestSchemaHelper.buildBundle(sdl);
        return InputTypeGenerator.generate(bundle.model());
    }

    private static TypeSpec findByName(List<TypeSpec> specs, String name) {
        return specs.stream()
            .filter(s -> s.name().equals(name))
            .findFirst()
            .orElseThrow(() -> new AssertionError("no TypeSpec named " + name + " in " + specs));
    }
}
