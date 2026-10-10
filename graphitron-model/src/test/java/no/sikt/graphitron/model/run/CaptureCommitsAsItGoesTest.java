package no.sikt.graphitron.model.run;

import no.sikt.graphitron.model.test.CapturedStore;
import no.sikt.graphitron.model.test.FactStores;
import org.jooq.DSLContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static no.sikt.graphitron.model.Tables.GRAPHQL_TYPE;
import static no.sikt.graphitron.model.Tables.STORE_GRAPH_PROGRESS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A capture commits as it goes, and {@code store_graph_progress} says how far it got.
 *
 * <p>The failure is made the way a late failure happens: a relation the graphitron gatherer writes
 * is gone, so the reading stops after every gatherer before it has committed. On a store of the
 * test's own, the schema being changed.
 */
class CaptureCommitsAsItGoesTest {

    @TempDir
    Path tmp;

    private static final String SDL = "type Query { film: Film }\ntype Film { title: String }\n";

    /** Every gatherer a capture runs, in the order it runs them, and the capture as a whole. */
    private static final List<String> GATHERERS = List.of("capture", "store", "jooq",
        "classpath-source", "code", "graphql-source", "graphql-ast", "graphql-assembly",
        "graphitron-ast", "sdl", "graphitron", "derivation");

    @Test
    @DisplayName("a reading that runs to its end completes every gatherer's row and the capture's")
    void aCompleteReadingCompletesEveryRow() throws IOException {
        try (var store = FactStores.inMemory()) {
            GraphitronStore.capture(store, graph(), corpus(), List.of(), null);

            assertThat(finished(store.dsl()).keySet()).containsExactlyInAnyOrderElementsOf(GATHERERS);
            assertThat(finished(store.dsl()).values()).as("every row says how long it took")
                .allMatch(finished -> finished);
        }
    }

    @Test
    @DisplayName("a reading that stops partway leaves what it finished, and says where it stopped")
    void aReadingThatStopsSaysWhereItStopped() throws IOException {
        try (var store = FactStores.inMemory()) {
            var graph = graph();
            var corpus = corpus();
            GraphitronStore.capture(store, graph, corpus, List.of(), null);

            Files.writeString(file(), SDL + "type Actor { name: String }\n");
            store.dsl().execute("DROP TABLE graphitron_type_backing CASCADE");
            assertThatThrownBy(() -> GraphitronStore.capture(store, graph, corpus, List.of(), null))
                .as("the graphitron gatherer writes the dropped relation, so the reading stops there");

            assertThat(store.dsl().select(GRAPHQL_TYPE.TYPE_NAME).from(GRAPHQL_TYPE)
                    .fetch(GRAPHQL_TYPE.TYPE_NAME))
                .as("the document gatherers ran before it and committed what they read")
                .contains("Actor");
            var rows = finished(store.dsl());
            assertThat(rows).as("finished this reading, or not")
                .containsEntry("graphitron-ast", true)
                .containsEntry("sdl", true)
                .containsEntry("graphitron", false)
                .containsEntry("capture", false);
            assertThat(rows.get("derivation"))
                .as("not reached, so still the previous reading's finished row")
                .isTrue();
        }
    }

    /** Each gatherer's row, as whether its last start has a completion after it. */
    private static Map<String, Boolean> finished(DSLContext dsl) {
        var t = STORE_GRAPH_PROGRESS;
        return dsl.selectFrom(t).fetch().stream().collect(Collectors.toMap(
            r -> r.getGathererName(), r -> r.getTimeSpentMs() != null));
    }

    private GraphIdentity graph() {
        return CapturedStore.graph(tmp);
    }

    private SubjectConfig corpus() throws IOException {
        Files.writeString(file(), SDL);
        return CapturedStore.corpusOf(List.of(file()), tmp);
    }

    private Path file() {
        return tmp.resolve("schema.graphqls");
    }
}
