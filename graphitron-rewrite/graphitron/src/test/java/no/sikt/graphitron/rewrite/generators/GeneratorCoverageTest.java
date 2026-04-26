package no.sikt.graphitron.rewrite.generators;

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

        assertThat(TypeFetcherGenerator.NOT_IMPLEMENTED_REASONS.keySet())
            .as("every map key must be a concrete sealed leaf — no interfaces, "
                + "no classes outside the GraphitronField hierarchy")
            .allMatch(leaves::contains);
    }

    /**
     * Every sealed leaf of {@link GraphitronField} must land in exactly one of four sets:
     * {@link TypeFetcherGenerator#IMPLEMENTED_LEAVES}, {@link TypeFetcherGenerator#NOT_IMPLEMENTED_REASONS}'s
     * key set, {@link TypeFetcherGenerator#NOT_DISPATCHED_LEAVES}, or
     * {@link TypeFetcherGenerator#PROJECTED_LEAVES}. Guarantees that adding a new leaf without
     * updating any of the four sets fails the build, and that a leaf can't silently live in two
     * sets (which would let a stub entry linger after the real arm is added).
     */
    @Test
    void everyGraphitronFieldLeafHasAKnownDispatchStatus() {
        Set<Class<?>> leaves = sealedLeaves(GraphitronField.class);
        Set<Class<?>> implemented = new HashSet<>(TypeFetcherGenerator.IMPLEMENTED_LEAVES);
        Set<Class<?>> stubbed = new HashSet<>(TypeFetcherGenerator.NOT_IMPLEMENTED_REASONS.keySet());
        Set<Class<?>> notDispatched = new HashSet<>(TypeFetcherGenerator.NOT_DISPATCHED_LEAVES);
        Set<Class<?>> projected = new HashSet<>(TypeFetcherGenerator.PROJECTED_LEAVES);

        assertThat(simpleNames(intersection(implemented, stubbed)))
            .as("IMPLEMENTED_LEAVES ∩ NOT_IMPLEMENTED_REASONS — a leaf cannot be both real and stubbed")
            .isEmpty();
        assertThat(simpleNames(intersection(implemented, notDispatched)))
            .as("IMPLEMENTED_LEAVES ∩ NOT_DISPATCHED_LEAVES — a dispatched leaf cannot also be filtered before dispatch")
            .isEmpty();
        assertThat(simpleNames(intersection(implemented, projected)))
            .as("IMPLEMENTED_LEAVES ∩ PROJECTED_LEAVES — a leaf cannot be both fetcher-implemented and projection-only")
            .isEmpty();
        assertThat(simpleNames(intersection(stubbed, notDispatched)))
            .as("NOT_IMPLEMENTED_REASONS ∩ NOT_DISPATCHED_LEAVES — a stubbed leaf must be reachable to be stubbed")
            .isEmpty();
        assertThat(simpleNames(intersection(stubbed, projected)))
            .as("NOT_IMPLEMENTED_REASONS ∩ PROJECTED_LEAVES — projection-only means no stub is needed")
            .isEmpty();
        assertThat(simpleNames(intersection(notDispatched, projected)))
            .as("NOT_DISPATCHED_LEAVES ∩ PROJECTED_LEAVES — a projected leaf must be dispatched to the projection path")
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
                + "NOT_IMPLEMENTED_REASONS.keySet(), NOT_DISPATCHED_LEAVES, or PROJECTED_LEAVES")
            .isEmpty();

        Set<Class<?>> stale = new HashSet<>(union);
        stale.removeAll(leaves);
        assertThat(simpleNames(stale))
            .as("none of IMPLEMENTED_LEAVES / NOT_IMPLEMENTED_REASONS / NOT_DISPATCHED_LEAVES / "
                + "PROJECTED_LEAVES may name a class outside the GraphitronField sealed hierarchy")
            .isEmpty();
    }

    private static <T> Set<T> intersection(Set<T> a, Set<T> b) {
        return a.stream().filter(b::contains).collect(Collectors.toSet());
    }

    private static Set<String> simpleNames(Set<Class<?>> classes) {
        return classes.stream().map(Class::getSimpleName).collect(Collectors.toSet());
    }
}
