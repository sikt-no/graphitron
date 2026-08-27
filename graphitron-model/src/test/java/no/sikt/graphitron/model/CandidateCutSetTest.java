package no.sikt.graphitron.model;

import no.sikt.graphitron.model.derive.Materializations;
import no.sikt.graphitron.model.test.CandidateCutSet;
import no.sikt.graphitron.model.test.FactStores;
import no.sikt.graphitron.model.test.UnregisteredRelation;
import org.jooq.DSLContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Nested;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static no.sikt.graphitron.model.Tables.META_MATERIALIZE;
import static no.sikt.graphitron.model.Tables.META_MATERIALIZE_DEPENDENCY;
import static no.sikt.graphitron.model.test.SeededStore.withSeededStore;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.table;

/**
 * Establishes {@link CandidateCutSet}'s mechanism before anything is measured on top of it: every
 * step of clear-delete-swap-populate is an existing routine, but nothing in the tree composes them
 * this way, and a candidate cut set priced through a mechanism that quietly loses a refresh edge
 * would be wrong on both axes with nothing failing.
 *
 * <p>Against the production register rather than a synthetic one, which is the opposite choice from
 * {@code MaterializationOrderTest} and made for the opposite reason. That class pins the walk
 * through shapes the shipped DDL does not contain, so it has to build them; this one asks whether a
 * real twelve-stage register survives being cut, and a two-registration fixture would not contain
 * the shape that fails. Nothing here names a registration, so arm B of a cut-set decision removing
 * one leaves these cases asserting the same claims about whatever register it leaves behind.
 */
class CandidateCutSetTest {

    /**
     * The failure the hand deletion this mechanism replaces would have produced silently, checked
     * on the register the store actually ships.
     *
     * <p>The rows that bite when a registration leaves are a <em>retained</em> registration
     * depending on the excluded one. While the excluded registration was live, the dependent's
     * stored definition named its target table and could not reach past it, so the only edges are
     * dependent to excluded and excluded to deeper. After the swap the dependent evaluates the
     * excluded rule live, so it now reads whatever that rule reads, and it must still refresh after
     * those. A hand edit that dropped both directions would leave the dependent with no prerequisite
     * at all: it would refresh in the first stage, against targets not yet refilled, and no
     * assertion anywhere would notice.
     *
     * <p>So the claim is the transitive one, and it is stated as a re-derivation rather than as a
     * shape: for the excluded registration's dependents and prerequisites <em>as the store held them
     * before the cut</em>, every dependent-to-prerequisite pair is an edge afterwards, and the
     * refresh order places it accordingly. Both sets are asserted non-empty first, because a
     * register with no such registration would pass every later assertion vacuously.
     */
    @Test
    @DisplayName("cutting a mid-chain registration re-derives the edges through its rule")
    void excludingAMidChainRegistrationRederivesItsTransitiveEdges() {
        withStore(dsl -> {
            var midChain = aMidChainRegistration(dsl);
            var dependents = dependentsOf(dsl, midChain);
            var prerequisites = prerequisitesOf(dsl, midChain);
            assertThat(dependents).as("registrations reading " + midChain + "'s target").isNotEmpty();
            assertThat(prerequisites).as("targets " + midChain + "'s own view reads").isNotEmpty();

            CandidateCutSet.realise(dsl, CandidateCutSet.excluding(dsl, midChain));

            var edges = dependencyRows(dsl);
            var lost = new TreeSet<String>();
            for (String dependent : dependents) {
                for (String prerequisite : prerequisites) {
                    if (!edges.contains(Map.entry(dependent, prerequisite))) {
                        lost.add(dependent + " -> " + prerequisite);
                    }
                }
            }
            assertThat(lost)
                .as("edges through " + midChain + "'s rule that the cut lost. Each is a dependent"
                    + " that now evaluates that rule live, so it reads what the rule reads and must"
                    + " refresh after it; a lost one refills against stale rows and fails nothing")
                .isEmpty();

            var position = positions(Materializations.refreshOrder(dsl).registrations());
            for (String dependent : dependents) {
                for (String prerequisite : prerequisites) {
                    assertThat(position.get(dependent))
                        .as(dependent + " refreshes after " + prerequisite + ", whose rows its view"
                            + " now reaches through " + midChain + "'s rule")
                        .isGreaterThan(position.get(prerequisite));
                }
            }
        });
    }

    @Test
    @DisplayName("the register afterwards is exactly the candidate")
    void theRegisterAfterwardsIsExactlyTheCandidate() {
        withStore(dsl -> {
            var candidate = CandidateCutSet.excluding(dsl, aMidChainRegistration(dsl));
            CandidateCutSet.realise(dsl, candidate);
            assertThat(Materializations.registrations(dsl))
                .as("Materializations.registrations reads meta_materialize, so this is what the"
                    + " refresh pass will run and what RefreshProgress will price")
                .containsExactlyInAnyOrderElementsOf(candidate);
        });
    }

    /**
     * The read axis, which is the half {@link UnregisteredRelation} already delivered and this
     * mechanism must not break: an excluded registration's canonical name resolves to a view over
     * its rule, and every retained one stays the table its readers meet.
     */
    @Test
    @DisplayName("excluded targets become views and retained targets stay tables")
    void theSwapReachesEveryExcludedTargetAndNoOthers() {
        withStore(dsl -> {
            var midChain = aMidChainRegistration(dsl);
            var excludedTarget = targetOf(dsl, midChain);
            CandidateCutSet.realise(dsl, CandidateCutSet.excluding(dsl, midChain));

            assertThat(kindOf(dsl, excludedTarget))
                .as(excludedTarget + " is where readers spell the name, so the cut has to reach it"
                    + " there rather than anywhere else")
                .isEqualTo("VIEW");
            assertThat(kindOf(dsl, excludedTarget + UnregisteredRelation.REGISTERED_SUFFIX))
                .as("the rows the registration had already materialized, kept rather than dropped")
                .isEqualTo("BASE TABLE");
            var notTables = Materializations.registrations(dsl).stream()
                .map(Materializations.Registration::targetTableName)
                .filter(target -> !"BASE TABLE".equals(kindOf(dsl, target)))
                .toList();
            assertThat(notTables).as("retained targets the cut turned into views").isEmpty();
        });
    }

    /**
     * The candidate that keeps everything, which is the identity of the mechanism and the control
     * the measured candidates are read against. Every step still runs: the edges are cleared and
     * re-derived, no census row is deleted and no swap installed. If the rewrite were not
     * byte-identical here, no figure taken through this harness could be attributed to the cut
     * rather than to the harness.
     */
    @Test
    @DisplayName("the whole register realised as a candidate leaves every edge as it was")
    void realisingTheWholeRegisterChangesNothing() {
        withStore(dsl -> {
            var before = dependencyRows(dsl);
            CandidateCutSet.realise(dsl, Set.copyOf(Materializations.registrations(dsl)));
            assertThat(dependencyRows(dsl))
                .as("populate rewrites the relation from the census it finds, so an uncut register"
                    + " has to come back with the edges it went in with")
                .isEqualTo(before);
        });
    }

    @Test
    @DisplayName("a realised candidate still refreshes")
    void aRealisedCandidateStillRefreshes() {
        withStore(dsl -> {
            CandidateCutSet.realise(dsl,
                CandidateCutSet.excluding(dsl, aMidChainRegistration(dsl)));
            assertThat(Materializations.refreshOrder(dsl).registrations())
                .as("every retained registration placed exactly once")
                .containsExactlyInAnyOrderElementsOf(Materializations.registrations(dsl));
            Materializations.refresh(dsl, "candidate-cut-set-test");
        });
    }

    /**
     * The three cases that refuse before touching anything, which is why they are the only ones in
     * this class that run on the funnel. A refusal is raised by the argument check at the top of
     * each entry point, so no register row, no relation kind and no edge has moved by the time the
     * exception leaves; the store is in its booted state and the next case may have it. Every case
     * above instead realises a candidate, which rewrites the register one way, and
     * {@link CandidateCutSet} says outright to expect one store per candidate.
     */
    @Nested
    @DisplayName("a candidate that is not a subset of this store's register")
    class RefusedCandidates {

        @Test
        @DisplayName("is refused rather than silently realised as something else")
        void aRegistrationThisStoreDoesNotHoldIsRefused() {
            withSeededStore(dsl -> assertThatThrownBy(() -> CandidateCutSet.realise(dsl,
                    Set.of(new Materializations.Registration("scratch_absent_live",
                        "scratch_absent"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("scratch_absent_live"));
        }

        @Test
        @DisplayName("cannot be built by excluding a name nothing registers")
        void excludingAnUnregisteredNameIsRefused() {
            withSeededStore(dsl -> assertThatThrownBy(
                    () -> CandidateCutSet.excluding(dsl, "scratch_absent_live"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("scratch_absent_live"));
        }

        @Test
        @DisplayName("cannot be built by keeping a name nothing registers")
        void keepingAnUnregisteredNameIsRefused() {
            withSeededStore(dsl -> assertThatThrownBy(
                    () -> CandidateCutSet.keepingOnly(dsl, Set.of("scratch_absent_live")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("scratch_absent_live"));
        }
    }

    // ===== Reading the register the store shipped =====

    /**
     * A registration with at least one dependent and at least one prerequisite: the shape the
     * transitive claim is about, and the only one where cutting can lose an edge. Chosen off the
     * register rather than named, so this class survives the register changing under it;
     * alphabetically least among the candidates, so a failure names the same registration twice
     * running.
     */
    private static String aMidChainRegistration(DSLContext dsl) {
        var edges = dependencyRows(dsl);
        return edges.stream()
            .map(Map.Entry::getValue)
            .filter(registration -> edges.stream()
                .anyMatch(edge -> edge.getKey().equals(registration)))
            .min(String::compareTo)
            .orElseThrow(() -> new IllegalStateException(
                "no registration in this store has both a dependent and a prerequisite, so the"
                    + " register is at most one stage deep and the transitive claim this class"
                    + " establishes cannot arise in it"));
    }

    private static Set<String> dependentsOf(DSLContext dsl, String sourceViewName) {
        return dependencyRows(dsl).stream()
            .filter(edge -> edge.getValue().equals(sourceViewName))
            .map(Map.Entry::getKey)
            .collect(Collectors.toCollection(TreeSet::new));
    }

    private static Set<String> prerequisitesOf(DSLContext dsl, String sourceViewName) {
        return dependencyRows(dsl).stream()
            .filter(edge -> edge.getKey().equals(sourceViewName))
            .map(Map.Entry::getValue)
            .collect(Collectors.toCollection(TreeSet::new));
    }

    private static Set<Map.Entry<String, String>> dependencyRows(DSLContext dsl) {
        return dsl.select(META_MATERIALIZE_DEPENDENCY.SOURCE_VIEW_NAME,
                META_MATERIALIZE_DEPENDENCY.DEPENDS_ON)
            .from(META_MATERIALIZE_DEPENDENCY)
            .orderBy(META_MATERIALIZE_DEPENDENCY.SOURCE_VIEW_NAME,
                META_MATERIALIZE_DEPENDENCY.DEPENDS_ON)
            .fetch()
            .stream()
            .map(row -> Map.entry(row.value1(), row.value2()))
            .collect(Collectors.toCollection(java.util.LinkedHashSet::new));
    }

    private static String targetOf(DSLContext dsl, String sourceViewName) {
        return dsl.select(META_MATERIALIZE.TARGET_TABLE_NAME)
            .from(META_MATERIALIZE)
            .where(META_MATERIALIZE.SOURCE_VIEW_NAME.eq(sourceViewName))
            .fetchOne(META_MATERIALIZE.TARGET_TABLE_NAME);
    }

    private static Map<String, Integer> positions(List<Materializations.Registration> order) {
        return java.util.stream.IntStream.range(0, order.size())
            .boxed()
            .collect(Collectors.toMap(index -> order.get(index).sourceViewName(), index -> index));
    }

    private static String kindOf(DSLContext dsl, String relationName) {
        return dsl.select(field(name("TABLE_TYPE"), String.class))
            .from(table(name("INFORMATION_SCHEMA", "TABLES")))
            .where(field(name("TABLE_SCHEMA"), String.class).eq("PUBLIC"))
            .and(field(name("TABLE_NAME"), String.class)
                .eq(relationName.toUpperCase(Locale.ROOT)))
            .fetchOne(0, String.class);
    }

    private static void withStore(Consumer<DSLContext> body) {
        try (var store = FactStores.inMemory()) {
            body.accept(store.dsl());
        }
    }
}
