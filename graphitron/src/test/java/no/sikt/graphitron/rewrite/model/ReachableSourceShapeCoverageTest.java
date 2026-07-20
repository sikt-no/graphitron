package no.sikt.graphitron.rewrite.model;

import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static no.sikt.graphitron.rewrite.model.ReachableSourceShape.CLASS_BACKED_ACCESSOR;
import static no.sikt.graphitron.rewrite.model.ReachableSourceShape.JOOQ_RECORD_CARRIER;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Coverage meta-test partitioning every realizable {@link ReachableSourceShape} combination into
 * supported-by-dispatch ({@link ReachableSourceShape#SINGLE_ARM} ∪ {@link ReachableSourceShape#DISPATCHED})
 * or rejected-by-validator ({@link ReachableSourceShape#REJECTED}), and failing on any realizable
 * combination in neither. No new {@code GraphitronField} leaf is introduced by the mixed-source seam, so
 * {@code GeneratorCoverageTest.everyGraphitronFieldLeafHasAKnownDispatchStatus} does not cover it; this
 * test guards the orthogonal shape-set dimension so a future third source shape cannot silently fall
 * through the emitter (a realizable combination it introduces would land in no bucket and fail here).
 */
@UnitTier
class ReachableSourceShapeCoverageTest {

    /**
     * The result-axis shapes: a type carries at most one producer-backed result classification, so no
     * realizable combination contains more than one of these. A combination with two would be
     * unrealizable and must not be declared.
     */
    private static final Set<ReachableSourceShape> RESULT_AXIS =
        Set.of(CLASS_BACKED_ACCESSOR, JOOQ_RECORD_CARRIER);

    @Test
    void everyRealizableShapeSetIsPartitionedIntoSupportedOrRejected() {
        var singleArm = ReachableSourceShape.SINGLE_ARM;
        var dispatched = ReachableSourceShape.DISPATCHED;
        var rejected = ReachableSourceShape.REJECTED;

        assertThat(intersection(singleArm, dispatched))
            .as("SINGLE_ARM ∩ DISPATCHED — a combination cannot be both single-arm and dispatched")
            .isEmpty();
        assertThat(intersection(singleArm, rejected))
            .as("SINGLE_ARM ∩ REJECTED — a supported combination cannot also be rejected")
            .isEmpty();
        assertThat(intersection(dispatched, rejected))
            .as("DISPATCHED ∩ REJECTED — a dispatched combination cannot also be rejected")
            .isEmpty();

        var declared = new HashSet<Set<ReachableSourceShape>>();
        declared.addAll(singleArm);
        declared.addAll(dispatched);
        declared.addAll(rejected);

        var realizable = realizableShapeSets();

        assertThat(difference(realizable, declared))
            .as("realizable shape-set combinations declared in neither the supported buckets "
                + "(SINGLE_ARM / DISPATCHED) nor REJECTED — a new source shape must be explicitly "
                + "partitioned so it cannot silently fall through the emitter")
            .isEmpty();
        assertThat(difference(declared, realizable))
            .as("a declared partition bucket names an unrealizable or empty shape-set combination")
            .isEmpty();
    }

    /** Non-empty subsets of the shape universe carrying at most one result-axis shape. */
    private static Set<Set<ReachableSourceShape>> realizableShapeSets() {
        List<ReachableSourceShape> all = List.of(ReachableSourceShape.values());
        var out = new HashSet<Set<ReachableSourceShape>>();
        int n = all.size();
        for (int mask = 1; mask < (1 << n); mask++) {
            var subset = EnumSet.noneOf(ReachableSourceShape.class);
            for (int i = 0; i < n; i++) {
                if ((mask & (1 << i)) != 0) {
                    subset.add(all.get(i));
                }
            }
            long resultAxisCount = subset.stream().filter(RESULT_AXIS::contains).count();
            if (resultAxisCount <= 1) {
                out.add(Set.copyOf(subset));
            }
        }
        return out;
    }

    private static Set<Set<ReachableSourceShape>> intersection(
            Set<Set<ReachableSourceShape>> a, Set<Set<ReachableSourceShape>> b) {
        return a.stream().filter(b::contains).collect(Collectors.toSet());
    }

    private static Set<Set<ReachableSourceShape>> difference(
            Set<Set<ReachableSourceShape>> a, Set<Set<ReachableSourceShape>> b) {
        return a.stream().filter(s -> !b.contains(s)).collect(Collectors.toSet());
    }
}
