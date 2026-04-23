package no.sikt.graphitron.rewrite.generators.schema;

import no.sikt.graphitron.javapoet.TypeSpec;
import no.sikt.graphitron.rewrite.TestSchemaHelper;
import org.junit.jupiter.api.Test;

import javax.lang.model.element.Modifier;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ObjectTypeGeneratorTest {

    private static final String BASIC = """
        type Query {
          film(id: ID!): Film
          films: [Film!]!
        }
        "Feature-length motion picture."
        type Film implements Node {
          id: ID!
          title: String!
          director: Person
          "@deprecated tag is preserved."
          rating: String @deprecated(reason: "use 'score'")
        }
        type Person {
          id: ID!
          name: String!
        }
        interface Node {
          id: ID!
        }
        union SearchHit = Film | Person
        """;

    @Test
    void generate_emitsOneClassPerObjectInterfaceAndUnion() {
        var names = generateFor(BASIC).stream().map(TypeSpec::name).toList();
        assertThat(names).contains("QueryType", "FilmType", "PersonType", "NodeType", "SearchHitType");
    }

    @Test
    void objectType_classIsPublicFinalWithSingleTypeMethod() {
        var spec = findByName(generateFor(BASIC), "FilmType");
        assertThat(spec.modifiers()).contains(Modifier.PUBLIC, Modifier.FINAL);
        assertThat(spec.methodSpecs()).extracting(m -> m.name()).containsExactly("type");
    }

    @Test
    void objectType_returnsGraphQLObjectType() {
        var method = findByName(generateFor(BASIC), "FilmType").methodSpecs().get(0);
        assertThat(method.returnType().toString()).isEqualTo("graphql.schema.GraphQLObjectType");
        assertThat(method.modifiers()).contains(Modifier.PUBLIC, Modifier.STATIC);
    }

    @Test
    void objectType_emitsNameAndDescription() {
        var body = findByName(generateFor(BASIC), "FilmType").methodSpecs().get(0).code().toString();
        assertThat(body).contains("GraphQLObjectType.newObject()");
        assertThat(body).contains(".name(\"Film\")");
        assertThat(body).contains(".description(\"Feature-length motion picture.\")");
    }

    @Test
    void objectType_emitsFieldsWithNonNullWrapping() {
        var body = findByName(generateFor(BASIC), "FilmType").methodSpecs().get(0).code().toString();
        assertThat(body).contains(".name(\"title\")");
        assertThat(body).contains("graphql.schema.GraphQLNonNull.nonNull(graphql.schema.GraphQLTypeReference.typeRef(\"String\"))");
    }

    @Test
    void objectType_emitsNullableFieldsAsBareTypeRef() {
        var body = findByName(generateFor(BASIC), "FilmType").methodSpecs().get(0).code().toString();
        assertThat(body).contains(".name(\"director\")");
        assertThat(body).contains("GraphQLTypeReference.typeRef(\"Person\")");
    }

    @Test
    void objectType_preservesDeprecation() {
        var body = findByName(generateFor(BASIC), "FilmType").methodSpecs().get(0).code().toString();
        assertThat(body).contains(".deprecate(\"use 'score'\")");
    }

    @Test
    void objectType_emitsWithInterface_forImplementers() {
        var body = findByName(generateFor(BASIC), "FilmType").methodSpecs().get(0).code().toString();
        assertThat(body).contains(".withInterface(graphql.schema.GraphQLTypeReference.typeRef(\"Node\"))");
    }

    @Test
    void objectType_emitsArgumentsOnFields() {
        var body = findByName(generateFor(BASIC), "QueryType").methodSpecs().get(0).code().toString();
        assertThat(body).contains(".name(\"film\")");
        assertThat(body).contains("GraphQLArgument.newArgument()");
        assertThat(body).contains(".name(\"id\")");
        assertThat(body).contains("graphql.schema.GraphQLNonNull.nonNull(graphql.schema.GraphQLTypeReference.typeRef(\"ID\"))");
    }

    @Test
    void objectType_wrapsListAndNonNullCombined() {
        var body = findByName(generateFor(BASIC), "QueryType").methodSpecs().get(0).code().toString();
        assertThat(body).contains("graphql.schema.GraphQLNonNull.nonNull(graphql.schema.GraphQLList.list(graphql.schema.GraphQLNonNull.nonNull(graphql.schema.GraphQLTypeReference.typeRef(\"Film\"))))");
    }

    @Test
    void interfaceType_returnsGraphQLInterfaceType() {
        var method = findByName(generateFor(BASIC), "NodeType").methodSpecs().get(0);
        assertThat(method.returnType().toString()).isEqualTo("graphql.schema.GraphQLInterfaceType");
        var body = method.code().toString();
        assertThat(body).contains("GraphQLInterfaceType.newInterface()");
        assertThat(body).contains(".name(\"Node\")");
        assertThat(body).contains(".name(\"id\")");
    }

    @Test
    void unionType_returnsGraphQLUnionType_andEmitsPossibleTypes() {
        var method = findByName(generateFor(BASIC), "SearchHitType").methodSpecs().get(0);
        assertThat(method.returnType().toString()).isEqualTo("graphql.schema.GraphQLUnionType");
        var body = method.code().toString();
        assertThat(body).contains("GraphQLUnionType.newUnionType()");
        assertThat(body).contains(".possibleType(graphql.schema.GraphQLTypeReference.typeRef(\"Film\"))");
        assertThat(body).contains(".possibleType(graphql.schema.GraphQLTypeReference.typeRef(\"Person\"))");
    }

    @Test
    void generate_skipsIntrospectionAndFederationInjectedTypes() {
        generateFor(BASIC).forEach(spec ->
            assertThat(spec.name()).doesNotStartWith("__").doesNotStartWith("_"));
    }

    @Test
    void generate_resultsAreAlphabeticallySorted() {
        var names = generateFor(BASIC).stream().map(TypeSpec::name).toList();
        assertThat(names).isSorted();
    }

    private static List<TypeSpec> generateFor(String sdl) {
        return ObjectTypeGenerator.generate(TestSchemaHelper.buildBundle(sdl).assembled());
    }

    private static TypeSpec findByName(List<TypeSpec> specs, String name) {
        return specs.stream()
            .filter(s -> s.name().equals(name))
            .findFirst()
            .orElseThrow(() -> new AssertionError("no TypeSpec named " + name + " in " + specs));
    }
}
