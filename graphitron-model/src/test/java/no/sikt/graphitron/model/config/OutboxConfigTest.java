package no.sikt.graphitron.model.config;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The {@code <outbox>} block's faults raised while the pom is read: a payload mapping that does not
 * parse has no SDL position to carry a stored defect, so it fails the configuration, naming what the
 * parse said.
 */
class OutboxConfigTest {

    @Test
    void aPayloadThatDoesNotParseFailsTheConfiguration() {
        assertThatThrownBy(() -> OutboxConfig.from("outbox", null, Map.of(), Map.of(),
                "operation: $scope.operationName, oops"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("<outbox>'s <payload> does not parse")
            .hasMessageContaining("expected ':' after entry key 'oops'");
    }

    @Test
    void aPayloadThatParsesIsKeptAsWritten() {
        var config = OutboxConfig.from("outbox", null, Map.of(), Map.of(),
            "operation: $scope.operationName, where: $scope.coordinate");
        assertThat(config.payload()).contains("operation: $scope.operationName, where: $scope.coordinate");
    }
}
