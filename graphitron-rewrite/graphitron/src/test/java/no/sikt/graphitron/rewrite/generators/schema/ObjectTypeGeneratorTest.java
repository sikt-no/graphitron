package no.sikt.graphitron.rewrite.generators.schema;

import graphql.schema.GraphQLFieldDefinition;
import graphql.schema.GraphQLList;
import graphql.schema.GraphQLNonNull;
import graphql.schema.GraphQLObjectType;
import graphql.schema.GraphQLTypeReference;
import no.sikt.graphitron.javapoet.TypeSpec;
import no.sikt.graphitron.rewrite.GraphitronSchema;
import no.sikt.graphitron.rewrite.TestSchemaHelper;
import no.sikt.graphitron.rewrite.model.GraphitronType;
import org.junit.jupiter.api.Test;

import javax.lang.model.element.Modifier;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

    // ===== @asConnection synthesis =====

    private static final String WITH_AS_CONNECTION = """
        type Query {
          films: [Film!]! @asConnection
        }
        type Film { id: ID! }
        """;

    @Test
    void asConnection_replacesReturnTypeWithConnectionRef() {
        var body = findByName(generateFor(WITH_AS_CONNECTION), "QueryType").methodSpecs().get(0).code().toString();
        // should reference the synthesised Connection type, not the bare list
        assertThat(body).contains("typeRef(\"QueryFilmsConnection\")");
        assertThat(body).doesNotContain("graphql.schema.GraphQLList.list");
    }

    @Test
    void asConnection_addsFirstAndAfterArguments() {
        var body = findByName(generateFor(WITH_AS_CONNECTION), "QueryType").methodSpecs().get(0).code().toString();
        assertThat(body).contains(".name(\"first\")");
        assertThat(body).contains(".name(\"after\")");
    }

    @Test
    void asConnection_firstArgumentHasDefaultPageSize() {
        var body = findByName(generateFor(WITH_AS_CONNECTION), "QueryType").methodSpecs().get(0).code().toString();
        assertThat(body).contains(".defaultValueProgrammatic(100)");
    }

    @Test
    void asConnection_doesNotEmitAsConnectionDirective() {
        var body = findByName(generateFor(WITH_AS_CONNECTION), "QueryType").methodSpecs().get(0).code().toString();
        assertThat(body).doesNotContain("\"asConnection\"");
    }

    @Test
    void asConnection_structuralFieldIsUnchanged() {
        // Hand-written FilmsConnection in BASIC schema: no @asConnection directive,
        // connection type exists structurally — should emit as-is (bare list return type).
        // Here we test that the Query.films field in BASIC is emitted as a list.
        var body = findByName(generateFor(BASIC), "QueryType").methodSpecs().get(0).code().toString();
        assertThat(body).contains("graphql.schema.GraphQLList.list");
    }

    // ===== Direct ConnectionType variant emission (classifier-independent) =====

    @Test
    void connectionType_directVariant_emitsFieldsFromSchemaType() {
        // Build the schemaType programmatically — bypassing the classifier so a
        // classifier bug cannot mask an emitter bug (plan-shipped gap).
        var edgeSchemaType = GraphQLObjectType.newObject().name("FilmEdge")
            .field(GraphQLFieldDefinition.newFieldDefinition().name("cursor")
                .type(GraphQLNonNull.nonNull(GraphQLTypeReference.typeRef("String"))).build())
            .field(GraphQLFieldDefinition.newFieldDefinition().name("node")
                .type(GraphQLTypeReference.typeRef("Film")).build())
            .build();
        var connSchemaType = GraphQLObjectType.newObject().name("FilmConnection")
            .field(GraphQLFieldDefinition.newFieldDefinition().name("edges")
                .type(GraphQLNonNull.nonNull(GraphQLList.list(GraphQLNonNull.nonNull(
                    GraphQLTypeReference.typeRef("FilmEdge"))))).build())
            .field(GraphQLFieldDefinition.newFieldDefinition().name("nodes")
                .type(GraphQLNonNull.nonNull(GraphQLList.list(
                    GraphQLTypeReference.typeRef("Film")))).build())
            .field(GraphQLFieldDefinition.newFieldDefinition().name("pageInfo")
                .type(GraphQLNonNull.nonNull(GraphQLTypeReference.typeRef("PageInfo"))).build())
            .build();

        var types = new LinkedHashMap<String, GraphitronType>();
        types.put("Query", new GraphitronType.RootType("Query", null));
        types.put("FilmConnection", new GraphitronType.ConnectionType(
            "FilmConnection", null, "Film", "FilmEdge", true, false, connSchemaType));
        types.put("FilmEdge", new GraphitronType.EdgeType(
            "FilmEdge", null, "Film", true, false, edgeSchemaType));

        var schema = new GraphitronSchema(types, Map.of());
        var assembled = TestSchemaHelper.buildBundle("type Query { x: Int }").assembled();
        var specs = ObjectTypeGenerator.generate(schema, assembled);

        var connBody = findByName(specs, "FilmConnectionType").methodSpecs().get(0).code().toString();
        assertThat(connBody).contains(".name(\"FilmConnection\")");
        assertThat(connBody).contains(".name(\"edges\")");
        assertThat(connBody).contains(".name(\"nodes\")");
        assertThat(connBody).contains(".name(\"pageInfo\")");

        var edgeBody = findByName(specs, "FilmEdgeType").methodSpecs().get(0).code().toString();
        assertThat(edgeBody).contains(".name(\"FilmEdge\")");
        assertThat(edgeBody).contains(".name(\"cursor\")");
        assertThat(edgeBody).contains(".name(\"node\")");
    }

    private static List<TypeSpec> generateFor(String sdl) {
        var bundle = TestSchemaHelper.buildBundle(sdl);
        return ObjectTypeGenerator.generate(bundle.model(), bundle.assembled());
    }

    private static TypeSpec findByName(List<TypeSpec> specs, String name) {
        return specs.stream()
            .filter(s -> s.name().equals(name))
            .findFirst()
            .orElseThrow(() -> new AssertionError("no TypeSpec named " + name + " in " + specs));
    }
}
