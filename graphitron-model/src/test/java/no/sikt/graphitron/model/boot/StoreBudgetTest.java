package no.sikt.graphitron.model.boot;

import no.sikt.graphitron.model.test.FactStores;
import org.jooq.DSLContext;
import org.jooq.exception.DataAccessException;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.TimeUnit;

import static no.sikt.graphitron.model.test.StoreAnswers.answered;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What a {@link ReadBudget} does to a {@link StoreReader}: the session it installs, the arm an
 * overrun produces, the failures it must not swallow, and the reader that keeps working afterwards.
 *
 * <p><strong>Nothing here asserts a duration.</strong> The subject is an arm and a setting, both of
 * which are observable without a clock, which is what keeps these cases in the same tier as the
 * statement counters: a case that could fail for being slow could fail on a loaded machine, and
 * would then be measuring the machine.
 *
 * <p>The overrun is provoked by a query that does not terminate rather than by one that is merely
 * slow, so there is no threshold to tune and no fixture to scale. The shape is the one
 * {@code fact-model.adoc} records as "a hang with no diagnostic": a recursive term that joins on one
 * column and projects another turns two rows differing only in the projected column into identical
 * output rows, and {@code UNION ALL} keeps both, so the frontier doubles every hop. Forty stated rows
 * reproduce it, which is why nothing here needs census scale.
 *
 * <p>The relation carrying that shape is the fixture's own, created here. The two store relations
 * that carried the defect are respectively deleted and deduplicated, so a case naming either would
 * pin a shape that no longer exists and would start passing for the wrong reason.
 */
class StoreBudgetTest {

    /** Tight enough that the whole class costs a fraction of a second, and asserted against never. */
    private static final ReadBudget BOUNDED = new ReadBudget.Bounded(500);

    /** The fixture's own runaway relation, deliberately not one of the store's. */
    private static final String EDGES = "budget_probe_edge";

    /** How H2 reports the session's own statement budget; {@code 0} is its spelling of no limit. */
    private static final String QUERY_TIMEOUT =
        "SELECT setting_value FROM information_schema.settings WHERE setting_name = 'QUERY_TIMEOUT'";

    private static final String RUNAWAY = """
        WITH RECURSIVE reach(a, b) AS (
            SELECT a, b FROM %s
            UNION ALL
            SELECT r.a, e.b FROM reach r JOIN %s e ON e.a = r.b
        )
        SELECT count(*) FROM reach
        """.formatted(EDGES, EDGES);

    /**
     * A budget states itself in the session, and the absence of one states nothing. The assertion is
     * on the setting H2 reports rather than on a jOOQ listener, because the reader issues the
     * command through the connection rather than through {@code DSLContext}: a listener would see
     * neither the isolation level nor the budget, so it would pass whatever the reader did.
     */
    @Test
    void theBudgetIsInstalledOnTheSessionAndNowhereElse() {
        try (var store = FactStores.inMemory();
             var bounded = store.reader(BOUNDED);
             var unbounded = store.reader(new ReadBudget.Unbounded())) {
            String onBounded = answered(bounded.read(StoreBudgetTest::queryTimeout));
            String onUnbounded = answered(unbounded.read(StoreBudgetTest::queryTimeout));

            assertThat(onBounded)
                .as("the bounded reader's own session carries its number")
                .isEqualTo("500");
            assertThat(onUnbounded)
                .as("a sibling reader is bounded only by its own budget, which is none")
                .isEqualTo("0");
        }
    }

    /**
     * The case the whole item exists for: a query that would never return becomes an arm instead of
     * a hang. The timeout is a guard against exactly that hang, not an assertion; a suite that stops
     * responding is far harder to diagnose than one that reports which arm it got, and 60 seconds is
     * two orders of magnitude above the budget under test.
     */
    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void aNonTerminatingReadArrivesAsAnArm() {
        try (var store = FactStores.inMemory(); var reader = store.reader(BOUNDED)) {
            seedRunawayShape(store.dsl());

            assertThat(reader.read(dsl -> dsl.fetchOne(RUNAWAY)))
                .isInstanceOfSatisfying(StoreAnswer.OutOfBudget.class, expired -> {
                    assertThat(expired.budget()).isEqualTo(BOUNDED);
                    assertThat(expired.sql())
                        .as("the statement is what makes the warning worth reading")
                        .contains(EDGES);
                });
        }
    }

    /**
     * A query the database refused is a defect in that query, and the arm must not become the place
     * defects go quiet. Only a statement the database <em>cancelled</em> is an overrun.
     */
    @Test
    void aGenuineQueryErrorStillThrows() {
        try (var store = FactStores.inMemory(); var reader = store.reader(BOUNDED)) {
            assertThatThrownBy(() -> reader.read(dsl -> dsl.fetchOne("SELECT * FROM no_such_relation")))
                .isInstanceOf(DataAccessException.class);
        }
    }

    /**
     * An aborted statement ends the statement, not the session. The rollback that ends every read
     * runs on the overrun path too, and this is the case a missing one would break: the next read
     * would open a transaction on a connection still inside the last one's.
     */
    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void aReaderThatRanOutOfBudgetAnswersTheNextRead() {
        try (var store = FactStores.inMemory(); var reader = store.reader(BOUNDED)) {
            seedRunawayShape(store.dsl());

            assertThat(reader.read(dsl -> dsl.fetchOne(RUNAWAY)))
                .isInstanceOf(StoreAnswer.OutOfBudget.class);

            int edges = answered(reader.read(dsl -> dsl.fetchCount(DSL.table(EDGES))));
            assertThat(edges)
                .as("the session survived the abort and answers an ordinary read")
                .isEqualTo(40);
        }
    }

    /** {@code 0} is H2's spelling of no limit, so a computed zero must not pass for a budget. */
    @Test
    void aNonPositiveBoundedBudgetIsRefused() {
        assertThatThrownBy(() -> new ReadBudget.Bounded(0))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Unbounded");
        assertThatThrownBy(() -> new ReadBudget.Bounded(-1))
            .isInstanceOf(IllegalArgumentException.class);
    }

    /** The statement budget H2 reports for the session a read is running on. */
    private static String queryTimeout(DSLContext dsl) {
        return dsl.fetchOne(QUERY_TIMEOUT).get(0, String.class);
    }

    /**
     * Twenty edges written twice: the duplicate targets are what make the projection produce
     * identical output rows for {@code UNION ALL} to keep, and forty rows is what
     * {@code fact-model.adoc} records as enough to reproduce the hang.
     */
    private static void seedRunawayShape(DSLContext dsl) {
        dsl.execute("CREATE TABLE " + EDGES + "(a INT, b INT)");
        for (int i = 0; i < 20; i++) {
            dsl.execute("INSERT INTO " + EDGES + " VALUES (?, ?), (?, ?)", i, i + 1, i, i + 1);
        }
    }
}
