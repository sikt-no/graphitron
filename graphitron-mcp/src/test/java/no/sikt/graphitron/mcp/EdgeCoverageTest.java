package no.sikt.graphitron.mcp;

import no.sikt.graphitron.rewrite.catalog.FieldClassification;
import no.sikt.graphitron.rewrite.catalog.TypeClassification;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Coverage pin: the live test that makes the edge switch's "drift guard" claim real rather
 * than a false invariant. {@link EdgeProducer}'s no-{@code default} switches force a compile-time
 * edge decision for every {@link FieldClassification} / {@link TypeClassification} permit; this test
 * additionally asserts each permit is declared, deliberately and visibly, in exactly one of the two
 * companion partition sets ({@code EDGE_BEARING_*} / {@code NO_EDGE_*}). Without it a reviewer could
 * not see that the no-edge arms were chosen on purpose, and a new permit could land in the switch's
 * no-edge bucket silently. The analogue of
 * {@code GeneratorCoverageTest.everyGraphitronFieldLeafHasAKnownDispatchStatus}.
 */
class EdgeCoverageTest {

    @Test
    void everyFieldClassificationPermitHasAKnownEdgeDecision() {
        assertPartition(
            sealedLeaves(FieldClassification.class),
            EdgeProducer.EDGE_BEARING_FIELDS, EdgeProducer.NO_EDGE_FIELDS,
            "FieldClassification");
    }

    @Test
    void everyTypeClassificationPermitHasAKnownEdgeDecision() {
        assertPartition(
            sealedLeaves(TypeClassification.class),
            EdgeProducer.EDGE_BEARING_TYPES, EdgeProducer.NO_EDGE_TYPES,
            "TypeClassification");
    }

    private static void assertPartition(
        Set<Class<?>> leaves, Set<? extends Class<?>> edgeBearing, Set<? extends Class<?>> noEdge, String root
    ) {
        var bearing = new HashSet<Class<?>>(edgeBearing);
        var none = new HashSet<Class<?>>(noEdge);

        var overlap = bearing.stream().filter(none::contains).map(Class::getSimpleName).sorted().toList();
        assertThat(overlap)
            .as("%s: a permit cannot be both edge-bearing and no-edge", root)
            .isEmpty();

        var union = new HashSet<Class<?>>(bearing);
        union.addAll(none);

        var missing = leaves.stream().filter(l -> !union.contains(l))
            .map(Class::getSimpleName).sorted().toList();
        assertThat(missing)
            .as("%s: every permit must be declared edge-bearing or no-edge in EdgeProducer", root)
            .isEmpty();

        var stale = union.stream().filter(c -> !leaves.contains(c))
            .map(Class::getSimpleName).sorted().toList();
        assertThat(stale)
            .as("%s: the partition sets must not name a class outside the sealed hierarchy", root)
            .isEmpty();
    }

    /** Recursive sealed-leaf walker ({@code getPermittedSubclasses} is shallow). */
    private static Set<Class<?>> sealedLeaves(Class<?> type) {
        var direct = type.getPermittedSubclasses();
        if (direct == null || direct.length == 0) return Set.of(type);
        return Arrays.stream(direct)
            .flatMap(p -> sealedLeaves(p).stream())
            .collect(Collectors.toSet());
    }
}
