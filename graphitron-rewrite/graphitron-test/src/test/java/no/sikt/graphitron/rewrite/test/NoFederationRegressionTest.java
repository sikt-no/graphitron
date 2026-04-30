package no.sikt.graphitron.rewrite.test;

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
        // Compile-time check: the shared facade emits only a one-arg
        // build(Consumer<SchemaBuilder>) since federationLink is false.
        // Any attempt to call the two-arg form should be a compile error;
        // verify by reflection that the two-arg method does not exist.
        var methods = java.util.Arrays.stream(Graphitron.class.getDeclaredMethods())
            .filter(m -> "buildSchema".equals(m.getName()))
            .toList();
        assertThat(methods)
            .as("shared facade emits exactly one buildSchema overload (no federation two-arg form)")
            .hasSize(1);
        assertThat(methods.get(0).getParameterCount())
            .as("the single overload is the one-arg form")
            .isEqualTo(1);
    }
}
