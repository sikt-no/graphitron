package no.sikt.graphitron.rewrite.compile;

import no.sikt.graphitron.model.test.FactStores;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static no.sikt.graphitron.model.test.FactWriters.compileFacts;
import static no.sikt.graphitron.model.Tables.JAVAC_DIAGNOSTIC;
import static no.sikt.graphitron.model.Tables.STORE_GRAPH;
import static org.assertj.core.api.Assertions.assertThat;
import no.sikt.graphitron.model.compile.CompileDiagnostic;
import no.sikt.graphitron.model.compile.CompileRound;

/**
 * The {@code javac_} writer's own pins: round-trip fidelity, wholesale replacement within the
 * graph, graph-scoped statements (the writer-side half of the two-graph property the agreement
 * arm pins on the refresh side), and the natural key's ordinal grain.
 */
@UnitTier
class CompileFactsTest {

    private static final CompileDiagnostic ERROR = new CompileDiagnostic(
        "file:///gen/pkg/FilmFetchers.java", 12, 7, "ERROR", "compiler.err.cant.resolve",
        "cannot find symbol");

    @Test
    @DisplayName("a round trips through the store handle, code and sentinels included")
    void roundTripsThroughAStoreHandle(@TempDir Path tmp) {
        try (var store = FactStores.inMemory()) {
            var noPosition = new CompileDiagnostic("(no source)", -1, -1, "NOTE", null, "a note");
            compileFacts(store.dsl(), "dev", tmp).write(new CompileRound(false, List.of(ERROR, noPosition)));

            var rows = store.dsl().selectFrom(JAVAC_DIAGNOSTIC)
                .orderBy(JAVAC_DIAGNOSTIC.FILE).fetch();
            assertThat(rows).hasSize(2);
            var sentinel = rows.getFirst();
            assertThat(sentinel.getFile()).isEqualTo("(no source)");
            assertThat(sentinel.getLineNumber()).isEqualTo(-1);
            assertThat(sentinel.getColumnNumber()).isEqualTo(-1);
            assertThat(sentinel.getCode()).isNull();
            var error = rows.getLast();
            assertThat(error.getGraphName()).isEqualTo("dev");
            assertThat(error.getFile()).isEqualTo(ERROR.file());
            assertThat(error.getLineNumber()).isEqualTo(ERROR.line());
            assertThat(error.getColumnNumber()).isEqualTo(ERROR.column());
            assertThat(error.getOrdinal()).isZero();
            assertThat(error.getKind()).isEqualTo("ERROR");
            assertThat(error.getCode()).isEqualTo("compiler.err.cant.resolve");
            assertThat(error.getMessage()).isEqualTo("cannot find symbol");
        }
    }

    @Test
    @DisplayName("a second round replaces the first wholesale within its graph")
    void aSecondRoundReplacesTheFirstWholesale(@TempDir Path tmp) {
        try (var store = FactStores.inMemory()) {
            var writer = compileFacts(store.dsl(), "dev", tmp);
            writer.write(new CompileRound(false, List.of(ERROR)));
            var next = new CompileDiagnostic("file:///gen/pkg/Other.java", 3, 1, "WARNING",
                "compiler.warn.unchecked", "unchecked call");
            writer.write(new CompileRound(false, List.of(next)));

            assertThat(store.dsl().selectFrom(JAVAC_DIAGNOSTIC).fetch())
                .hasSize(1)
                .allSatisfy(row -> assertThat(row.getFile()).isEqualTo(next.file()));
        }
    }

    @Test
    @DisplayName("a successful round clears a previous failure")
    void aSuccessfulRoundClearsAPreviousFailure(@TempDir Path tmp) {
        try (var store = FactStores.inMemory()) {
            var writer = compileFacts(store.dsl(), "dev", tmp);
            writer.write(new CompileRound(false, List.of(ERROR)));
            writer.write(new CompileRound(true, List.of()));

            assertThat(store.dsl().fetchCount(JAVAC_DIAGNOSTIC)).isZero();
        }
    }

    @Test
    @DisplayName("a round leaves a second graph's rows byte-identical")
    void aRoundLeavesASecondGraphsRowsAlone(@TempDir Path tmp) {
        try (var store = FactStores.inMemory()) {
            compileFacts(store.dsl(), "sibling", tmp.resolve("sibling")).write(
                new CompileRound(false, List.of(ERROR)));
            var before = store.dsl().selectFrom(JAVAC_DIAGNOSTIC)
                .where(JAVAC_DIAGNOSTIC.GRAPH_NAME.eq("sibling")).fetch().map(Object::toString);

            var writer = compileFacts(store.dsl(), "dev", tmp.resolve("dev"));
            writer.write(new CompileRound(false, List.of(ERROR)));
            writer.write(new CompileRound(true, List.of()));

            assertThat(store.dsl().selectFrom(JAVAC_DIAGNOSTIC)
                .where(JAVAC_DIAGNOSTIC.GRAPH_NAME.eq("sibling")).fetch().map(Object::toString))
                .isEqualTo(before);
        }
    }

    @Test
    @DisplayName("repeated identical diagnostics at one position number densely in round order")
    void ordinalsDisambiguateIdenticalPositions(@TempDir Path tmp) {
        try (var store = FactStores.inMemory()) {
            compileFacts(store.dsl(), "dev", tmp).write(new CompileRound(false, List.of(ERROR, ERROR, ERROR)));

            assertThat(store.dsl().select(JAVAC_DIAGNOSTIC.ORDINAL).from(JAVAC_DIAGNOSTIC)
                .orderBy(JAVAC_DIAGNOSTIC.ORDINAL).fetch(0, Integer.class))
                .containsExactly(0, 1, 2);
        }
    }

    @Test
    @DisplayName("a graph name recorded against another directory is not written under")
    void anotherCheckoutsPartitionIsLeftAlone(@TempDir Path tmp) {
        try (var store = FactStores.inMemory()) {
            // The anchor row claims the graph for a different base directory, the way a sibling
            // checkout's capture would have.
            compileFacts(store.dsl(), "dev", tmp.resolve("theirs")).write(new CompileRound(false, List.of(ERROR)));
            var before = store.dsl().selectFrom(JAVAC_DIAGNOSTIC).fetch().map(Object::toString);

            compileFacts(store.dsl(), "dev", tmp.resolve("ours")).write(new CompileRound(true, List.of()));

            assertThat(store.dsl().selectFrom(JAVAC_DIAGNOSTIC).fetch().map(Object::toString))
                .as("the recorded owner's rows, after a non-owner's round")
                .isEqualTo(before);
        }
    }

    @Test
    @DisplayName("the writer's minted anchor row satisfies the structural FK")
    void theWriterAnchorsItsGraphWhenNoCaptureHas(@TempDir Path tmp) {
        try (var store = FactStores.inMemory()) {
            compileFacts(store.dsl(), "dev", tmp).write(new CompileRound(false, List.of(ERROR)));

            assertThat(store.dsl().select(STORE_GRAPH.BASE_DIR).from(STORE_GRAPH)
                .where(STORE_GRAPH.GRAPH_NAME.eq("dev")).fetchOne(0, String.class))
                .isEqualTo(tmp.toAbsolutePath().normalize().toString());
        }
    }
}
