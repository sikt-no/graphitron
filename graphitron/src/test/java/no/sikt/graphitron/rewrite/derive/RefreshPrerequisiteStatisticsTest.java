package no.sikt.graphitron.rewrite.derive;

import no.sikt.graphitron.model.test.MaterializedRegistryFixture;

import no.sikt.graphitron.common.configuration.TestConfiguration;
import no.sikt.graphitron.model.capture.FactCapture;
import no.sikt.graphitron.model.derive.Materializations;
import no.sikt.graphitron.model.derive.RefreshProgress;
import no.sikt.graphitron.model.run.ModelCapture;
import no.sikt.graphitron.model.schema.SchemaAssembly;
import no.sikt.graphitron.model.schema.SdlVerdicts;
import no.sikt.graphitron.model.test.CapturedStore;
import no.sikt.graphitron.model.test.FactStores;
import no.sikt.graphitron.model.jooq.JooqCatalog;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.table;

/**
 * Whether a registration's refresh statements are planned against statistics on the targets they
 * read, which is what {@code Materializations.refreshAnalysing} exists to make true and what the
 * cadence a capture used before it could not.
 *
 * <p><b>What this asserts and why it is the right thing to assert.</b> Not a wall clock, for
 * {@code DerivedReadCostTest}'s reason: a tier that must not fail for being slow cannot hold a
 * figure. Not a plan comparison either, because {@code RefreshPlanStatisticsTest} already holds that
 * half and holds it better, over four statistics regimes on one store. What that test establishes is
 * that a store with its registered targets analysed plans exactly as a settled store does, on every
 * registration. So the only thing left to say about the cadence is whether it reaches that state
 * before each registration reads it, and that is a fact about statistics rather than about plans:
 * cheap to observe, exact, and unaffected by how fast the machine is.
 *
 * <p><b>The population, and why it is the dependency rows rather than every target.</b> A
 * registration is entitled to statistics on the targets its own source view reads, which is what
 * {@code meta_materialize_dependency} records. It is not entitled to statistics on a target no rule
 * of its reads, and asserting over every target would fail the first registration in the order for
 * not having what it does not use. {@code MaterializeDependencies} refuses a registration whose
 * source view reads its own target, so a registration's prerequisites are always relations some
 * earlier registration filled, and the claim below is reachable rather than merely desirable.
 *
 * <p><b>Two legs, because one of them is the control.</b> The pair matters more than either half: an
 * assertion that the new cadence analyses its prerequisites is a tautology unless something says the
 * old one does not, and it is the old one that shipped for as long as this defect went unmeasured.
 *
 * <p><b>A third leg, over the selector rather than over the cadences.</b> The two legs above call
 * {@code Materializations} directly, so together they say what each cadence does and nothing at all
 * about which one a capture takes. That was the gap the defect lived in: the selector read
 * {@code store_graph} as a proxy for the register's state, three unrelated writers mint that anchor
 * row before a capture, and so every consumer build took the in-transaction cadence on a store with
 * no statistics anywhere while both cadences went on behaving exactly as pinned. The third leg
 * drives the capture pass on a store whose anchor was pre-written the way the build
 * writes it, and asserts the same claim as the first. Same observation, same instrument, one step
 * further out.
 */
@PipelineTier
class RefreshPrerequisiteStatisticsTest {

    /**
     * Repetitions of the fixture's node cluster, twelve to match the read-cost gate and
     * {@code RefreshPlanStatisticsTest} rather than for a reason of this test's own: what this test
     * needs from the size is that the targets it asserts over hold rows, which the population filter
     * below establishes per target rather than by trusting the size.
     */
    private static final int UNITS = 12;

    @TempDir
    static Path tmp;

    private static Map<String, Set<String>> unanalysedUnderSplitCadence;
    private static Map<String, Set<String>> unanalysedUnderCallerCadence;
    private static Map<String, Set<String>> unanalysedUnderCapture;
    private static Set<String> dependentRegistrations;
    private static Set<String> dependentRegistrationsUnderCapture;

    /**
     * Runs both cadences over one store, each from a reset, and records for every registration which
     * of its prerequisite targets carried no statistics when its statements were issued. One store
     * because the cadences differ in their transactions and their {@code ANALYZE} placement, and a
     * store per cadence would differ in its rows as well.
     */
    @BeforeAll
    static void observeBothCadences() {
        var ctx = TestConfiguration.testContext();
        var jooq = new JooqCatalog(ctx.jooqPackage(), ctx.codegenLoader());
        try (var store = CapturedStore.ownStoreOfCatalog(tmp.resolve("prerequisites"),
                MaterializedRegistryFixture.scaledSdl(UNITS), jooq)) {
            DSLContext dsl = store.dsl();
            Map<String, Set<String>> prerequisites = populated(dsl, prerequisiteTargets(dsl));
            dependentRegistrations = prerequisites.keySet();

            StoreStatistics.reset(dsl);
            var caller = new UnanalysedPrerequisites(dsl, prerequisites);
            dsl.transaction(tx -> Materializations.refresh(tx.dsl(), CapturedStore.GRAPH, caller));
            unanalysedUnderCallerCadence = caller.observed();

            StoreStatistics.reset(dsl);
            var split = new UnanalysedPrerequisites(dsl, prerequisites);
            Materializations.refreshAnalysing(dsl, CapturedStore.GRAPH, split);
            unanalysedUnderSplitCadence = split.observed();
        }
    }

    /**
     * Runs a capture the way a build runs one: the run-configuration families first, which lead with
     * the graph's {@code store_graph} anchor, and the generator's capture into the same store after
     * them. Records what each registration met, exactly as the legs above do.
     *
     * <p>A store of its own and a capture of its own, because the subject here is a capture into a
     * store nothing has written a target row into yet, and the store the legs above use has been
     * captured into twice by the time they are done.
     *
     * <p>The population is filtered after the capture rather than before it: a fresh store's targets
     * are all empty, and {@code ANALYZE} on an empty table records nothing, so a target that stays
     * empty would read here as a defect it cannot be.
     */
    @BeforeAll
    static void observeTheCaptureItself() {
        var ctx = TestConfiguration.testContext();
        var jooq = new JooqCatalog(ctx.jooqPackage(), ctx.codegenLoader());
        Path directory = tmp.resolve("anchored");
        try (var store = FactStores.inMemory()) {
            DSLContext dsl = store.dsl();
            var registry = CapturedStore.registryOf(directory,
                MaterializedRegistryFixture.scaledSdl(UNITS));
            var graph = CapturedStore.graph(directory);
            var config = CapturedStore.corpusOf(directory);
            LocalDateTime readAt = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);
            // The writer that made the old selector wrong, in the position a build puts it:
            // AbstractRewriteMojo.captureModel writes these families into the store the generator
            // is about to capture into, and the pass leads with the anchor row.
            ModelCapture.capture(dsl, graph, config, List.of(), jooq, readAt);

            var prerequisites = prerequisiteTargets(dsl);
            var observer = new UnanalysedPrerequisites(dsl, prerequisites);
            // Warm, which is what a build's own capture is told here and not a choice of this
            // test's: RunStore reads the graph's store_graph row as "this graph has been captured
            // before", and the pass above has just written one. So the capture stands its own rows
            // down before rewriting them, which is why this leg does not collide with the recipe
            // families that pass wrote.
            ModelCapture.capture(dsl, graph, config, List.of(), jooq, readAt);
            FactCapture.derive(dsl, graph, SchemaAssembly.of(registry), readAt, observer);

            var populated = populated(dsl, prerequisites);
            dependentRegistrationsUnderCapture = populated.keySet();
            unanalysedUnderCapture = new TreeMap<>();
            observer.observed().forEach((view, unanalysed) -> {
                var kept = new TreeSet<>(unanalysed);
                kept.retainAll(populated.getOrDefault(view, Set.of()));
                if (!kept.isEmpty()) {
                    unanalysedUnderCapture.put(view, kept);
                }
            });
        }
    }

    /**
     * The claim, one step out: a capture picks the cadence that gives every registration statistics
     * on the targets its own rule reads, on a store whose anchor row another writer had already
     * minted. This is the invariant the selector broke, and the leg that fails on the predicate it
     * used to use.
     */
    @Test
    @DisplayName("a capture behind a pre-written anchor row still meets every prerequisite analysed")
    void aCaptureBehindAPreWrittenAnchorMeetsThemAnalysed() {
        assertThat(dependentRegistrationsUnderCapture)
            .as("registrations whose source view reads another registration's populated target, on"
                + " the store the capture filled. Non-empty, or this leg is vacuous")
            .isNotEmpty();
        assertThat(unanalysedUnderCapture)
            .as("registration -> the targets its source view reads that carried no statistics when"
                + " its refresh statements were issued, during a capture into a store whose"
                + " store_graph anchor a run-configuration capture had already written. None: the"
                + " cadence is decided on the register's state, and no target held a row")
            .isEmpty();
    }

    /**
     * The claim. Every registration meets the targets its own rule reads already analysed, so the
     * plans its statements get are the plans {@code RefreshPlanStatisticsTest} pins as the settled
     * store's.
     */
    @Test
    @DisplayName("every registration meets the targets it reads analysed")
    void everyRegistrationMeetsItsPrerequisiteTargetsAnalysed() {
        assertThat(unanalysedUnderSplitCadence)
            .as("registration -> the targets its source view reads that carried no statistics when"
                + " its refresh statements were issued, under the cadence that commits and analyses"
                + " each registration. None: that is what the cadence is for")
            .isEmpty();
    }

    /**
     * The control, and the reason the claim above is not a tautology. Under the cadence a capture
     * used before this, every prerequisite of every registration is unanalysed at the moment it is
     * read, because the one {@code ANALYZE} on that path runs after the whole pass. This is the
     * defect stated as a test rather than as a measurement, and it is what would silently return if
     * the new cadence were reverted to running on the caller's transaction.
     */
    @Test
    @DisplayName("the caller-transaction cadence reads every prerequisite unanalysed")
    void theCallerTransactionCadenceMeetsThemAllUnanalysed() {
        assertThat(dependentRegistrations)
            .as("registrations whose source view reads another registration's target. Non-empty, or"
                + " the fixture exercises no dependent rule and both claims here are vacuous")
            .isNotEmpty();
        assertThat(unanalysedUnderCallerCadence.keySet())
            .as("registrations that met a prerequisite target unanalysed under the cadence that runs"
                + " inside the caller's transaction, where nothing can be analysed until the pass"
                + " has ended. All of them: this is the defect, stated as a test")
            .containsExactlyInAnyOrderElementsOf(dependentRegistrations);
    }

    // ===== Helpers =====

    /**
     * Each registration's prerequisite <em>targets</em>: the tables filled by the registrations its
     * own source view reads. The rows are keyed by view on both sides, {@code depends_on} naming a
     * prerequisite's view rather than its target, so this resolves the second half through the
     * registry.
     */
    private static Map<String, Set<String>> prerequisiteTargets(DSLContext dsl) {
        var targetByView = new LinkedHashMap<String, String>();
        Materializations.registrations(dsl)
            .forEach(r -> targetByView.put(r.sourceViewName(), r.targetTableName()));
        var prerequisites = new TreeMap<String, Set<String>>();
        dsl.fetch("SELECT SOURCE_VIEW_NAME, DEPENDS_ON FROM META_MATERIALIZE_DEPENDENCY")
            .forEach(row -> prerequisites
                .computeIfAbsent(row.get(0, String.class), view -> new LinkedHashSet<>())
                .add(targetByView.get(row.get(1, String.class))));
        return prerequisites;
    }

    /**
     * The same map with the empty targets dropped, and with it any registration left asking about
     * nothing. Asked of the store as it stands, which is why the two legs above filter before their
     * cadences run and the capture leg filters afterwards: its store holds no row until the capture
     * it is observing has written one.
     */
    private static Map<String, Set<String>> populated(DSLContext dsl,
                                                      Map<String, Set<String>> prerequisites) {
        var kept = new TreeMap<String, Set<String>>();
        prerequisites.forEach((view, targets) -> {
            var holding = targets.stream().filter(target -> holdsRows(dsl, target))
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
            if (!holding.isEmpty()) {
                kept.put(view, holding);
            }
        });
        return kept;
    }

    /**
     * Whether the target holds any row, which decides whether it belongs in the population at all.
     * {@code ANALYZE} on an empty table records nothing, so an empty target reports unanalysed
     * forever and would read here as a defect it cannot be: there is no selectivity for the engine to
     * state, and the row count it plans against is live rather than gathered. Three registrations of
     * this fixture read a target the {@code @mutation} payload surface leaves empty, that surface
     * being held fixed while the rest scales, and they are the reason this filter is here rather
     * than a note about it.
     */
    private static boolean holdsRows(DSLContext dsl, String target) {
        return dsl.fetchCount(table(name(target.toUpperCase()))) > 0;
    }

    /**
     * Reads the statistics state at the moment each registration's statements go out, which is what
     * the started event marks: the class contract on {@code Materializations} is that a
     * registration's name precedes its statements, which is exactly the instant this measurement
     * needs and the reason no seam had to be added for it.
     */
    private static final class UnanalysedPrerequisites implements RefreshProgress {

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
            if (!(event instanceof Event.RegistrationStarted started)) {
                return;
            }
            String view = started.registration().sourceViewName();
            var unanalysed = new TreeSet<String>();
            for (String target : prerequisites.getOrDefault(view, Set.of())) {
                if (!StoreStatistics.analysed(dsl, target)) {
                    unanalysed.add(target);
                }
            }
            if (!unanalysed.isEmpty()) {
                observed.put(view, unanalysed);
            }
        }
    }
}
