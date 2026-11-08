package no.sikt.graphitron.model.test;

import org.jooq.exception.DataAccessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_FIELD;
import static no.sikt.graphitron.model.Tables.SQL_TABLE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The fact-graph population: each graph captured once whoever asks, read only through a connection
 * that can select and nothing else, and stored beside graphs of its own catalog alone.
 */
class FactGraphsTest {

    @RegisterExtension
    static final FactGraphs.Reader FACTS = FactGraphs.reader();

    /** Documents read over the default catalog, enough of them to land in more than one store. */
    private static final List<String> DEFAULT_CATALOG = List.of("scenarios/field-key-row",
        "scenarios/as-connection", "scenarios/default-order", "scenarios/catalog-routines",
        "scenarios/producer-claim", "scenarios/code-reference-owner", "scenarios/table-binding",
        "scenarios/enum-binding");

    /** A document read with no catalog, and one read over a catalog of its own. */
    private static final String NO_CATALOG = "scenarios/result-handed-record";
    private static final String OWN_CATALOG = "defects/column-match-claim-own-catalog";

    @Test
    @DisplayName("a graph asked for from several threads at once is captured once")
    void capturedOnce() throws Exception {
        var path = "scenarios/field-key-row";
        try (var pool = Executors.newFixedThreadPool(4)) {
            var reads = new ArrayList<Future<Integer>>();
            for (int i = 0; i < 4; i++) {
                Callable<Integer> read = () -> FACTS.read(path,
                    graph -> graph.handle().dsl().fetchCount(GRAPHITRON_FIELD,
                        GRAPHITRON_FIELD.GRAPH_NAME.eq(graph.handle().graphName())));
                reads.add(pool.submit(read));
            }
            for (var read : reads) {
                assertThat(read.get()).as("fields in the graph").isPositive();
            }
        }
        assertThat(FACTS.captures(path)).isEqualTo(1);
    }

    @Test
    @DisplayName("a write inside a read is refused by the database")
    void writesAreRefused() {
        var path = "scenarios/field-key-row";
        assertThatThrownBy(() -> FACTS.read(path,
                graph -> graph.handle().dsl().deleteFrom(GRAPHITRON_FIELD).execute()))
            .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> FACTS.read(path,
                graph -> graph.handle().dsl().execute("CREATE TABLE scribble (x INT)")))
            .isInstanceOf(DataAccessException.class);
        int fields = FACTS.read(path, graph -> graph.handle().dsl().fetchCount(GRAPHITRON_FIELD,
            GRAPHITRON_FIELD.GRAPH_NAME.eq(path)));
        assertThat(fields).as("the graph's fields are all still there").isPositive();
    }

    @Test
    @DisplayName("graphs of different catalogs never share a store, and one catalog's stores agree")
    void storesAreKeyedByCatalog() {
        var asked = new ArrayList<>(DEFAULT_CATALOG);
        asked.add(NO_CATALOG);
        asked.add(OWN_CATALOG);
        FACTS.warm(asked);

        var defaultCatalog = Set.copyOf(DEFAULT_CATALOG);
        record Seen(Set<String> held, int tables) {}
        var stores = FACTS.eachStore((dsl, held) -> new Seen(held, dsl.fetchCount(SQL_TABLE)));

        for (var store : stores) {
            var kinds = store.held().stream()
                .map(graph -> defaultCatalog.contains(graph) ? "default"
                    : graph.equals(NO_CATALOG) ? "none" : graph.equals(OWN_CATALOG) ? "own" : "other")
                .filter(kind -> !kind.equals("other"))
                .distinct()
                .toList();
            assertThat(kinds).as("catalogs sharing the store holding %s", store.held()).hasSizeLessThanOrEqualTo(1);
        }
        var defaultStores = stores.stream()
            .filter(store -> store.held().stream().anyMatch(defaultCatalog::contains))
            .toList();
        assertThat(defaultStores).as("the default catalog's graphs are spread over stores").hasSizeGreaterThan(1);
        assertThat(defaultStores.stream().map(Seen::tables).distinct().toList())
            .as("sql_table, keyed by no graph, holds the same rows in every store of one catalog")
            .hasSize(1);
        assertThat(stores.stream().filter(store -> store.held().contains(NO_CATALOG))
                .map(Seen::tables).findFirst())
            .as("a store holding a graph read with no catalog holds no catalog's tables")
            .contains(0);
    }

    @Test
    @DisplayName("a path naming no plain document is refused, naming the shape a path takes")
    void unknownPathsAreRefused() {
        assertThatThrownBy(() -> FACTS.read("field-key-row", graph -> null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("scenarios/field-key-row");
    }
}
