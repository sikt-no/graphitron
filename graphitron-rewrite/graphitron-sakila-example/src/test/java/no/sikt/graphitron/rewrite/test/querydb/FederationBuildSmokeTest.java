package no.sikt.graphitron.rewrite.test.querydb;

import com.apollographql.federation.graphqljava.printer.ServiceSDLPrinter;
import graphql.ExecutionInput;
import graphql.GraphQL;
import graphql.schema.GraphQLSchema;
import graphql.schema.GraphQLUnionType;
import no.sikt.graphitron.generated.federated.Graphitron;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;

/**
 * Locks the existing federation scaffold: {@code Graphitron.buildSchema(b -> {}, fed -> {})}
 * must accept the fixture schema and produce a schema with the federation protocol types.
 *
 * <p>This test guards against federation-jvm API drift and confirms that
 * {@code Federation.transform} accepts the generated base schema before the
 * entity-dispatch implementation moves things underneath it.
 */
@PipelineTier
class FederationBuildSmokeTest {

    @Test
    void twoArgBuildDoesNotThrow() {
        assertThatCode(() -> Graphitron.buildSchema(b -> {}, fed -> {}))
            .doesNotThrowAnyException();
    }

    @Test
    void resultHasServiceType() {
        GraphQLSchema schema = Graphitron.buildSchema(b -> {}, fed -> {});
        assertThat(schema.getObjectType("_Service"))
            .as("federation _Service type must be present")
            .isNotNull();
    }

    @Test
    void resultHasEntitiesQueryField() {
        GraphQLSchema schema = Graphitron.buildSchema(b -> {}, fed -> {});
        var entitiesField = schema.getQueryType().getFieldDefinition("_entities");
        assertThat(entitiesField)
            .as("_entities query field must be present when schema has @key types")
            .isNotNull();
    }

    @Test
    void federationCustomizerRunsAfterDefaults() {
        var called = new boolean[]{false};
        Graphitron.buildSchema(b -> {}, fed -> { called[0] = true; });
        assertThat(called[0]).as("federationCustomizer must be invoked").isTrue();
    }

    @Test
    void oneArgDelegatesToTwoArg_federationTypesPresent() {
        GraphQLSchema schema = Graphitron.buildSchema(b -> {});
        assertThat(schema.getObjectType("_Service"))
            .as("one-arg buildSchema delegates to two-arg; _Service must be present")
            .isNotNull();
    }

    /**
     * The {@code _Entity} union must list every type Graphitron classifies as a federation entity:
     * three {@code @node} types (Customer, Address, Film), the compound-key FilmActor, plus
     * Language ({@code @key(resolvable: false)} types are reference-only but federation still
     * includes them in the union). Catches the case where {@code @key} parsing or
     * {@code KeyNodeSynthesiser} silently drops a type from the entity union.
     */
    @Test
    void resultEntityUnionContainsAllFixtureEntities() {
        GraphQLSchema schema = Graphitron.buildSchema(b -> {}, fed -> {});
        var entityUnion = schema.getType("_Entity");
        assertThat(entityUnion)
            .as("_Entity union must be present when schema has @key/@node types")
            .isInstanceOf(GraphQLUnionType.class);
        var memberNames = ((GraphQLUnionType) entityUnion).getTypes().stream()
            .map(graphql.schema.GraphQLNamedType::getName)
            .toList();
        assertThat(memberNames)
            .as("_Entity union must contain every classified federation entity")
            .containsExactlyInAnyOrder("Customer", "Address", "Film", "FilmActor", "Language");
    }

    /**
     * Build-time {@code @key(fields: "id")} synthesis on {@code @node} types is visible in the
     * runtime-reconstructed {@code _Service.sdl}. {@code KeyNodeSynthesiser} attaches the
     * directive at the registry level (so the supergraph composer sees it); this test asserts
     * the emitted SDL carries it for every {@code @node} type, even ones that did not write the
     * directive themselves (Customer, Address). Locks the synthesis path against silent removal.
     */
    @Test
    void serviceSdlExposesSynthesisedKeyOnNodeTypes() {
        GraphQLSchema schema = Graphitron.buildSchema(b -> {}, fed -> {});
        var graphql = GraphQL.newGraphQL(schema).build();
        var input = ExecutionInput.newExecutionInput()
            .query("{ _service { sdl } }")
            .build();
        var result = graphql.execute(input);
        assertThat(result.getErrors()).isEmpty();
        @SuppressWarnings("unchecked")
        var service = (Map<String, Object>) ((Map<String, Object>) result.getData()).get("_service");
        var sdl = (String) service.get("sdl");
        // The federation SDL printer uses ` : ` (space-around-colon) for directive args.
        assertThat(sdl)
            .as("synthesised @key(fields: \"id\") must show on every @node type in _Service.sdl")
            .containsPattern("type\\s+Customer\\b[^{]*@key\\s*\\(\\s*fields\\s*:\\s*\"id\"")
            .containsPattern("type\\s+Address\\b[^{]*@key\\s*\\(\\s*fields\\s*:\\s*\"id\"")
            .containsPattern("type\\s+Film\\b[^{]*@key\\s*\\(\\s*fields\\s*:\\s*\"id\"");
    }

    /**
     * The printed Service SDL must carry the canonical {@code @key} directive shape:
     * {@code fields: federation__FieldSet!} (the post-rename form when {@code @link} imports
     * {@code @key} without importing {@code FieldSet}) and {@code resolvable: Boolean = true}
     * (the spec-defined default value). Subgraph composition tooling rejects the placeholder
     * shape that the previous emitter produced ({@code fields: String!} with no default on
     * {@code resolvable}).
     */
    @Test
    void serviceSdlExposesCanonicalKeyDirectiveShape() {
        GraphQLSchema schema = Graphitron.buildSchema(b -> {}, fed -> {});
        String sdl = ServiceSDLPrinter.generateServiceSDLV2(schema);
        assertThat(sdl)
            .as("@key directive must declare fields: federation__FieldSet! and resolvable: Boolean = true")
            .containsPattern(
                "directive\\s+@key\\s*\\(\\s*fields\\s*:\\s*federation__FieldSet\\s*!\\s*,\\s*"
                    + "resolvable\\s*:\\s*Boolean\\s*=\\s*true\\s*\\)\\s+repeatable\\s+on")
            .as("synthesised federation__FieldSet scalar must be registered under its SDL name")
            .contains("scalar federation__FieldSet");
    }

}
