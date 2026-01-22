package no.fellesstudentsystem.schema_transformer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("OutputSchema")
class OutputSchemaTest {

    @Test
    @DisplayName("includeAllFeatures should be true when flags is null")
    void includeAllFeatures_whenFlagsNull() {
        var schema = new OutputSchema("schema.graphql");

        assertThat(schema.includeAllFeatures()).isTrue();
        assertThat(schema.flags()).isEmpty();
    }

    @Test
    @DisplayName("includeAllFeatures should be false when flags is empty")
    void includeAllFeatures_whenFlagsEmpty() {
        var schema = new OutputSchema("schema.graphql", Set.of());

        assertThat(schema.includeAllFeatures()).isFalse();
        assertThat(schema.flags()).isEmpty();
    }

    @Test
    @DisplayName("includeAllFeatures should be false when flags has values")
    void includeAllFeatures_whenFlagsHasValues() {
        var schema = new OutputSchema("schema.graphql", Set.of("beta", "experimental"));

        assertThat(schema.includeAllFeatures()).isFalse();
        assertThat(schema.flags()).containsExactlyInAnyOrder("beta", "experimental");
    }

    @Test
    @DisplayName("shouldRemoveFederationDefinitions should use per-output override when set")
    void shouldRemoveFederationDefinitions_withOverride() {
        var schemaWithTrue = new OutputSchema("schema.graphql", null, true);
        var schemaWithFalse = new OutputSchema("schema.graphql", null, false);

        assertThat(schemaWithTrue.shouldRemoveFederationDefinitions(false)).isTrue();
        assertThat(schemaWithFalse.shouldRemoveFederationDefinitions(true)).isFalse();
    }

    @Test
    @DisplayName("shouldRemoveFederationDefinitions should use global default when not set")
    void shouldRemoveFederationDefinitions_withoutOverride() {
        var schema = new OutputSchema("schema.graphql");

        assertThat(schema.shouldRemoveFederationDefinitions(true)).isTrue();
        assertThat(schema.shouldRemoveFederationDefinitions(false)).isFalse();
    }
}
