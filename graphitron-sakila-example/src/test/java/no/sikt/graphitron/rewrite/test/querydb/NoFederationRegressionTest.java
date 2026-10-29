package no.sikt.graphitron.rewrite.test.querydb;

import graphql.schema.GraphQLSchema;
import no.sikt.graphitron.generated.Graphitron;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;

/**
 * Regression guard: the shared {@code schema.graphqls} fixture (no federation {@code @link})
 * builds a non-federation schema. {@code _Service}, {@code _entities}, and {@code _Entity}
 * must not appear; {@code Graphitron.buildSchema(b -> {})} must return a vanilla schema
 * with no federation wrapping.
 *
 * <p>Catches the case where federation accidentally turns on for a schema with no
 * {@code @link} (a regression in {@code FederationLinkApplier} or
 * {@code KeyNodeSynthesiser}). Companion to {@link FederationBuildSmokeTest}, which
 * runs against the federated fixture; this one runs against the shared one.
 */
@PipelineTier
class NoFederationRegressionTest {

    @Test
    void sharedFixture_buildsNonFederatedSchema() {
        GraphQLSchema schema = Graphitron.buildSchema(b -> {});
        assertThat(schema.getObjectType("_Service"))
            .as("shared fixture has no @link → _Service must not be present")
            .isNull();
        assertThat(schema.getQueryType().getFieldDefinition("_entities"))
            .as("_entities query field must not exist on a non-federated schema")
            .isNull();
        assertThat(schema.getType("_Entity"))
            .as("_Entity union must not exist on a non-federated schema")
            .isNull();
    }

    @Test
    void sharedFixture_doesNotExposeFederationTwoArgOverload() {
        // The shared facade emits the one-arg build(Consumer<SchemaBuilder>) and the no-argument
        // form beside it, since federationLink is false. A two-arg form (a federation customizer)
        // must not exist; verify by reflection.
        var parameterCounts = java.util.Arrays.stream(Graphitron.class.getDeclaredMethods())
            .filter(m -> "buildSchema".equals(m.getName()))
            .map(java.lang.reflect.Method::getParameterCount)
            .sorted()
            .toList();
        assertThat(parameterCounts)
            .as("shared facade emits buildSchema() and buildSchema(customizer) only, no federation two-arg form")
            .containsExactly(0, 1);
    }
}
