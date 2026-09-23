package no.sikt.graphitron.rewrite.derive;

import no.sikt.graphitron.common.configuration.TestConfiguration;
import no.sikt.graphitron.model.derive.DerivationStratum;
import no.sikt.graphitron.model.derive.StageProgress;
import no.sikt.graphitron.model.derive.ViewReferences;
import no.sikt.graphitron.model.jooq.JooqCatalog;
import no.sikt.graphitron.model.run.ModelCapture;
import no.sikt.graphitron.model.test.CapturedStore;
import no.sikt.graphitron.model.test.FactStores;
import no.sikt.graphitron.model.test.ScaledSchemaFixture;
import no.sikt.graphitron.model.test.StoreStatistics;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.table;

/**
 * Whether each stage of the derivation stratum is planned against statistics on the stage tables
 * its rule reads, which is what the stratum's analysing cadence exists to make true on a store none
 * of whose stratum tables holds a row.
 *
 * <p><b>What this asserts and why it is the right thing to assert.</b> Not a wall clock: a tier
 * that must not fail for being slow cannot hold a figure. What a stage is entitled to is statistics
 * on the tables an earlier step wrote that its own rule reads, read off the rule view by
 * {@link ViewReferences#tablesReachedBy(DSLContext, String)}, and whether it has them at the moment
 * its statements go out is a fact about statistics rather than about plans: cheap to observe,
 * exact, and unaffected by how fast the machine is. The instant is the stage's started event, which
 * the stratum emits before a statement is issued and which is exactly the instant this needs.
 *
 * <p><b>Two legs on the cadences themselves, because one of them is the control.</b> An assertion
 * that the analysing cadence analyses its prerequisites is a tautology unless something says the
 * one-transaction cadence does not, and on a store with no statistics it cannot: H2's
 * {@code ANALYZE} commits, so nothing inside one transaction is analysed until it ends.
 *
 * <p><b>Two legs on the capture, because the cadence is chosen and the choice is the part that
 * breaks.</b> The legs above drive {@link DerivationStratum} directly, so together they say what
 * each cadence does and nothing about which one a capture takes. A cold capture into a fresh store
 * has to take the analysing one; a warm capture takes the one-transaction cadence and meets its
 * prerequisites analysed anyway, the capture having stated statistics over the previous capture's
 * rows before the stratum began.
 */
@PipelineTier
class StagePrerequisiteStatisticsTest {

    /** Repetitions of the fixture's node cluster: enough that the prerequisite tables hold rows. */
    private static final int UNITS = 12;

    @TempDir
    static Path tmp;

    private static Map<String, Set<String>> unanalysedUnderAnalysing;
    private static Map<String, Set<String>> unanalysedUnderOneTransaction;
    private static Map<String, Set<String>> unanalysedUnderColdCapture;
    private static Map<String, Set<String>> unanalysedUnderWarmCapture;
    private static Set<String> dependentStages;
    private static Set<String> dependentStagesUnderColdCapture;
    private static Set<String> dependentStagesUnderWarmCapture;

    /**
     * Runs both cadences over one captured store, each from a reset. One store because the cadences
     * differ in their transactions and their {@code ANALYZE} placement, and a store per cadence would
     * differ in its rows as well.
     *
     * <p>The stratum runs without the classification domain's producer, which is the one step that
     * reads an executable schema rather than rows and would clear the domain if handed none. Its
     * table stays as the capture wrote it, which is all the steps after it need of it.
     */
    @BeforeAll
    static void observeBothCadences() {
        var ctx = TestConfiguration.testContext();
        var jooq = new JooqCatalog(ctx.jooqPackage(), ctx.codegenLoader());
        try (var store = CapturedStore.ownStoreOfCatalog(tmp.resolve("prerequisites"),
                ScaledSchemaFixture.scaledSdl(UNITS), jooq)) {
            DSLContext dsl = store.dsl();
            var steps = DerivationStratum.steps(null).stream()
                .filter(step -> !step.name().equals("ClassificationDomainCapture"))
                .toList();
            var prerequisites = populated(dsl, prerequisiteTables(dsl));
            dependentStages = prerequisites.keySet();

            StoreStatistics.reset(dsl);
            var inOne = new UnanalysedPrerequisites(dsl, prerequisites);
            dsl.transaction(tx -> DerivationStratum.runInOne(tx.dsl(), CapturedStore.GRAPH, steps,
                inOne));
            unanalysedUnderOneTransaction = inOne.observed();

            StoreStatistics.reset(dsl);
            var analysing = new UnanalysedPrerequisites(dsl, prerequisites);
            DerivationStratum.runAnalysing(dsl, CapturedStore.GRAPH, steps, analysing);
            unanalysedUnderAnalysing = analysing.observed();
        }
    }

    /**
     * Runs a capture into a fresh store, which is cold and has to take the analysing cadence, and
     * then a second capture into the same store, which is warm and takes the other. Both observed
     * the way the legs above observe; the population is filtered after each capture rather than
     * before it, a fresh store holding no row until the capture being observed has written one.
     */
    @BeforeAll
    static void observeTheCaptureItself() {
        var ctx = TestConfiguration.testContext();
        var jooq = new JooqCatalog(ctx.jooqPackage(), ctx.codegenLoader());
        Path directory = tmp.resolve("captured");
        try (var store = FactStores.inMemory()) {
            DSLContext dsl = store.dsl();
            CapturedStore.writeSource(directory, ScaledSchemaFixture.scaledSdl(UNITS));
            var graph = CapturedStore.graph(directory);
            var config = CapturedStore.corpusOf(directory);
            LocalDateTime readAt = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);

            var prerequisites = prerequisiteTables(dsl);
            var cold = new UnanalysedPrerequisites(dsl, prerequisites);
            ModelCapture.capture(dsl, graph, config, List.of(), jooq, readAt, cold);
            var coldPopulated = populated(dsl, prerequisites);
            dependentStagesUnderColdCapture = coldPopulated.keySet();
            unanalysedUnderColdCapture = retained(cold.observed(), coldPopulated);

            var warm = new UnanalysedPrerequisites(dsl, prerequisites);
            ModelCapture.capture(dsl, graph, config, List.of(), jooq, readAt, warm);
            var warmPopulated = populated(dsl, prerequisites);
            dependentStagesUnderWarmCapture = warmPopulated.keySet();
            unanalysedUnderWarmCapture = retained(warm.observed(), warmPopulated);
        }
    }

    @Test
    @DisplayName("a cold capture meets every stage's prerequisites analysed")
    void aColdCaptureMeetsThemAnalysed() {
        assertThat(dependentStagesUnderColdCapture)
            .as("stages whose rule reads a populated table an earlier step wrote, on the store the"
                + " capture filled. Non-empty, or this leg is vacuous")
            .isNotEmpty();
        assertThat(unanalysedUnderColdCapture)
            .as("stage -> the earlier steps' tables its rule reads that carried no statistics when"
                + " its statements were issued, during a capture into a fresh store. None: a store"
                + " no stratum table holds a row in takes the analysing cadence")
            .isEmpty();
    }

    @Test
    @DisplayName("a warm capture meets every stage's prerequisites analysed")
    void aWarmCaptureMeetsThemAnalysed() {
        assertThat(dependentStagesUnderWarmCapture).isNotEmpty();
        assertThat(unanalysedUnderWarmCapture)
            .as("stage -> the earlier steps' tables its rule reads that carried no statistics when"
                + " its statements were issued, during a second capture. None: the capture states"
                + " statistics over the previous capture's rows before the stratum begins")
            .isEmpty();
    }

    @Test
    @DisplayName("the analysing cadence meets every stage's prerequisites analysed")
    void theAnalysingCadenceMeetsThemAnalysed() {
        assertThat(unanalysedUnderAnalysing)
            .as("stage -> unanalysed prerequisites under the cadence that commits and analyses each"
                + " step. None: that is what the cadence is for")
            .isEmpty();
    }

    /**
     * The control, and the reason the claim above is not a tautology. Run inside one transaction on
     * a store with no statistics, every dependent stage meets its prerequisites unanalysed, because
     * nothing can be analysed until the transaction ends. This is what a cold store would get if the
     * cadence were ever chosen for it by the caller rather than by the store's state.
     */
    @Test
    @DisplayName("the one-transaction cadence on a store with no statistics meets them all unanalysed")
    void theOneTransactionCadenceMeetsThemAllUnanalysed() {
        assertThat(dependentStages)
            .as("stages whose rule reads a populated table an earlier step wrote. Non-empty, or the"
                + " fixture exercises no dependent stage and every claim here is vacuous")
            .isNotEmpty();
        assertThat(unanalysedUnderOneTransaction.keySet())
            .containsExactlyInAnyOrderElementsOf(dependentStages);
    }

    // ===== Helpers =====

    /**
     * Each stage's prerequisite tables: the tables an earlier step writes that its rule view reaches.
     * A stage whose rule is jOOQ rather than a stored view has no definition to read here and is left
     * out, the order gate being where its read set is checked.
     */
    private static Map<String, Set<String>> prerequisiteTables(DSLContext dsl) {
        var prerequisites = new TreeMap<String, Set<String>>();
        var earlier = new HashSet<String>();
        for (var step : DerivationStratum.steps(null)) {
            if (step.writes().size() == 1) {
                String rule = step.writes().iterator().next() + "_rule";
                if (isView(dsl, rule)) {
                    var read = new LinkedHashSet<>(ViewReferences.tablesReachedBy(dsl, rule));
                    read.retainAll(earlier);
                    if (!read.isEmpty()) {
                        prerequisites.put(step.name(), read);
                    }
                }
            }
            earlier.addAll(step.writes());
        }
        return prerequisites;
    }

    /**
     * The same map with the empty tables dropped, and with them any stage left asking about nothing.
     * {@code ANALYZE} on an empty table records nothing, so an empty prerequisite reports unanalysed
     * forever and would read here as a defect it cannot be.
     */
    private static Map<String, Set<String>> populated(DSLContext dsl,
                                                      Map<String, Set<String>> prerequisites) {
        var kept = new TreeMap<String, Set<String>>();
        prerequisites.forEach((stage, tables) -> {
            var holding = new LinkedHashSet<String>();
            tables.stream()
                .filter(t -> dsl.fetchCount(table(name(t.toUpperCase(Locale.ROOT)))) > 0)
                .forEach(holding::add);
            if (!holding.isEmpty()) {
                kept.put(stage, holding);
            }
        });
        return kept;
    }

    private static Map<String, Set<String>> retained(Map<String, Set<String>> observed,
                                                     Map<String, Set<String>> populated) {
        var kept = new TreeMap<String, Set<String>>();
        observed.forEach((stage, unanalysed) -> {
            var within = new TreeSet<>(unanalysed);
            within.retainAll(populated.getOrDefault(stage, Set.of()));
            if (!within.isEmpty()) {
                kept.put(stage, within);
            }
        });
        return kept;
    }

    private static boolean isView(DSLContext dsl, String relation) {
        return dsl.fetchExists(dsl.selectOne()
            .from(table(name("INFORMATION_SCHEMA", "VIEWS")))
            .where(field(name("TABLE_NAME"), String.class)
                .eq(relation.toUpperCase(Locale.ROOT))));
    }

    /** Reads the statistics state at the moment each stage's statements go out. */
    private static final class UnanalysedPrerequisites implements StageProgress {

        private final DSLContext dsl;
        private final Map<String, Set<String>> prerequisites;
        private final Map<String, Set<String>> observed = new TreeMap<>();

        UnanalysedPrerequisites(DSLContext dsl, Map<String, Set<String>> prerequisites) {
            this.dsl = dsl;
            this.prerequisites = prerequisites;
        }

        Map<String, Set<String>> observed() {
            return observed;
        }

        @Override
        public void observe(Event event) {
            if (!(event instanceof Event.StageStarted started)) {
                return;
            }
            String stage = started.step().name();
            var unanalysed = new TreeSet<String>();
            for (String prerequisite : prerequisites.getOrDefault(stage, Set.of())) {
                if (!StoreStatistics.analysed(dsl, prerequisite)) {
                    unanalysed.add(prerequisite);
                }
            }
            if (!unanalysed.isEmpty()) {
                observed.put(stage, unanalysed);
            }
        }
    }
}
