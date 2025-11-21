package no.sikt.graphql.helpers.resolvers;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static no.sikt.graphql.helpers.resolvers.EnvironmentHandler.flattenArgumentKeys;
import static org.assertj.core.api.Assertions.assertThat;

class EnvironmentHandlerTest {

    @Test
    @DisplayName("flattens simple arguments")
    void listedInputArguments() {
        Map<String, Object> arguments = Map.of(
                "in",
                List.of(
                        Map.of(
                                "id", "123",
                                "name", "test"
                        ),
                        Map.of(
                                "id", "123",
                                "active", true
                        )
                )
        );

        assertThat(flattenArgumentKeys(arguments, ""))
                .containsExactlyInAnyOrder("in", "in/id", "in/name", "in/active");
    }
}