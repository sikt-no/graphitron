package no.sikt.graphitron.render;

import com.palantir.javapoet.ClassName;
import no.sikt.graphitron.emit.ConnectionsQuery;
import no.sikt.graphitron.model.test.FactGraphs;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The class each connection producer builds and what its constructor takes, read from the store at
 * the field's grain ({@link ConnectionsQuery#byField}): the connection type the field returns names
 * the class ({@link ConnectionConstruction#classOf}), {@code TOTAL_COUNT} decides whether the
 * constructor takes a count and {@code FACETS} whether it takes facets. One field per kind of
 * connection field, over the {@code scenarios/connection-classes} fact document, which claims the
 * rows this reads. That the constructor a producer calls is the one the class declares is the
 * compiler's to say: the document is a scenario, so its generated producers and classes compile
 * together in this module's test sources.
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

    /** The class a producer builds, and whether its constructor takes a count and facets. */
    private record Built(String className, boolean counts, boolean faceted) {}

    @Test
    void eachConnectionFieldBuildsItsTypesClass_takingWhatItsPositionsSay() {
        var expected = Map.of(
            "Query.languages", new Built("fixture.util.Connections.QueryLanguagesConnection", true, false),
            "Query.filmsByTitle", new Built("fixture.util.Connections.QueryFilmsByTitleConnection", true, true),
            "Query.filmsPage", new Built("fixture.util.Connections.FilmsPage", false, false),
            "Query.films", new Built("fixture.util.Connections.FilmsConnection", true, false),
            "Query.people", new Built("fixture.util.Connections.QueryPeopleConnection", true, false),
            "Film.actors", new Built("fixture.util.Connections.ActorsConnection", true, false));
        assertThat(connections.byCoordinate().keySet()).containsExactlyInAnyOrderElementsOf(expected.keySet());
        expected.forEach((coordinate, built) -> {
            var parts = coordinate.split("\\.");
            var connection = connections.at(parts[0], parts[1]);
            assertThat(new Built(ConnectionConstruction.classOf(HELPER, connection).canonicalName(),
                connection.counts(), connection.faceted())).as(coordinate).isEqualTo(built);
        });
    }

    @Test
    void aFieldTheStoreStatesNoConnectionFor_isAGeneratorBug() {
        assertThatThrownBy(() -> connections.at("Film", "title"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("'Film.title' has no connection type in the store");
    }
}
