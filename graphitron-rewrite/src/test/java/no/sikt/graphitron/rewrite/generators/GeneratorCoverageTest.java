package no.sikt.graphitron.rewrite.generators;

import no.sikt.graphitron.rewrite.model.ChildField;
import no.sikt.graphitron.rewrite.model.GraphitronField;
import no.sikt.graphitron.rewrite.model.RootField;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.stream.Stream;

import static java.util.stream.Collectors.toSet;
import static org.assertj.core.api.Assertions.assertThat;

class GeneratorCoverageTest {

    /**
     * Recursively collects all concrete (non-sealed) leaf classes in a sealed hierarchy.
     * Stops recursing when a class has no permitted subclasses (i.e. it is a leaf record).
     */
    private static Set<Class<?>> leafPermits(Class<?> sealedClass) {
        var permits = sealedClass.getPermittedSubclasses();
        if (permits == null || permits.length == 0) return Set.of(sealedClass);
        var result = new HashSet<Class<?>>();
        for (var permit : permits) {
            result.addAll(leafPermits(permit));
        }
        return result;
    }

    @Test
    void everyChildAndRootFieldLeafIsHandledByTypeFetcherGenerator() {
        var permits = Stream.of(ChildField.class, RootField.class)
            .flatMap(c -> leafPermits(c).stream())
            .collect(toSet());

        var handled = Stream.concat(
            TypeFetcherGenerator.IMPLEMENTED_VARIANTS.stream(),
            TypeFetcherGenerator.UNIMPLEMENTED_VARIANTS.stream()
        ).collect(toSet());

        assertThat(permits)
            .as("every ChildField/RootField leaf must appear in IMPLEMENTED_VARIANTS or UNIMPLEMENTED_VARIANTS")
            .isSubsetOf(handled);
    }

    @Test
    void implementedAndUnimplementedVariantsAreDisjoint() {
        assertThat(TypeFetcherGenerator.IMPLEMENTED_VARIANTS)
            .as("a variant cannot be both implemented and unimplemented")
            .doesNotContainAnyElementsOf(TypeFetcherGenerator.UNIMPLEMENTED_VARIANTS);
    }

    @Test
    void allHandledVariantsAreActualLeafTypes() {
        var allLeaves = Stream.of(GraphitronField.class)
            .flatMap(c -> leafPermits(c).stream())
            .collect(toSet());

        Stream.concat(
            TypeFetcherGenerator.IMPLEMENTED_VARIANTS.stream(),
            TypeFetcherGenerator.UNIMPLEMENTED_VARIANTS.stream()
        ).forEach(v ->
            assertThat(allLeaves)
                .as("variant %s registered in generator sets should be a leaf in the GraphitronField hierarchy", v.getSimpleName())
                .contains(v)
        );
    }
}
