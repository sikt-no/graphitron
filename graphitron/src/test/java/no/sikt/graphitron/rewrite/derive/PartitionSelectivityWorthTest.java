package no.sikt.graphitron.rewrite.derive;

import no.sikt.graphitron.common.configuration.TestConfiguration;
import no.sikt.graphitron.model.boot.ReadBudget;
import no.sikt.graphitron.model.boot.StoreAnswer;
import no.sikt.graphitron.model.catalog.GraphPartition;
import no.sikt.graphitron.model.derive.Materializations;
import no.sikt.graphitron.model.test.CapturedStore;
import no.sikt.graphitron.model.jooq.JooqCatalog;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the partition dimension's declared selectivity is worth: whether a store that has been
 * analysed nowhere, and told only what {@link GraphPartition#DECLARED_SELECTIVITY} states, reads the
 * derivations over the reference walk for what a fully analysed store pays.
 *
 * <p><b>Why this claim needs a test of its own.</b> {@link RefreshPlanStatisticsTest} holds which
 * refresh statements plan differently, by plan text, and that is the acceptance evidence for the
 * declaration as a plan lever. It cannot say what a plan difference <em>costs</em>, and on this
 * schema the two questions come apart in both directions: a plan that differs can visit the same
 * rows, which is the shape the decode hop is in here once the declaration lands. The cliff the
 * declaration removes is a count of rows visited, so a count is what says whether it was removed.
 *
 * <p><b>Instrument: {@code EXPLAIN ANALYZE}'s summed {@code scanCount}</b>, which
 * {@link DerivedReadCostTest} carries and this borrows. A count of rows visited reads the same on a
 * fast machine and a loaded one, which is what lets a tier that must not fail for being slow hold a
 * cost claim. No duration is asserted anywhere here. It is also the only instrument that sees this
 * defect at all: plain {@code EXPLAIN} does not print a recursive CTE's plan, which is what hid the
 * index choice from every cheaper reading until a scan count made it visible.
 *
 * <p><b>Three regimes over one captured store</b>, so the rows and the declared indexes are the same
 * throughout and the statement of statistics is the only thing that varies.
 *
 * <ul>
 *   <li><b>bare</b>: every column of every base table back to H2's unanalysed default, the partition
 *       column included. The control, and a store no run can open any more: it is what a cold store
 *       was before the declaration existed. {@link StoreStatistics#resetIncludingTheDeclaration} is
 *       the only caller of that reset for exactly this reason.
 *   <li><b>declared</b>: the same, with the partition declaration restored and nothing analysed.
 *       This is a cold store as a run meets one, and the regime the claim is about.
 *   <li><b>analysed</b>: the declaration plus {@code ANALYZE} on every base table, which is the most
 *       statistics a store can carry.
 * </ul>
 *
 * <p><b>The two readers, and why the figures differ in kind between them.</b>
 * {@code intent_node_id_decode_hop_live} and {@code intent_node_id_instruction_live} both reach
 * {@code intent_field_reference_step_hop} through the same chain, and on a consumer-size store both
 * paid the cliff; the instruction view is the one an instrumented round measured at 592 s. On this
 * repository's twelve-unit fixture only the decode hop moves. Measured: the decode hop visits 10939
 * rows bare, 1113 declared and 1113 analysed, so the declaration recovers the analysed cost to the
 * row; the instruction view visits 2151 bare, 2151 declared and 2226 analysed, its plan moving
 * where its count does not. That is the fixture understating rather than the defect being absent,
 * for the reason {@code RefreshPlanStatisticsTest} states last: a per-driving-row cost is linear in
 * driving rows, and a fixture whose partitions are small is precisely where a partition scan is
 * cheap. So the control is asserted where the instrument has signal, and the instruction view is
 * asserted to be unmoved rather than quietly left out.
 */
@PipelineTier
class PartitionSelectivityWorthTest {

    /** Repetitions of the fixture's node cluster, twelve for {@link DerivedReadCostTest}'s reason. */
    private static final int UNITS = 12;

    /**
     * The reader the instrument has signal on: the rule whose read of
     * {@code intent_field_reference_step_hop} is the index choice the declaration decides.
     */
    private static final String DECODE_HOP = "intent_node_id_decode_hop_live";

    /** The reader the consumer-scale measurement was taken on, unmoved at this fixture size. */
    private static final String INSTRUCTION = "intent_node_id_instruction_live";

    /**
     * How far the declared regime may sit above the analysed one, as measured and not as guessed:
     * the decode hop visits 1113 rows under both, so the declaration recovers the analysed plan's
     * cost to the row, and the instruction view visits fewer declared than analysed. A tenth is
     * slack for a plan that shifts without the claim failing.
     */
    private static final double DECLARED_WITHIN = 1.1;

    /**
     * How far below the bare regime the declared one has to sit for the claim above to say anything.
     * Measured at 9.8 (10939 rows against 1113); five is the floor the assertion holds, low enough
     * that a fixture detail moving the ratio does not fail the build and high enough that the two
     * regimes cannot be the same plan.
     */
    private static final double CONTROL_AT_LEAST = 5;

    @TempDir
    static Path tmp;

    private static Map<String, Long> bare;
    private static Map<String, Long> declared;
    private static Map<String, Long> analysed;

    /**
     * Reads both relations once per regime, over one store. One store because the regimes differ
     * only in statistics and a store per regime would differ in its rows as well, which is the
     * confound this measurement exists to avoid.
     */
    @BeforeAll
    static void readEveryRegimeOnce() {
        var ctx = TestConfiguration.testContext();
        var jooq = new JooqCatalog(ctx.jooqPackage(), ctx.codegenLoader());
        try (var store = CapturedStore.ofCatalog(tmp.resolve("worth"),
                MaterializedRegistryFixture.scaledSdl(UNITS), jooq)) {
            DSLContext dsl = store.dsl();
            // H2 serves a repeated identical query from its result cache, which would report the
            // second regime as free rather than as equal. Database-wide, so one statement covers
            // every reader minted below.
            dsl.execute("SET OPTIMIZE_REUSE_RESULTS FALSE");

            StoreStatistics.resetIncludingTheDeclaration(dsl);
            bare = scans(store);

            StoreStatistics.reset(dsl);
            declared = scans(store);

            var baseTables = new TreeSet<String>();
            Materializations.registrations(dsl)
                .forEach(r -> baseTables.add(r.targetTableName().toUpperCase()));
            baseTables.addAll(GraphPartition.keyedBaseTables(dsl));
            baseTables.forEach(table -> dsl.execute("ANALYZE TABLE \"" + table + "\""));
            analysed = scans(store);
        }
    }

    /**
     * The claim. Told only what the model states about its partition dimension, the planner reaches
     * the cost a fully analysed store reaches, on both readers over the reference walk.
     */
    @Test
    @DisplayName("the declaration alone buys the analysed store's cost")
    void theDeclarationAloneReachesTheAnalysedCost() {
        for (String reader : declared.keySet()) {
            assertThat((double) declared.get(reader))
                .as("rows visited reading %s with the partition declaration and nothing analysed,"
                    + " against %d on a fully analysed store", reader, analysed.get(reader))
                .isLessThanOrEqualTo(analysed.get(reader) * DECLARED_WITHIN);
        }
    }

    /**
     * The control, and the reason the claim above is not a tautology about a planner that was going
     * to pick the same index anyway. Without the declaration the same read takes the partition
     * column's own one-column index and scans the graph's partition per driving row.
     */
    @Test
    @DisplayName("without the declaration the same read visits an order of magnitude more")
    void withoutItTheSameReadScansThePartition() {
        assertThat((double) bare.get(DECODE_HOP))
            .as("rows visited reading %s with nothing stated about the partition column at all"
                + " (%d), against %d with the declaration. This is the defect, stated as a test",
                DECODE_HOP, bare.get(DECODE_HOP), declared.get(DECODE_HOP))
            .isGreaterThanOrEqualTo(declared.get(DECODE_HOP) * CONTROL_AT_LEAST);
    }

    /**
     * What the fixture cannot show, asserted rather than omitted. The reader a consumer-size round
     * was measured on visits the same rows declared as bare here, so the control above would mean
     * nothing over it; a later fixture on which it does move is a figure to state, not a surprise.
     */
    @Test
    @DisplayName("the reader the round was measured on is unmoved by the declaration at this size")
    void theInstructionReaderIsUnmovedHere() {
        assertThat(declared.get(INSTRUCTION))
            .as("rows visited reading %s under the declaration, against %d with nothing stated at"
                + " all. Equal: at this fixture size the declaration moves its plan and not its"
                + " count, which is what keeps the control below on the decode hop", INSTRUCTION,
                bare.get(INSTRUCTION))
            .isEqualTo(bare.get(INSTRUCTION));
    }

    // ===== Helpers =====

    private static Map<String, Long> scans(CapturedStore store) {
        var counts = new LinkedHashMap<String, Long>();
        counts.put(DECODE_HOP, scans(store, DECODE_HOP));
        counts.put(INSTRUCTION, scans(store, INSTRUCTION));
        return counts;
    }

    private static final Pattern SCAN_COUNT = Pattern.compile("scanCount: (\\d+)");

    /**
     * What one relation's whole evaluation visits, read through a reader minted per call rather than
     * through the store's writer surface, which is the one session that has already resolved these
     * views and would answer from that resolution; {@code UnregisteredRelation}'s javadoc states the
     * rule this follows.
     */
    private static long scans(CapturedStore store, String relation) {
        try (var reader = store.reader(new ReadBudget.Unbounded())) {
            StoreAnswer<String> answer = reader.read(dsl -> dsl
                .fetch("EXPLAIN ANALYZE SELECT * FROM " + relation).get(0).get(0, String.class));
            if (!(answer instanceof StoreAnswer.Answered<String> plan)) {
                throw new AssertionError(relation + " did not answer, on an unbounded reader");
            }
            long total = 0;
            Matcher counts = SCAN_COUNT.matcher(plan.value());
            while (counts.find()) {
                total += Long.parseLong(counts.group(1));
            }
            return total;
        }
    }
}
