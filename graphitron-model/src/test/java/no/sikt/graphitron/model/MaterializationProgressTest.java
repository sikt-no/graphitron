package no.sikt.graphitron.model;

import no.sikt.graphitron.model.derive.MaterializeDependencies;
import no.sikt.graphitron.model.derive.Materializations;
import no.sikt.graphitron.model.derive.RefreshProgress;
import no.sikt.graphitron.model.derive.RefreshProgress.Event;
import no.sikt.graphitron.model.test.FactStores;
import no.sikt.graphitron.model.test.SeededStore;
import org.jooq.DSLContext;
import org.jooq.exception.DataAccessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static no.sikt.graphitron.model.Tables.META_MATERIALIZE;
import static no.sikt.graphitron.model.Tables.META_MATERIALIZE_DEPENDENCY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pins what a refresh pass reports, on synthetic registrations for the reason
 * {@link MaterializationOrderTest} states: a scratch store can {@code CREATE} ordinary tables and
 * views and register them, which is what puts a case in front of shapes the shipped register does
 * not produce, here a registration whose refill cannot succeed.
 *
 * <p>The first case is the item's whole subject and the rest are its ordinary-case company. A
 * refresh that timed each registration and reported afterwards would pass every other case here
 * while emitting nothing at all for the registration that never returns, which is the only case
 * anybody turns the instrument on for, so the emission order is asserted directly rather than
 * inferred from a healthy pass.
 *
 * <p>Durations are read for their existence and never for their size: this is a unit-tier case and
 * a wall-clock threshold has no business in it.
 */
class MaterializationProgressTest {

    /**
     * The case the instrument exists for. The registration's refill cannot succeed, so its
     * {@code INSERT} never returns rows: what survives is the started event that named it, and a
     * pass that emitted its name after the statement instead would leave the failure anonymous.
     */
    @Test
    @DisplayName("a registration whose refill fails has already named itself")
    void aFailingRegistrationHasAlreadyNamedItself() {
        withStore(dsl -> {
            dsl.execute("CREATE TABLE scratch_src (v INT)");
            dsl.execute("INSERT INTO scratch_src VALUES (1)");
            dsl.execute("CREATE VIEW scratch_n_live AS SELECT CAST(NULL AS INT) AS v FROM scratch_src");
            dsl.execute("CREATE TABLE scratch_n (v INT NOT NULL)");
            register(dsl, "scratch_n_live", "scratch_n");
            MaterializeDependencies.populate(dsl);

            var recorded = new Recorder();
            assertThatThrownBy(() -> Materializations.refresh(dsl, "any-graph", recorded))
                .as("the database's refusal propagates; the instrument swallows nothing")
                .isInstanceOf(DataAccessException.class);

            assertThat(describe(recorded.events))
                .as("the name went out before the statement that never completed, and no finished"
                    + " event followed it")
                .containsExactly(
                    "pass started: 1 registration for [any-graph]",
                    "started 1/1 scratch_n_live, whole relation");
        });
    }

    @Test
    @DisplayName("the pass reports its registrations in refresh order, each started event paired")
    void thePassReportsItsRegistrationsInRefreshOrder() {
        withStore(dsl -> {
            prerequisite(dsl);
            dependent(dsl);
            dsl.execute("INSERT INTO scratch_src VALUES (1), (2), (3)");
            MaterializeDependencies.populate(dsl);

            var recorded = new Recorder();
            Materializations.refresh(dsl, "any-graph", recorded);

            assertThat(describe(recorded.events))
                .as("the dependent sorts ahead of its prerequisite in the census, so a sequence read"
                    + " off the census instead of the refresh order fails here")
                .containsExactly(
                    "pass started: 2 registrations for [any-graph]",
                    "started 1/2 scratch_p_live, whole relation",
                    "finished scratch_p_live",
                    "started 2/2 scratch_d_live, whole relation",
                    "finished scratch_d_live",
                    "pass finished");
        });
    }

    @Test
    @DisplayName("each registration reports the scope it was refreshed for, in both refresh shapes")
    void eachRegistrationReportsTheScopeItWasRefreshedFor() {
        withStore(dsl -> {
            prerequisite(dsl);
            graphKeyed(dsl);
            MaterializeDependencies.populate(dsl);

            var recorded = new Recorder();
            Materializations.refresh(dsl, "orders", recorded);

            assertThat(describe(recorded.events))
                .as("the graph-keyed target names the partition it was refilled for; the graph-free"
                    + " one has no partition to name")
                .containsExactly(
                    "pass started: 2 registrations for [orders]",
                    "started 1/2 scratch_g_live, graph 'orders'",
                    "finished scratch_g_live",
                    "started 2/2 scratch_p_live, whole relation",
                    "finished scratch_p_live",
                    "pass finished");
        });
    }

    @Test
    @DisplayName("a whole-store refresh reports the graph-keyed registration once per graph")
    void aWholeStoreRefreshReportsOnePassPerGraph() {
        withStore(dsl -> {
            prerequisite(dsl);
            graphKeyed(dsl);
            SeededStore.seedGraph(dsl, "orders");
            SeededStore.seedGraph(dsl, "shipping");
            MaterializeDependencies.populate(dsl);

            var recorded = new Recorder();
            Materializations.refreshAll(dsl, recorded);

            assertThat(describe(recorded.events))
                .as("the graph-keyed registration is refreshed a partition at a time and says so;"
                    + " the graph-free one is one statement pair however many graphs the store holds")
                .containsExactly(
                    "pass started: 2 registrations for [orders, shipping]",
                    "started 1/2 scratch_g_live, graph 'orders'",
                    "finished scratch_g_live",
                    "started 1/2 scratch_g_live, graph 'shipping'",
                    "finished scratch_g_live",
                    "started 2/2 scratch_p_live, whole relation",
                    "finished scratch_p_live",
                    "pass finished");
        });
    }

    /**
     * The counts are the two statements' own return values, which is what makes them free and what
     * makes them worth reading: they explain a registration that is slow and does finish.
     */
    @Test
    @DisplayName("a finished registration carries the rows its own two statements touched")
    void aFinishedRegistrationCarriesTheRowsItsStatementsTouched() {
        withStore(dsl -> {
            prerequisite(dsl);
            dsl.execute("INSERT INTO scratch_src VALUES (1), (2), (3)");
            dsl.execute("INSERT INTO scratch_p VALUES (7), (8)");
            MaterializeDependencies.populate(dsl);

            var recorded = new Recorder();
            Materializations.refresh(dsl, "any-graph", recorded);

            var finished = recorded.events.stream()
                .filter(Event.RegistrationFinished.class::isInstance)
                .map(Event.RegistrationFinished.class::cast)
                .toList();
            assertThat(finished)
                .singleElement()
                .satisfies(event -> {
                    assertThat(event.rowsDeleted())
                        .as("what the target held before the pass")
                        .isEqualTo(2);
                    assertThat(event.rowsInserted())
                        .as("what the source view yields")
                        .isEqualTo(3);
                    assertThat(event.deleteNanos()).isNotNegative();
                    assertThat(event.insertNanos()).isNotNegative();
                });
        });
    }

    /**
     * The line rendering, asserted at the tier split rather than word for word: which consumer takes
     * which event is the property a caller's two method references rest on, and a pass line landing
     * in the per-registration tier would put twenty lines a save on a console nobody asked.
     */
    @Test
    @DisplayName("the line rendering splits the two tiers and names before it times")
    void theLineRenderingSplitsTheTwoTiers() {
        withStore(dsl -> {
            prerequisite(dsl);
            dsl.execute("INSERT INTO scratch_src VALUES (1), (2)");
            MaterializeDependencies.populate(dsl);

            var pass = new ArrayList<String>();
            var registration = new ArrayList<String>();
            Materializations.refresh(dsl, "orders",
                RefreshProgress.lines(pass::add, registration::add));

            assertThat(pass)
                .as("the pass boundary, which is what a default cadence prints")
                .hasSize(2);
            assertThat(pass.getFirst()).contains("refreshing 1 materialization", "graph 'orders'");
            assertThat(pass.getLast()).contains("materialization refresh done in");
            assertThat(registration)
                .as("the relation named first, its cost after; the two are one line each")
                .hasSize(2);
            assertThat(registration.getFirst())
                .contains("1/1", "scratch_p_live -> scratch_p", "whole relation");
            assertThat(registration.getLast())
                .contains("1/1", "done in", "deleted 0 rows", "inserted 2 rows");
        });
    }

    @Test
    @DisplayName("the observer-free overloads still refresh, and report to nothing")
    void theObserverFreeOverloadsStillRefresh() {
        withStore(dsl -> {
            prerequisite(dsl);
            dependent(dsl);
            dsl.execute("INSERT INTO scratch_src VALUES (4), (5)");
            MaterializeDependencies.populate(dsl);

            Materializations.refresh(dsl, "any-graph");
            assertThat(targetRows(dsl, "scratch_d"))
                .as("the graph-scoped overload refreshes exactly as it did before the observer")
                .containsExactly(4, 5);

            dsl.execute("INSERT INTO scratch_src VALUES (6)");
            Materializations.refreshAll(dsl);
            assertThat(targetRows(dsl, "scratch_d"))
                .as("and so does the whole-store one")
                .containsExactly(4, 5, 6);
        });
    }

    // ===== Fixture =====

    /** Every event the refresh emitted, in order. */
    private static final class Recorder implements RefreshProgress {
        private final List<Event> events = new ArrayList<>();

        @Override
        public void observe(Event event) {
            events.add(event);
        }
    }

    /**
     * The events as this test compares them: what each one names, and never what it timed. A
     * duration is a wall-clock reading and belongs in no assertion of this tier beyond the
     * existence check one case makes.
     */
    private static List<String> describe(List<Event> events) {
        return events.stream().map(event -> switch (event) {
            case Event.PassStarted started -> "pass started: " + started.registrations()
                + (started.registrations() == 1 ? " registration for " : " registrations for ")
                + started.graphs();
            case Event.RegistrationStarted started -> "started " + started.position() + "/"
                + started.total() + " " + started.registration().sourceViewName() + ", "
                + started.graph().map(graph -> "graph '" + graph + "'").orElse("whole relation");
            case Event.RegistrationFinished finished -> "finished "
                + finished.registration().sourceViewName();
            case Event.PassFinished ignored -> "pass finished";
        }).toList();
    }

    private static void withStore(Consumer<DSLContext> body) {
        try (var store = FactStores.inMemory()) {
            isolateRegister(store.dsl());
            body.accept(store.dsl());
        }
    }

    /**
     * Empties the shipped register before the fixture writes its own rows, which is what makes a
     * case about an emitted sequence hold: a pass over a booted store runs every registration the
     * DDL declares, so the positions and the event list would otherwise be pinned to how many
     * registrations happen to ship and would move whenever one lands. The relations the shipped
     * rows named are still in the store; they are simply nobody's registration here.
     */
    private static void isolateRegister(DSLContext dsl) {
        dsl.deleteFrom(META_MATERIALIZE_DEPENDENCY).execute();
        dsl.deleteFrom(META_MATERIALIZE).execute();
    }

    /** A graph-free registration: target {@code scratch_p}, whose view reads a base table. */
    private static void prerequisite(DSLContext dsl) {
        dsl.execute("CREATE TABLE scratch_src (v INT)");
        dsl.execute("CREATE VIEW scratch_p_live AS SELECT v FROM scratch_src");
        dsl.execute("CREATE TABLE scratch_p (v INT)");
        register(dsl, "scratch_p_live", "scratch_p");
    }

    /** A registration reading the prerequisite's target, so the refresh order has an edge to hold. */
    private static void dependent(DSLContext dsl) {
        dsl.execute("CREATE VIEW scratch_d_live AS SELECT v FROM scratch_p");
        dsl.execute("CREATE TABLE scratch_d (v INT)");
        register(dsl, "scratch_d_live", "scratch_d");
    }

    /**
     * A graph-keyed registration: the target carries {@code graph_name}, which is what decides the
     * refresh shape, so this one is refreshed a partition at a time.
     */
    private static void graphKeyed(DSLContext dsl) {
        dsl.execute("CREATE TABLE scratch_gsrc (graph_name VARCHAR, v INT)");
        dsl.execute("CREATE VIEW scratch_g_live AS SELECT graph_name, v FROM scratch_gsrc");
        dsl.execute("CREATE TABLE scratch_g (graph_name VARCHAR, v INT)");
        register(dsl, "scratch_g_live", "scratch_g");
    }

    private static void register(DSLContext dsl, String sourceViewName, String targetTableName) {
        dsl.insertInto(META_MATERIALIZE,
                META_MATERIALIZE.SOURCE_VIEW_NAME,
                META_MATERIALIZE.TARGET_TABLE_NAME,
                META_MATERIALIZE.REASON)
            .values(sourceViewName, targetTableName, "synthetic registration for this test")
            .execute();
    }

    private static List<Integer> targetRows(DSLContext dsl, String targetTableName) {
        return dsl.fetch("SELECT v FROM " + targetTableName + " ORDER BY v")
            .getValues(0, Integer.class);
    }
}
