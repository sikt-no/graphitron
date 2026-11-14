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

import static no.sikt.graphitron.model.Tables.CODE_CLASS;
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

    /** Documents read over a classpath fixture, by the fixture each names. */
    private static final java.util.Map<String, String> FIXTURE_CLASSPATH = java.util.Map.of(
        "defects/type-backing-partition-library-named", "library-named",
        "defects/type-backing-partition-library-nameless", "library-nameless",
        "scenarios/type-backing-partition-corpus-and-library", "corpus-and-library-named",
        "scenarios/code-reference-copied-entry", "service-copy",
        "defects/classpath-order-code-reference", "corpus-and-library-named");

    @Test
    @DisplayName("graphs of different classpaths never share a store, so a class read off a fixture is no row beside a corpus graph")
    void storesAreKeyedByClasspath() {
        var asked = new ArrayList<>(DEFAULT_CATALOG);
        asked.addAll(FIXTURE_CLASSPATH.keySet());
        FACTS.warm(asked);

        var corpus = Set.copyOf(DEFAULT_CATALOG);
        record Seen(Set<String> held, int libraryClasses) {}
        var stores = FACTS.eachStore((dsl, held) -> new Seen(held,
            dsl.fetchCount(CODE_CLASS, CODE_CLASS.CLASS_NAME.eq("lib.HolderService"))));

        for (var store : stores) {
            var classpaths = store.held().stream()
                .map(graph -> corpus.contains(graph) ? "corpus" : FIXTURE_CLASSPATH.getOrDefault(graph, "other"))
                .filter(kind -> !kind.equals("other"))
                .distinct()
                .toList();
            assertThat(classpaths).as("classpaths sharing the store holding %s", store.held())
                .hasSizeLessThanOrEqualTo(1);
        }
        assertThat(stores.stream().filter(store -> store.held().stream().anyMatch(corpus::contains))
                .map(Seen::libraryClasses).toList())
            .as("code_class, keyed by no graph, holds no class of the library in a store of corpus graphs")
            .isNotEmpty()
            .containsOnly(0);
        assertThat(stores.stream()
                .filter(store -> store.held().contains("defects/type-backing-partition-library-named"))
                .map(Seen::libraryClasses).toList())
            .as("while the store of a graph read over the library holds it")
            .containsExactly(1);
    }

    @Test
    @DisplayName("a path naming no plain document is refused, naming the shape a path takes")
    void unknownPathsAreRefused() {
        assertThatThrownBy(() -> FACTS.read("field-key-row", graph -> null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("scenarios/field-key-row");
    }

    private static final String INLINE_SDL = """
        type Query { inlineFilm: InlineFilm }
        type InlineFilm { title: String }
        """;

    @Test
    @DisplayName("an inline schema is captured under a name of its own and read back scoped to it")
    void inlineSchemasAreReadScoped() {
        var catalog = CatalogInput.of(TestRunContext.of());
        record Read(String graph, int fields, boolean typed) {}
        var first = FACTS.readInline("fixture", INLINE_SDL, catalog, graph -> new Read(
            graph.handle().graphName(),
            graph.handle().dsl().fetchCount(GRAPHITRON_FIELD,
                GRAPHITRON_FIELD.GRAPH_NAME.eq(graph.handle().graphName())),
            graph.registry().types().containsKey("InlineFilm")));
        var second = FACTS.readInline("fixture", INLINE_SDL, catalog,
            graph -> graph.handle().graphName());

        assertThat(first.fields()).as("the inline graph's fields").isPositive();
        assertThat(first.typed()).as("the registry of the file the SDL was written to").isTrue();
        assertThat(second).as("each inline schema gets a graph of its own").isNotEqualTo(first.graph());
    }

    @Test
    @DisplayName("an inline schema's store holds graphs not its own, so an unscoped read meets their rows")
    void inlineStoresHoldOtherGraphs() {
        var graphs = FACTS.readInline("fixture", INLINE_SDL, null, graph -> graph.handle().dsl()
            .selectDistinct(GRAPHITRON_FIELD.GRAPH_NAME).from(GRAPHITRON_FIELD)
            .fetch(GRAPHITRON_FIELD.GRAPH_NAME));
        assertThat(graphs).as("the ballast, or the documents and inline schemas captured before it")
            .hasSizeGreaterThan(1);
    }

    @Test
    @DisplayName("a write inside an inline read is refused by the database")
    void inlineWritesAreRefused() {
        assertThatThrownBy(() -> FACTS.readInline("fixture", INLINE_SDL, null,
                graph -> graph.handle().dsl().deleteFrom(GRAPHITRON_FIELD).execute()))
            .isInstanceOf(DataAccessException.class);
    }

    @Test
    @DisplayName("inline schemas over one catalog and classpath boot no stores beyond the population's for it")
    void inlineSchemasShareThePopulationsStores() {
        long before = FactGraphs.boots();
        for (int i = 0; i < 3 * FactGraphs.SHARDS_PER_CATALOG; i++) {
            FACTS.readInline("fixture", INLINE_SDL, null, graph -> null);
        }
        assertThat(FactGraphs.boots() - before).as("at most one boot per store of the key")
            .isLessThanOrEqualTo(FactGraphs.SHARDS_PER_CATALOG);
    }

    @Test
    @DisplayName("a store that stops answering is retired, and the next read gets a fresh one")
    void aBrokenStoreIsRetired() {
        // A classpath no other test captures over, so the store shut down here is this test's alone.
        var classpath = List.of(ClasspathCorpus.jooqRuntime());
        long before = FactGraphs.retired();
        assertThatThrownBy(() -> FACTS.readInline("fixture", INLINE_SDL, null, classpath, graph -> {
                FactGraphs.population().shutDownStoreOf(graph.handle().graphName());
                return graph.handle().dsl().fetchCount(GRAPHITRON_FIELD);
            }))
            .isInstanceOf(RuntimeException.class);
        assertThat(FactGraphs.retired()).as("the shut-down store").isEqualTo(before + 1);
        for (int i = 0; i < 2; i++) {
            int fields = FACTS.readInline("fixture", INLINE_SDL, null, classpath, graph -> graph.handle().dsl()
                .fetchCount(GRAPHITRON_FIELD, GRAPHITRON_FIELD.GRAPH_NAME.eq(graph.handle().graphName())));
            assertThat(fields).as("every read after it, in the store that replaced it").isPositive();
        }
    }

    @Test
    @DisplayName("a store that refuses one statement and still answers is kept")
    void aRefusedStatementRetiresNothing() {
        long before = FactGraphs.retired();
        assertThatThrownBy(() -> FACTS.readInline("fixture", INLINE_SDL, null,
                graph -> graph.handle().dsl().fetch("SELECT * FROM NO_SUCH_RELATION")))
            .isInstanceOf(DataAccessException.class);
        assertThat(FactGraphs.retired()).isEqualTo(before);
    }
}
