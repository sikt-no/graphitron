package no.fellesstudentsystem.schema_transformer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("OutputSchema")
class OutputSchemaTest {

    @Test
    @DisplayName("flags() should return empty set when flags is null")
    void flags_whenNull() {
        var schema = new OutputSchema("schema.graphql");

        assertThat(schema.flags()).isEmpty();
    }

    @Test
    @DisplayName("flags() should return empty set when flags is empty")
    void flags_whenEmpty() {
        var schema = new OutputSchema("schema.graphql", Set.of());

        assertThat(schema.flags()).isEmpty();
    }

    @Test
    @DisplayName("flags() should return the specified flags")
    void flags_whenHasValues() {
        var schema = new OutputSchema("schema.graphql", Set.of("beta", "experimental"));

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

    @Test
    @DisplayName("includeAllFeatures should return true when flags is null (default)")
    void includeAllFeatures_whenFlagsNull() {
        var schema = new OutputSchema("schema.graphql");

        assertThat(schema.includeAllFeatures()).isTrue();
    }

    @Test
    @DisplayName("includeAllFeatures should return false when flags is specified")
    void includeAllFeatures_whenFlagsSpecified() {
        var schema = new OutputSchema("schema.graphql", Set.of("beta"));

        assertThat(schema.includeAllFeatures()).isFalse();
    }

    @Test
    @DisplayName("includeAllFeatures should return false when flags is empty set")
    void includeAllFeatures_whenFlagsEmpty() {
        var schema = new OutputSchema("schema.graphql", Set.of());

        assertThat(schema.includeAllFeatures()).isFalse();
    }
}
