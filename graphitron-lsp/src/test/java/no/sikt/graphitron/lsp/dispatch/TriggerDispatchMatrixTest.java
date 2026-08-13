package no.sikt.graphitron.lsp.dispatch;

import no.sikt.graphitron.lsp.parsing.Behavior;
import no.sikt.graphitron.lsp.parsing.Trigger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The dispatch matrix is a total function from ({@link Trigger} leaf × {@link LspSurface}) to a
 * {@link TriggerDispatch.Status}. This test is what makes it total: the trigger universe is
 * derived from {@link Class#getPermittedSubclasses()}, never listed here, so adding a trigger
 * without saying what every surface does with it fails the build.
 *
 * <p>That derivation is the entire point of sealing the vocabulary. A hand-written leaf list
 * would let a new trigger sit unaccounted for on five surfaces while this test stayed green,
 * which is the rotting-inventory problem moved from prose into a test file rather than fixed.
 */
class TriggerDispatchMatrixTest {

    /**
     * The canonical leaf derivation, the same recursive walk the generator-side coverage test
     * uses: {@link Class#getPermittedSubclasses()} is shallow and hands back nested sealed
     * interfaces rather than their records, so the families have to be flattened.
     */
    static Set<Class<?>> sealedLeaves(Class<?> type) {
        var direct = type.getPermittedSubclasses();
        if (direct == null || direct.length == 0) return Set.of(type);
        return Arrays.stream(direct)
            .flatMap(p -> sealedLeaves(p).stream())
            .collect(Collectors.toSet());
    }

    @Test
    @DisplayName("every trigger leaf is declared in the matrix, and the matrix names no stale leaf")
    void theMatrixCoversExactlyTheSealedLeafSet() {
        Set<Class<?>> leaves = sealedLeaves(Trigger.class);
        Set<Class<?>> declared = new HashSet<>(TriggerDispatch.declaredTriggers());

        Set<Class<?>> missing = new HashSet<>(leaves);
        missing.removeAll(declared);
        assertThat(simpleNames(missing))
            .as("every sealed Trigger leaf must carry a dispatch row saying what each surface "
                + "does with it; an undeclared leaf is a capability nobody has decided about")
            .isEmpty();

        Set<Class<?>> stale = new HashSet<>(declared);
        stale.removeAll(leaves);
        assertThat(simpleNames(stale))
            .as("the matrix may not name a class outside the Trigger sealed hierarchy")
            .isEmpty();
    }

    @Test
    @DisplayName("the three statuses partition every cell of the matrix")
    void everyCellHasExactlyOneStatus() {
        Set<Class<?>> leaves = sealedLeaves(Trigger.class);

        for (Class<?> leaf : leaves) {
            @SuppressWarnings("unchecked")
            var trigger = (Class<? extends Trigger>) leaf;
            for (LspSurface surface : LspSurface.values()) {
                var status = TriggerDispatch.statusOf(trigger, surface);
                assertThat(status)
                    .as("%s × %s must carry one of the three statuses", leaf.getSimpleName(), surface)
                    .isNotNull();
            }
        }

        // Cross-check the per-surface views against the per-cell reading: the two derive from the
        // same table by different routes, so a disagreement means the accessors have drifted.
        for (LspSurface surface : LspSurface.values()) {
            var answered = TriggerDispatch.answeredBy(surface);
            var gaps = TriggerDispatch.gapsOf(surface);

            Set<Class<? extends Trigger>> overlap = new HashSet<>(answered);
            overlap.retainAll(gaps);
            assertThat(overlap)
                .as("a trigger cannot be both answered and a gap on %s", surface)
                .isEmpty();

            long declined = leaves.stream()
                .filter(leaf -> {
                    @SuppressWarnings("unchecked")
                    var trigger = (Class<? extends Trigger>) leaf;
                    return TriggerDispatch.statusOf(trigger, surface)
                        == TriggerDispatch.Status.NO_ANSWER;
                })
                .count();

            assertThat(answered.size() + gaps.size() + declined)
                .as("answered + gaps + declined must partition every trigger on %s exactly", surface)
                .isEqualTo(leaves.size());
        }
    }

    /**
     * A family interface is not a leaf and carries no row. Passing one is the realistic slip,
     * since the families read like dispatch subjects, and it must fail loudly rather than report
     * a plausible {@code NO_ANSWER} for all six surfaces.
     */
    @Test
    @DisplayName("an undeclared trigger is rejected rather than silently declined")
    void statusOfRefusesAnUndeclaredTrigger() {
        assertThatThrownBy(
            () -> TriggerDispatch.statusOf(Trigger.CursorToken.class, LspSurface.HOVER))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("no dispatch row");
    }

    /**
     * The value bindings are the family the incumbent already dispatched on, so they are the part
     * of the matrix a port would get right by accident. Pinned as class literals rather than by
     * count, so renaming or deleting an arm fails compilation here instead of quietly shrinking
     * the assertion.
     */
    @Test
    @DisplayName("the value bindings are one family inside the hierarchy, not the whole of it")
    void theBehaviourFamilyIsAProperSubsetOfTheTriggerVocabulary() {
        Set<Class<?>> behaviourLeaves = sealedLeaves(Behavior.class);
        Set<Class<?>> allLeaves = sealedLeaves(Trigger.class);

        assertThat(behaviourLeaves)
            .as("the eight directive-argument value bindings")
            .containsExactlyInAnyOrder(
                Behavior.ClassNameBinding.class, Behavior.MethodNameBinding.class,
                Behavior.CatalogTableBinding.class, Behavior.CatalogColumnBinding.class,
                Behavior.CatalogFkBinding.class, Behavior.ArgMappingBinding.class,
                Behavior.ScalarTypeBinding.class, Behavior.NodeTypeBinding.class);

        assertThat(allLeaves).containsAll(behaviourLeaves);
        assertThat(allLeaves.size())
            .as("a matrix over Behavior alone would leave the name tokens and the document "
                + "sweeps ungated, which is the position declining a shadow-parity gate has to "
                + "avoid; the vocabulary is deliberately wider than the eight arms")
            .isGreaterThan(behaviourLeaves.size());
    }

    /**
     * The gaps are declared facts, not silently empty arms. This pins the ones the capability
     * inventory marks as returning nothing today, so closing one is a deliberate edit here and
     * losing one cannot pass unnoticed.
     */
    @Test
    @DisplayName("the known gaps are declared where the inventory marks them")
    void theDeclaredGapsAreTheInventorysEmptyArms() {
        assertThat(TriggerDispatch.gapsOf(LspSurface.HOVER))
            .as("argMapping and scalarType hover to nothing today")
            .containsExactlyInAnyOrder(
                Behavior.ArgMappingBinding.class, Behavior.ScalarTypeBinding.class);

        assertThat(TriggerDispatch.gapsOf(LspSurface.DEFINITION))
            .as("argMapping, scalarType and nodeType resolve to nothing today")
            .containsExactlyInAnyOrder(
                Behavior.ArgMappingBinding.class, Behavior.ScalarTypeBinding.class,
                Behavior.NodeTypeBinding.class);

        assertThat(TriggerDispatch.gapsOf(LspSurface.CODE_ACTION))
            .as("the SDL refactor registry is empty today")
            .containsExactly(Trigger.DocumentScan.SdlActionDetectors.class);

        assertThat(TriggerDispatch.gapsOf(LspSurface.COMPLETION))
            .as("completing a type name against the workspace is the case the projection era "
                + "cannot express, since one unparseable buffer invalidates the whole snapshot")
            .containsExactly(Trigger.CursorToken.SdlTypeReference.class);

        assertThat(TriggerDispatch.gapsOf(LspSurface.INLAY_HINT)).isEmpty();
        assertThat(TriggerDispatch.gapsOf(LspSurface.DIAGNOSTIC)).isEmpty();
    }

    /**
     * The document sweeps have no cursor, so no cursor-keyed surface may claim one, and the
     * cursor tokens are never answered by the pushed diagnostics channel. Getting this backwards
     * is how a sweep ends up re-derived per keystroke, which is the cost the incumbent pays.
     */
    @Test
    @DisplayName("cursor-keyed surfaces decline the document sweeps, and diagnostics decline the cursor tokens")
    void theCursorAndSweepAxesDoNotCross() {
        var cursorSurfaces = Set.of(LspSurface.COMPLETION, LspSurface.HOVER, LspSurface.DEFINITION);

        for (Class<?> sweep : sealedLeaves(Trigger.DocumentScan.class)) {
            @SuppressWarnings("unchecked")
            var trigger = (Class<? extends Trigger>) sweep;
            for (LspSurface surface : cursorSurfaces) {
                assertThat(TriggerDispatch.statusOf(trigger, surface))
                    .as("%s is a whole-document sweep, so %s must decline it",
                        sweep.getSimpleName(), surface)
                    .isEqualTo(TriggerDispatch.Status.NO_ANSWER);
            }
        }

        for (Class<?> token : sealedLeaves(Trigger.CursorToken.class)) {
            @SuppressWarnings("unchecked")
            var trigger = (Class<? extends Trigger>) token;
            assertThat(TriggerDispatch.statusOf(trigger, LspSurface.DIAGNOSTIC))
                .as("diagnostics are pushed with no cursor, so %s is carried by a sweep instead",
                    token.getSimpleName())
                .isEqualTo(TriggerDispatch.Status.NO_ANSWER);
        }
    }

    /** Every surface must do something, or it has no business being registered. */
    @Test
    @DisplayName("no registered surface is inert")
    void everySurfaceAnswersSomething() {
        Map<LspSurface, Integer> answered = new EnumMap<>(LspSurface.class);
        for (LspSurface surface : LspSurface.values()) {
            answered.put(surface, TriggerDispatch.answeredBy(surface).size());
        }
        assertThat(answered)
            .as("a surface answering no trigger at all would be a registered capability with "
                + "nothing behind it")
            .allSatisfy((surface, count) -> assertThat(count).isPositive());
    }

    private static Set<String> simpleNames(Set<Class<?>> classes) {
        return classes.stream().map(Class::getSimpleName).collect(Collectors.toSet());
    }
}
