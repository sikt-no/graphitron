package no.sikt.graphitron.render;

import com.palantir.javapoet.ClassName;
import no.sikt.graphitron.emit.ConnectionsQuery;
import no.sikt.graphitron.model.test.FactGraphs;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What the {@code scenarios/connection-classes} fact document cannot say about the class a
 * connection producer builds. The document claims the decision itself, one
 * {@code graphitron_field_connection} row per field returning a connection, which
 * {@link ConnectionsQuery#byField} reads; what is left is the generator's half: that
 * {@link ConnectionConstruction#classOf} names the class after the connection type under the
 * helper's {@code Connections}, and that a field the store states no row for is a generator bug
 * rather than a name to guess. That the constructor a producer calls is the one the
 * class declares is the compiler's to say: the document is a scenario, so its generated producers
 * and classes compile together in this module's test sources.
 */
@PipelineTier
class ConnectionClassPipelineTest {

    @RegisterExtension
    static final FactGraphs.Reader FACTS = FactGraphs.reader();

    private static final ClassName HELPER = ClassName.get("fixture.util", "ConnectionHelper");

    private static ConnectionsQuery.FieldConnections connections;

    @BeforeAll
    static void read() {
        connections = FACTS.read("scenarios/connection-classes", graph -> ConnectionsQuery.byField(graph.handle()));
    }

    @Test
    void theClassIsNamedAfterTheConnectionType_twoClassesOverOneEdge() {
        assertThat(ConnectionConstruction.classOf(HELPER, connections.at("Query", "films")).canonicalName())
            .isEqualTo("fixture.util.Connections.FilmsConnection");
        assertThat(ConnectionConstruction.classOf(HELPER, connections.at("Query", "filmsPage")).canonicalName())
            .isEqualTo("fixture.util.Connections.FilmsPage");
    }

    @Test
    void aFieldTheStoreStatesNoConnectionFor_isAGeneratorBug() {
        assertThatThrownBy(() -> connections.at("Film", "title"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("'Film.title' has no connection type in the store");
    }
}
