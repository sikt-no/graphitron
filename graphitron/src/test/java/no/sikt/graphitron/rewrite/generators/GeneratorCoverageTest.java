package no.sikt.graphitron.rewrite.generators;

import no.sikt.graphitron.plan.ProjectionCommands;
import no.sikt.graphitron.rewrite.model.ChildField;
import no.sikt.graphitron.rewrite.model.GraphitronField;
import no.sikt.graphitron.rewrite.model.InputField;
import no.sikt.graphitron.rewrite.model.MutationField;
import no.sikt.graphitron.rewrite.model.QueryField;
import no.sikt.graphitron.rewrite.model.RootField;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;

@UnitTier
public class GeneratorCoverageTest {

    /**
     * Recursive leaf walker — {@link Class#getPermittedSubclasses()} is shallow; it returns
     * {@code TableTargetField.class} (a nested sealed interface) rather than its eight concrete
     * implementations.
     *
     * <p>Public so Phase 2's {@code VariantCoverageTest} in the parent package can reuse it.
     * Extraction to a shared {@code SealedLeafUtils} helper is deferred until a third caller arrives.
     */
    public static Set<Class<?>> sealedLeaves(Class<?> type) {
        var direct = type.getPermittedSubclasses();
        if (direct == null || direct.length == 0) return Set.of(type);
        return Arrays.stream(direct)
            .flatMap(p -> sealedLeaves(p).stream())
            .collect(Collectors.toSet());
    }

    @Test
    void notImplementedReasonsContainsOnlyConcreteSealedLeaves() {
        var roots = List.of(
            GraphitronField.class, RootField.class, QueryField.class,
            MutationField.class, ChildField.class, InputField.class);
        var leaves = roots.stream()
            .flatMap(r -> sealedLeaves(r).stream())
            .collect(Collectors.toSet());

        assertThat(TypeFetcherGenerator.STUBBED_VARIANTS.keySet())
            .as("every map key must be a concrete sealed leaf — no interfaces, "
                + "no classes outside the GraphitronField hierarchy")
            .allMatch(leaves::contains);
    }

    /**
     * Every sealed leaf of {@link GraphitronField} must land in exactly one of four buckets:
     * {@link TypeFetcherGenerator#IMPLEMENTED_LEAVES}, {@link TypeFetcherGenerator#STUBBED_VARIANTS}'s
     * key set, {@link TypeFetcherGenerator#NOT_DISPATCHED_LEAVES}, or the projected bucket
     * <em>derived</em> from the projection producer's own membership declaration
     * ({@link ProjectionCommands#CONTRIBUTION_MINTING_LEAVES} minus the dual-arm kinds that also
     * carry a fetcher arm). Deriving the bucket rather than restating it keeps the partition
     * sourced where the dispatch lives; the pipeline-tier census test binds the declaration to
     * the producer's observed minting in both directions, and the dual-arm intersection is pinned
     * here explicitly so a new leaf landing in both dispatches is a review signal, never a silent
     * subtraction.
     */
    @Test
    void everyGraphitronFieldLeafHasAKnownDispatchStatus() {
        Set<Class<?>> leaves = sealedLeaves(GraphitronField.class);
        Set<Class<?>> implemented = new HashSet<>(TypeFetcherGenerator.IMPLEMENTED_LEAVES);
        Set<Class<?>> stubbed = new HashSet<>(TypeFetcherGenerator.STUBBED_VARIANTS.keySet());
        Set<Class<?>> notDispatched = new HashSet<>(TypeFetcherGenerator.NOT_DISPATCHED_LEAVES);
        Set<Class<?>> minting = new HashSet<>(ProjectionCommands.CONTRIBUTION_MINTING_LEAVES);

        assertThat(simpleNames(intersection(minting, implemented)))
            .as("the dual-arm kinds: leaves that project (a contribution, a unit row of their own,"
                + " or ridden slot contributions) AND get a fetcher arm. Pinned exactly so a new"
                + " dual-arm leaf is a deliberate edit here, not a silent derivation change")
            .containsExactlyInAnyOrder("ColumnBackedField", "ComputedField", "BatchedPivotField");

        Set<Class<?>> projected = new HashSet<>(minting);
        projected.removeAll(implemented);

        assertThat(simpleNames(intersection(implemented, stubbed)))
            .as("IMPLEMENTED_LEAVES ∩ STUBBED_VARIANTS — a leaf cannot be both real and stubbed")
            .isEmpty();
        assertThat(simpleNames(intersection(implemented, notDispatched)))
            .as("IMPLEMENTED_LEAVES ∩ NOT_DISPATCHED_LEAVES — a dispatched leaf cannot also be filtered before dispatch")
            .isEmpty();
        assertThat(simpleNames(intersection(stubbed, notDispatched)))
            .as("STUBBED_VARIANTS ∩ NOT_DISPATCHED_LEAVES — a stubbed leaf must be reachable to be stubbed")
            .isEmpty();
        assertThat(simpleNames(intersection(stubbed, projected)))
            .as("STUBBED_VARIANTS ∩ projected — projection-only means no stub is needed")
            .isEmpty();
        assertThat(simpleNames(intersection(notDispatched, projected)))
            .as("NOT_DISPATCHED_LEAVES ∩ projected — a projected leaf must be dispatched to the projection path")
            .isEmpty();

        Set<Class<?>> union = new HashSet<>();
        union.addAll(implemented);
        union.addAll(stubbed);
        union.addAll(notDispatched);
        union.addAll(projected);

        Set<Class<?>> missing = new HashSet<>(leaves);
        missing.removeAll(union);
        assertThat(simpleNames(missing))
            .as("every GraphitronField leaf must be declared in exactly one of IMPLEMENTED_LEAVES, "
                + "STUBBED_VARIANTS.keySet(), NOT_DISPATCHED_LEAVES, or the producer-derived "
                + "projected bucket")
            .isEmpty();

        Set<Class<?>> stale = new HashSet<>(union);
        stale.removeAll(leaves);
        assertThat(simpleNames(stale))
            .as("none of the dispatch sets may name a class outside the GraphitronField sealed hierarchy")
            .isEmpty();
    }

    private static <T> Set<T> intersection(Set<T> a, Set<T> b) {
        return a.stream().filter(b::contains).collect(Collectors.toSet());
    }

    private static Set<String> simpleNames(Set<Class<?>> classes) {
        return classes.stream().map(Class::getSimpleName).collect(Collectors.toSet());
    }
}
