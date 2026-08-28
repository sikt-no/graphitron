package no.sikt.graphitron.rewrite.derive;

import no.sikt.graphitron.common.configuration.TestConfiguration;
import no.sikt.graphitron.model.boot.ReadBudget;
import no.sikt.graphitron.model.boot.StoreAnswer;
import no.sikt.graphitron.model.derive.Materializations;
import no.sikt.graphitron.rewrite.CapturedStore;
import no.sikt.graphitron.rewrite.JooqCatalog;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Which of the materialization refresh's statements plan differently on a cold store, and whose
 * statistics decide it.
 *
 * <p><b>Why the question exists.</b> {@code Materializations.analyse} runs after the capture
 * transaction commits, because H2 commits as a side effect of {@code ANALYZE} and a commit between
 * the refresh's delete and its inserts would publish the emptied target the one-transaction
 * contract exists to prevent. So every statement a cold capture's refresh issues is planned with no
 * selectivity on anything it reads, while every timing anyone has ever taken of those same
 * statements was taken afterwards, against a store {@code analyse} had run on. Those are not
 * necessarily the same plan, and until this test there was nothing in the tree that said whether
 * they were.
 *
 * <p><b>What this is worth, so nobody reads it as more than it is.</b> A secondary cost. The register
 * has a larger and separate problem, an unregistered view re-evaluated once per driving row inside a
 * correlated term, which no statistics inform because there is nothing there for a planner to choose;
 * {@code docs/architecture/explanation/fact-model.adoc} carries both. What this test holds is the
 * smaller one, and the reason it is held at all is that it is invisible from every measurement anyone
 * takes: a settled store is the only store a timing can be taken against, and a settled store is
 * precisely the one whose plans this says the refresh does not get.
 *
 * <p><b>Two populations, and only one of them is reachable from before the refresh starts.</b> The
 * refresh reads base fact tables, which capture wrote earlier in the same transaction, and it reads
 * <em>registered targets</em>, which the refresh itself writes one at a time. Committing the facts
 * ahead of the refresh and analysing them would state statistics for the first population and could
 * not state them for the second, because the second has not been written yet. The two assertions
 * below are that split: the first says the plans move at all, the second says which population
 * moving them belongs to.
 *
 * <p><b>Instrument: the executed plan, not the explained one.</b> {@code EXPLAIN} without
 * {@code ANALYZE} is the tempting instrument, since it needs no execution, and on this schema it is
 * not faithful. H2 optimizes a view's inner query per set of index conditions, at execution, and a
 * bare {@code EXPLAIN} renders the unmasked form; measured here, it reported three of the seven
 * registrations then in the set below and silently agreed on the other four, whose rows visited move
 * by factors of three to eight. {@code EXPLAIN ANALYZE} renders what ran.
 *
 * <p><b>And the whole plan text, minus {@code scanCount}, rather than a reduction of it.</b> A
 * hand-rolled reduction to the relation order and the index names was tried first and is the reason
 * the paragraph above can name a number: it missed four of those seven, because the difference lives
 * in the seek conditions and not in which index is named. The failing shape is one index used two
 * ways, and dropping the conditions drops exactly the evidence. {@code scanCount} is the one
 * rendering that varies with statistics by construction, being a count of rows visited, so it is the
 * one thing removed; nothing else in this schema's plans was found to carry an estimate.
 *
 * <p><b>The statistics regimes.</b> Four, all over one captured store with the same rows and the
 * same declared indexes throughout, so statistics are the only thing that varies.
 * {@link StoreStatistics#reset} is the reset, and that type carries why the statement it issues
 * states "no statistics" rather than a third state of its own.
 *
 * <ul>
 *   <li><b>settled</b>: the store as a capture leaves it, before anything here touches it.
 *   <li><b>cold</b>: no selectivity anywhere, which is what the refresh inside a cold capture plans
 *       against.
 *   <li><b>facts</b>: the base fact tables analysed and the registered targets not, which is the
 *       most that committing and analysing the facts ahead of a still-single-transaction refresh
 *       could buy.
 *   <li><b>targets</b>: the registered targets analysed and the base facts not, which is what a
 *       refresh that committed and analysed each target as it refilled it would give the
 *       registrations after it. Faithful as an upper bound rather than only as an approximation:
 *       {@code MaterializeDependencies} refuses a registration whose source view reads its own
 *       target, and orders every registration after the ones whose targets it reads, so a
 *       registration under that shape would meet exactly this state.
 * </ul>
 */
@PipelineTier
class RefreshPlanStatisticsTest {

    /**
     * Repetitions of the fixture's node cluster, which {@link MaterializedRegistryFixture} scales.
     * Twelve for {@code DerivedReadCostTest}'s reason and not independently: it is the size at which
     * the targets these gates measure hold rows, and a plan chosen over an empty relation is not the
     * plan that ships.
     *
     * <p>Not <em>every</em> registered target, which this note claimed until it was measured. Two are
     * empty at this size and at any other, {@code intent_mutation_payload_key_membership} and
     * {@code intent_mutation_payload_refusal}: their rules read a {@code @mutation} payload surface the
     * fixture holds fixed, and neither rule has rows to state about what it holds. Scale is not the
     * lever for them, so {@code RefreshPrerequisiteStatisticsTest} asks per target rather than
     * trusting the size, which is the shape a later gate over this fixture should copy.
     */
    private static final int UNITS = 12;

    /**
     * The registrations whose refresh plans differently with the registered targets' statistics than
     * without them, {@code source_view}, pinned by equality so the set cannot grow or shrink
     * unremarked.
     *
     * <p>One mechanism, seen plainly on {@code intent_node_id_decode_hop_column_live}. With
     * statistics its read of {@code intent_field_reference_step_hop}, a registered target, seeks
     * {@code IX_FIELD_REFERENCE_STEP_HOP_STEP} on seven columns; without them it seeks
     * {@code CONSTRAINT_INDEX_98} on {@code GRAPH_NAME} alone, which is a scan of the whole graph's
     * partition per driving row. H2's no-statistics default assumes every column has half as many
     * distinct values as the table has rows, which is a wild over-estimate of a partition column's
     * selectivity, so the one-column seek prices as though it were nearly exact. That is the case
     * {@code Materializations.analyse}'s own javadoc describes, arriving where that call cannot
     * reach: an index without statistics, costing most of the gain the index exists for.
     *
     * <p>Rows visited, cold against targets-analysed, on this fixture and as documentation rather
     * than as an assertion: the decode hop column 11213 against 1387, the input-field filter role
     * 34323 against 4845, the payload refusal 42685 against 13207, the payload column 41961 against
     * 12483, the field column scope 12115 against 2854. The last two rows of the set move the plan
     * without moving the count much, the field scope table 3260 against 3243 and the node-id
     * instruction 1854 against 1854, and they are in the set because the claim is about the plan.
     * Wall clocks move in the same direction and by less, the widest being the decode hop column at
     * 103 milliseconds against 29. No number here is asserted, for {@code DerivedReadCostTest}'s
     * reason: a tier that must not fail for being slow cannot hold a figure.
     *
     * <p>An eighth row joined the set when {@code intent_input_field_carrier_role} was registered,
     * and it is the mechanism above arriving on a new statement rather than a new mechanism: that
     * rule reads {@code intent_input_field_filter_role} and {@code intent_node_id_decode_column},
     * both registered targets, so the plan it gets turns on statistics the refresh itself has to
     * have written. A registration whose source view reads another registration's target is the
     * shape that joins this set, which is worth stating because both registrations landed in the
     * same increment and only one of them joined: the decode column rule reads the hop column
     * target and plans identically either way.
     *
     * <p>What the figures do <em>not</em> say is what this costs a schema of consumer size. Nothing
     * in this repo captures one, and the ratios above are taken over a twelve-unit fixture whose
     * whole point is that it understates: a per-driving-row cost is linear in driving rows, and the
     * gap between a seven-column seek and a partition scan widens with the partition.
     */
    private static final Set<String> PLAN_DEPENDS_ON_STATISTICS = Set.of(
        "intent_field_column_scope_live",
        "intent_input_field_carrier_role_live",
        "intent_field_scope_table_live",
        "intent_input_field_filter_role_live",
        "intent_mutation_payload_column_live",
        "intent_mutation_payload_refusal_live",
        "intent_node_id_decode_hop_column_live",
        "intent_node_id_instruction_live");

    @TempDir
    static Path tmp;

    private static List<Materializations.Registration> registrations;
    private static Map<String, String> settled;
    private static Map<String, String> cold;
    private static Map<String, String> facts;
    private static Map<String, String> targets;

    /**
     * Plans every registration's source view once per regime, over one store. One store because the
     * regimes differ only in statistics and a store per regime would differ in its rows as well,
     * which is the confound this measurement exists to avoid.
     */
    @BeforeAll
    static void planEveryRegimeOnce() {
        var ctx = TestConfiguration.testContext();
        var jooq = new JooqCatalog(ctx.jooqPackage(), ctx.codegenLoader());
        try (var store = CapturedStore.ofCatalog(tmp.resolve("plans"),
                MaterializedRegistryFixture.scaledSdl(UNITS), jooq)) {
            DSLContext dsl = store.dsl();
            // H2 serves a repeated identical query from its result cache, which would report the
            // second regime's plan as free rather than as equal; the fact model page carries the
            // measurement behind that. Database-wide, so one statement covers every reader minted.
            dsl.execute("SET OPTIMIZE_REUSE_RESULTS FALSE");
            registrations = Materializations.registrations(dsl);
            var targetNames = new TreeSet<String>();
            registrations.forEach(r -> targetNames.add(r.targetTableName().toUpperCase()));
            List<String> factTables = baseTables(dsl).stream()
                .filter(name -> !targetNames.contains(name)).toList();

            settled = plans(store);
            cold = regime(store, dsl, List.of());
            facts = regime(store, dsl, factTables);
            targets = regime(store, dsl, List.copyOf(targetNames));
        }
    }

    /**
     * The claim. A registration outside the pinned set plans identically whether or not the targets
     * it reads carry statistics; one inside it does not, and the set is exactly what was observed.
     * Equality both ways, {@code DerivedReadCostTest}'s ratchet: a registration that joins the set is
     * a new statement whose cold plan nobody has looked at, and one that leaves it is a row to delete
     * rather than a tolerance that survives.
     */
    @Test
    void theRefreshStatementsWhosePlanNeedsStatisticsAreExactlyTheOnesRecorded() {
        assertThat(differingFrom(targets, cold))
            .as("registrations whose refresh plans differently with the registered targets'"
                + " statistics than without them, which is the difference between a settled store"
                + " and the store a cold capture's own refresh runs against")
            .containsExactlyInAnyOrderElementsOf(PLAN_DEPENDS_ON_STATISTICS);
    }

    /**
     * Which half of the transaction boundary the difference belongs to, and the assertion that would
     * fail the day somebody landed the cheap half believing it closed this.
     *
     * <p>Committing capture's facts ahead of the refresh and analysing them is the one thing a single
     * transaction can still do, and it reaches none of the set above: every registration whose plan
     * moves is reading a <em>registered target</em>, which no statement before the refresh can have
     * analysed because no statement before the refresh has written it. Stated as a superset rather
     * than as an equality because the fact tables' statistics move two further registrations onto
     * plans of their own ({@code intent_carrier_data_field_live} and, differently from cold,
     * {@code intent_field_scope_table_live}), which is a second finding and not this claim: what this
     * asserts is that the cheap half closes nothing, not that it changes nothing.
     */
    @Test
    void analysingTheFactsAloneReachesNoneOfThem() {
        assertThat(differingFrom(targets, facts))
            .as("registrations still planning differently once the base fact tables are analysed"
                + " and the registered targets are not. Every registration whose plan needs"
                + " statistics is reading a target the refresh itself writes, so committing and"
                + " analysing the facts ahead of the refresh cannot reach it")
            .containsAll(PLAN_DEPENDS_ON_STATISTICS);
    }

    /**
     * The reference leg is the state a capture actually leaves, rather than a regime invented here.
     * {@code Materializations.analyse} covers the registered targets and nothing else, and on a
     * fixture this size H2's own automatic analysis does not fire on the fact tables, its threshold
     * being two thousand changes to a table. So the settled store's plans are the targets-analysed
     * plans, and the comparison above is between the cold refresh and the store every timing in this
     * investigation was taken against.
     */
    @Test
    void theSettledStoreIsTheTargetsAnalysedRegime() {
        assertThat(differingFrom(targets, settled))
            .as("registrations whose plan on the store a capture leaves differs from the plan with"
                + " the registered targets analysed. None: analysing the targets is what a capture"
                + " does, and nothing else it does moves a plan")
            .isEmpty();
    }

    // ===== Helpers =====

    private static Set<String> differingFrom(Map<String, String> reference, Map<String, String> other) {
        var differing = new TreeSet<String>();
        reference.forEach((view, plan) -> {
            if (!plan.equals(other.get(view))) {
                differing.add(view);
            }
        });
        return differing;
    }

    /** Resets every column's selectivity, analyses {@code analysed}, and plans every registration. */
    private static Map<String, String> regime(CapturedStore store, DSLContext dsl,
                                              List<String> analysed) {
        StoreStatistics.reset(dsl);
        analysed.forEach(table -> dsl.execute("ANALYZE TABLE \"" + table + "\""));
        return plans(store);
    }

    private static Map<String, String> plans(CapturedStore store) {
        var plans = new LinkedHashMap<String, String>();
        for (var registration : registrations) {
            plans.put(registration.sourceViewName(),
                executedPlan(store, registration.sourceViewName()));
        }
        return plans;
    }

    /**
     * What ran, with the rows-visited annotation removed. Read through a reader minted per call
     * rather than through the store's writer surface, which is the one session that has already
     * resolved these views and would answer from that resolution;
     * {@code UnregisteredRelation}'s javadoc states the rule this follows.
     */
    private static String executedPlan(CapturedStore store, String relation) {
        try (var reader = store.reader(new ReadBudget.Unbounded())) {
            StoreAnswer<String> answer = reader.read(dsl -> dsl
                .fetch("EXPLAIN ANALYZE SELECT * FROM " + relation).get(0).get(0, String.class));
            if (!(answer instanceof StoreAnswer.Answered<String> plan)) {
                throw new AssertionError(relation + " did not answer, on an unbounded reader");
            }
            return plan.value().replaceAll("scanCount: \\d+", "");
        }
    }

    private static List<String> baseTables(DSLContext dsl) {
        return dsl.fetch("""
            SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES
            WHERE TABLE_SCHEMA = 'PUBLIC' AND TABLE_TYPE = 'BASE TABLE'
            ORDER BY TABLE_NAME
            """).map(row -> row.get(0, String.class));
    }

}
