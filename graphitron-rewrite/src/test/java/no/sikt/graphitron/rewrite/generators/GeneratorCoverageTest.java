package no.sikt.graphitron.rewrite.generators;

import no.sikt.graphitron.rewrite.model.ChildField;
import no.sikt.graphitron.rewrite.model.GraphitronField;
import no.sikt.graphitron.rewrite.model.InputField;
import no.sikt.graphitron.rewrite.model.MutationField;
import no.sikt.graphitron.rewrite.model.QueryField;
import no.sikt.graphitron.rewrite.model.RootField;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class GeneratorCoverageTest {

    /**
     * Recursive leaf walker — {@link Class#getPermittedSubclasses()} is shallow; it returns
     * {@code TableTargetField.class} (a nested sealed interface) rather than its eight concrete
     * implementations.
     */
    private static Set<Class<?>> sealedLeaves(Class<?> type) {
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
}
