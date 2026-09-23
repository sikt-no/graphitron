package no.sikt.graphitron.rewrite.derive;

import no.sikt.graphitron.model.test.MaterializedRegistryFixture;

import no.sikt.graphitron.common.configuration.TestConfiguration;
import no.sikt.graphitron.model.boot.ReadBudget;
import no.sikt.graphitron.model.boot.StoreAnswer;
import no.sikt.graphitron.model.catalog.GraphPartition;
import no.sikt.graphitron.model.derive.Materializations;
import no.sikt.graphitron.model.test.CapturedStore;
import no.sikt.graphitron.model.test.StoreStatistics;
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
 *       column included. The regime the control was taken against, and a store no run can open any
 *       more: it is what a cold store was before the declaration existed. {@link StoreStatistics#resetIncludingTheDeclaration} is
 *       the only caller of that reset for exactly this reason.
 *   <li><b>declared</b>: the same, with the partition declaration restored and nothing analysed.
 *       This is a cold store as a run meets one, and the regime the claim is about.
 *   <li><b>analysed</b>: the declaration plus {@code ANALYZE} on every base table, which is the most
 *       statistics a store can carry.
 * </ul>
 *
 * <p><b>The two readers, and why neither of them moves any more.</b>
 * {@code graphitron_node_id_decode_hop_rule} and {@code graphitron_node_id_instruction_rule} both reach
 * {@code graphitron_field_reference_step_hop} through the same chain, and on a consumer-size store
 * both paid the cliff; the instruction view is the one an instrumented round measured at 592 s. On
 * this repository's twelve-unit fixture neither moves. Measured: the decode hop visits 524 rows
 * bare, 512 declared and 512 analysed, so the declaration reaches the analysed cost exactly and the
 * bare regime sits twelve rows above it; the instruction view visits 2151 bare, 2151 declared and
 * 2226 analysed, its plan moving where its count does not and the declaration costing it less than
 * a full analysis does.
 *
 * <p><b>That is the third fall in a row, and each one is a key landing underneath this measurement
 * rather than a weaker reading of the same shape.</b> On the single hop table the question opened
 * on, the bare read visited 10939 rows against 1113 declared, a factor of 9.8. The hop became two
 * keyed tables under a union view and the factor fell to 1.36. The input-field resolving table
 * gained the key that declaring it forced and it fell to 1.13. Then both {@code @reference} walk
 * targets became keyed tables whose keys lead with the columns this rule joins them on, and what
 * was left went with them. Every time, the planner gained a seekable ordering it can reach without
 * being told what the partition column holds, and every time that was one less thing the
 * declaration was buying.
 *
 * <p><b>So this file no longer holds a cost control, and says so rather than lowering a floor to
 * keep one.</b> Twelve rows out of 524 cannot tell two plans from one. The note the previous fall
 * left here said what to read when the ratio reached one, and this is that: the declaration has
 * stopped being worth measuring at this fixture size, which is not a reason to widen the control
 * until it passes. What the file keeps is the safety property, that the declared regime never costs
 * more than the analysed one, and both counts pinned as figures, so a change that makes the
 * declaration matter here again fails this test and gets stated rather than passing quietly.
 *
 * <p><b>Which leaves the acceptance evidence for the declaration elsewhere, and that is worth
 * saying plainly.</b> {@code RefreshPlanStatisticsTest} holds which statements plan differently by
 * plan text, and no key touched that; the consumer-scale round behind this work stands as a
 * measurement on the single-table shape; and the declaration remains the only statement of that
 * column available to a pass planning inside a transaction, where no {@code ANALYZE} can run at
 * all. What has no fixture evidence any more is the cost claim, for the reason
 * {@code RefreshPlanStatisticsTest} states last: a per-driving-row cost is linear in driving rows,
 * and a fixture whose partitions are small is precisely where a partition scan is cheap.
 */
@PipelineTier
class PartitionSelectivityWorthTest {

    /** Repetitions of the fixture's node cluster, twelve for {@link DerivedReadCostTest}'s reason. */
    private static final int UNITS = 12;

    /**
     * The reader the cliff was visible on until keys on the relations it reads took it: the rule
     * whose read of {@code graphitron_field_reference_step_hop} was the index choice the
     * declaration decided. It also joins both {@code @reference} walk targets, on the columns their
     * keys lead with, which is how the last fall below reached it.
     */
    private static final String DECODE_HOP = "graphitron_node_id_decode_hop_rule";

    /** The reader the consumer-scale measurement was taken on, unmoved at this fixture size. */
    private static final String INSTRUCTION = "graphitron_node_id_instruction_rule";

    /**
     * How far the declared regime may sit above the analysed one, as measured and not as guessed:
     * the decode hop visits 512 rows under either, and the instruction view visits 2151 declared
     * against 2226 analysed, so the declaration reaches the analysed plan's cost on one reader and
     * sits below it on the other. A tenth is slack for a plan that shifts without the claim
     * failing.
     */
    private static final double DECLARED_WITHIN = 1.1;

    /**
     * How close to the declared regime the bare one reads now, which is the figure that replaced
     * this file's control. Measured at 1.023 on the decode hop, 524 rows against 512, and at
     * exactly one on the instruction view. A twentieth is slack for a fixture detail; a bare regime
     * that climbs past it is the cliff coming back, which is a figure to state here rather than a
     * change to absorb silently.
     */
    private static final double UNMOVED_WITHIN = 1.05;

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
     * What the control became. Without the declaration this read used to take the partition column's
     * own one-column index and scan the graph's partition per driving row, and the gap that opened
     * was the defect stated as a test. Three keys on relations this rule reads have since closed it,
     * the last two arriving together when both {@code @reference} walk targets became keyed tables.
     * Asserting the collapse rather than deleting the case is what keeps the figure pinned: the gap
     * returning fails here and has to be restated, where a deleted case would let it pass.
     */
    @Test
    @DisplayName("the declaration no longer moves this reader's count at this fixture size")
    void theDecodeHopIsNoLongerMovedEither() {
        assertThat((double) bare.get(DECODE_HOP))
            .as("rows visited reading %s with nothing stated about the partition column at all"
                + " (%d), against %d with the declaration. These differed by enough to hold a"
                + " control until keys on the relations it reads closed the gap; a gap reappearing"
                + " is a figure to state", DECODE_HOP, bare.get(DECODE_HOP),
                declared.get(DECODE_HOP))
            .isLessThanOrEqualTo(declared.get(DECODE_HOP) * UNMOVED_WITHIN);
    }

    /**
     * The same fact on the other reader, where it held from the start rather than arriving with a
     * key. The reader a consumer-size round was measured on visits the same rows declared as bare
     * here, exactly rather than within a slack, which is why it never carried the control. A later
     * fixture on which it does move is a figure to state, not a surprise.
     */
    @Test
    @DisplayName("the reader the round was measured on is unmoved by the declaration at this size")
    void theInstructionReaderIsUnmovedHere() {
        assertThat(declared.get(INSTRUCTION))
            .as("rows visited reading %s under the declaration, against %d with nothing stated at"
                + " all. Equal: at this fixture size the declaration moves its plan and not its"
                + " count, which is now what both readers do", INSTRUCTION,
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
