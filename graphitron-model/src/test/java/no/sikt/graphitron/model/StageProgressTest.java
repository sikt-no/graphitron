package no.sikt.graphitron.model;

import no.sikt.graphitron.model.derive.DerivationStratum;
import no.sikt.graphitron.model.derive.DerivationStratum.Step;
import no.sikt.graphitron.model.derive.StageProgress;
import no.sikt.graphitron.model.derive.StageProgress.Event;
import no.sikt.graphitron.model.test.FactStores;
import org.jooq.DSLContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pins what the derivation stratum reports, and how its two cadences commit, on synthetic steps over
 * scratch tables: a stage whose statements fail is a shape the shipped stratum does not produce, and
 * it is the one the instrument exists for.
 *
 * <p>The first case is the subject and the rest are its ordinary-case company. A stratum that timed
 * each step and reported afterwards would pass every other case here while emitting nothing at all
 * for the step that never returns, so the emission order is asserted directly rather than inferred
 * from a healthy pass. Durations are read for their existence and never for their size: this is a
 * unit-tier case and a wall-clock threshold has no business in it.
 */
class StageProgressTest {

    private static final String GRAPH = "g";

    @Test
    @DisplayName("a stage whose statements fail has already named itself")
    void aFailingStageHasAlreadyNamedItself() {
        withScratch(dsl -> {
            var events = new ArrayList<Event>();
            var steps = List.of(
                fills("first", "scratch_a"),
                new Step("failing", Set.of("scratch_b"), (d, g) -> {
                    throw new IllegalStateException("the statement that never returns");
                }),
                fills("never", "scratch_c"));

            assertThatThrownBy(() -> dsl.transaction(tx ->
                DerivationStratum.runInOne(tx.dsl(), GRAPH, steps, events::add)))
                .hasMessage("the statement that never returns");

            assertThat(events.stream().map(StageProgressTest::describe).toList())
                .as("the failing step's name went out before its statements did, and nothing after"
                    + " it was announced")
                .containsExactly("pass 3", "started first", "finished first", "started failing");
        });
    }

    @Test
    @DisplayName("the stratum reports its steps in order, each started event paired")
    void everyStepIsAnnouncedAndFinishedInOrder() {
        withScratch(dsl -> {
            var events = new ArrayList<Event>();
            var steps = List.of(fills("a", "scratch_a"), fills("b", "scratch_b"),
                fills("c", "scratch_c"));
            dsl.transaction(tx -> DerivationStratum.runInOne(tx.dsl(), GRAPH, steps, events::add));

            assertThat(events.stream().map(StageProgressTest::describe).toList())
                .containsExactly("pass 3", "started a", "finished a", "started b", "finished b",
                    "started c", "finished c", "done");
            assertThat(events.stream().filter(e -> e instanceof Event.StageStarted)
                    .map(e -> ((Event.StageStarted) e).position()).toList())
                .as("positions are 1-based and dense").containsExactly(1, 2, 3);
        });
    }

    @Test
    @DisplayName("a finished step carries the rows its tables hold for the graph")
    void aFinishedStepCarriesItsRows() {
        withScratch(dsl -> {
            dsl.execute("INSERT INTO SCRATCH_A VALUES ('another', 9)");
            var events = new ArrayList<Event>();
            dsl.transaction(tx -> DerivationStratum.runInOne(tx.dsl(), GRAPH,
                List.of(fills("a", "scratch_a")), events::add));

            var finished = events.stream().filter(e -> e instanceof Event.StageFinished)
                .map(e -> (Event.StageFinished) e).findFirst().orElseThrow();
            assertThat(finished.rows())
                .as("the two rows this graph's step wrote, and not the sibling graph's row")
                .isEqualTo(2);
            assertThat(finished.nanos()).isPositive();
        });
    }

    @Test
    @DisplayName("the analysing cadence commits each step, so a failure keeps what came before it")
    void theAnalysingCadenceCommitsStepByStep() {
        withScratch(dsl -> {
            var steps = List.of(fills("first", "scratch_a"),
                new Step("failing", Set.of("scratch_b"), (d, g) -> {
                    throw new IllegalStateException("refused");
                }));

            assertThatThrownBy(() -> DerivationStratum.runAnalysing(dsl, GRAPH, steps,
                StageProgress.none())).hasMessage("refused");
            assertThat(dsl.fetchCount(dsl.selectFrom("SCRATCH_A")))
                .as("the first step committed on its own, which is the cadence's whole contract")
                .isEqualTo(2);

            dsl.execute("DELETE FROM SCRATCH_A");
            assertThatThrownBy(() -> dsl.transaction(tx -> DerivationStratum.runInOne(tx.dsl(),
                GRAPH, steps, StageProgress.none()))).hasMessage("refused");
            assertThat(dsl.fetchCount(dsl.selectFrom("SCRATCH_A")))
                .as("the one-transaction cadence rolls the whole stratum back, which is its own")
                .isZero();
        });
    }

    @Test
    @DisplayName("the line rendering splits the two tiers and names before it times")
    void theLinesNameBeforeTheyTime() {
        var pass = new ArrayList<String>();
        var stage = new ArrayList<String>();
        withScratch(dsl -> dsl.transaction(tx -> DerivationStratum.runInOne(tx.dsl(), GRAPH,
            List.of(fills("a", "scratch_a")), StageProgress.lines(pass::add, stage::add))));

        assertThat(pass).hasSize(2);
        assertThat(pass.getFirst()).isEqualTo("graphitron: deriving 1 stage for graph 'g'");
        assertThat(pass.getLast()).startsWith("graphitron: derivation stratum done in ");
        assertThat(stage).hasSize(2);
        assertThat(stage.getFirst()).isEqualTo("graphitron: 1/1 stage a");
        assertThat(stage.getLast()).startsWith("graphitron: 1/1 done in ").endsWith(", 2 rows");
    }

    // ===== Helpers =====

    /** A step writing two rows for the graph into one scratch table, reconciling first. */
    private static Step fills(String name, String table) {
        return new Step(name, Set.of(table), (dsl, graph) -> {
            dsl.execute("DELETE FROM " + table.toUpperCase() + " WHERE GRAPH_NAME = ?", graph);
            dsl.execute("INSERT INTO " + table.toUpperCase() + " VALUES (?, 1), (?, 2)", graph,
                graph);
        });
    }

    private static String describe(Event event) {
        return switch (event) {
            case Event.PassStarted started -> "pass " + started.stages();
            case Event.StageStarted started -> "started " + started.step().name();
            case Event.StageFinished finished -> "finished " + finished.step().name();
            case Event.PassFinished finished -> "done";
        };
    }

    private static void withScratch(Consumer<DSLContext> body) {
        try (var store = FactStores.inMemory()) {
            DSLContext dsl = store.dsl();
            for (String table : List.of("SCRATCH_A", "SCRATCH_B", "SCRATCH_C")) {
                dsl.execute("CREATE TABLE " + table + " (GRAPH_NAME VARCHAR NOT NULL, V INT)");
            }
            body.accept(dsl);
        }
    }
}
