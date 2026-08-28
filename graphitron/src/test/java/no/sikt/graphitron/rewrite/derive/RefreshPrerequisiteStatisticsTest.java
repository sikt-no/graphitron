package no.sikt.graphitron.rewrite.derive;

import no.sikt.graphitron.common.configuration.TestConfiguration;
import no.sikt.graphitron.model.derive.Materializations;
import no.sikt.graphitron.model.derive.RefreshProgress;
import no.sikt.graphitron.rewrite.CapturedStore;
import no.sikt.graphitron.rewrite.JooqCatalog;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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
    private static Set<String> dependentRegistrations;

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
        try (var store = CapturedStore.ofCatalog(tmp.resolve("prerequisites"),
                MaterializedRegistryFixture.scaledSdl(UNITS), jooq)) {
            DSLContext dsl = store.dsl();
            Map<String, Set<String>> prerequisites = prerequisiteTargets(dsl);
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
            .forEach(row -> {
                String target = targetByView.get(row.get(1, String.class));
                if (holdsRows(dsl, target)) {
                    prerequisites
                        .computeIfAbsent(row.get(0, String.class), view -> new LinkedHashSet<>())
                        .add(target);
                }
            });
        return prerequisites;
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
