package no.sikt.graphitron.rewrite.test;

import graphql.schema.GraphQLSchema;
import no.sikt.graphitron.generated.federated.Graphitron;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Locks the existing federation scaffold: {@code Graphitron.buildSchema(b -> {}, fed -> {})}
 * must accept the fixture schema and produce a schema with the federation protocol types.
 *
 * <p>This test guards against federation-jvm API drift and confirms that
 * {@code Federation.transform} accepts the generated base schema before the
 * entity-dispatch implementation moves things underneath it.
 */
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
}
