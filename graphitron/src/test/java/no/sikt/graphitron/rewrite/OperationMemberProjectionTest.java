package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.rewrite.generators.GeneratorCoverageTest;
import no.sikt.graphitron.rewrite.model.OperationMember;
import no.sikt.graphitron.rewrite.model.OperationMember.Kind;
import no.sikt.graphitron.rewrite.model.OperationMembers;
import no.sikt.graphitron.rewrite.model.OutputField;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The declaration fence around the leaf-to-member crosswalk: every sealed {@link OutputField}
 * leaf carries exactly one {@link OperationMembers#DECLARED_SHAPES} entry (no leaf classifies
 * without declaring its member shape, no entry outlives its leaf), the entries are internally
 * coherent, and the modeled-but-unpopulated member kinds stay declared rather than accidental.
 *
 * <p>Population agreement lives elsewhere: {@link OperationMemberMintPinTest} holds the minted
 * trigger-fact production equal to the leaf projection per coordinate over the corpus (with
 * per-kind non-vacuity floors), and the summary column this test once mirrored is retired; the
 * corpus asserts the member rows directly ({@code @classified(operations:)}, the arm-token
 * multiset), each retirement's equality to its successor demonstrated by a bridge pin that
 * shipped and retired with the retiring surface.
 */
@PipelineTier
class OperationMemberProjectionTest {

    @Test
    void declaredShapesCoverExactlyTheSealedLeaves() {
        Set<Class<?>> leaves = GeneratorCoverageTest.sealedLeaves(OutputField.class);
        assertThat(OperationMembers.DECLARED_SHAPES.keySet())
            .as("every sealed OutputField leaf declares its member shape, and no entry outlives its leaf")
            .containsExactlyInAnyOrderElementsOf(leaves.stream()
                .map(c -> {
                    @SuppressWarnings("unchecked")
                    Class<? extends OutputField> cast = (Class<? extends OutputField>) c;
                    return cast;
                })
                .collect(Collectors.toSet()));
    }

    /** The modeled-but-unpopulated member kinds, kept distinct from a silently empty vocabulary. */
    @Test
    void unpopulatedKindsAreDeclaredNotAccidental() {
        // These kinds exist in the vocabulary with no producing leaf: the protocol and
        // connection members whose coordinate home is still open, and the condition-matched
        // writes. Their arms construct fine; only production is absent.
        assertThat(new OperationMember.EntityResolve().kind()).isEqualTo(Kind.ENTITY_RESOLVE);
        assertThat(new OperationMember.Count().kind()).isEqualTo(Kind.COUNT);
        assertThat(new OperationMember.Facet().kind()).isEqualTo(Kind.FACET);
        assertThat(new OperationMember.Write.UpdateMatching().kind()).isEqualTo(Kind.WRITE);
        assertThat(new OperationMember.Write.DeleteMatching().kind()).isEqualTo(Kind.WRITE);
    }

    /** {@link OperationMembers#DECLARED_SHAPES} values are internally coherent (disjoint sets enforced at construction). */
    @Test
    void declaredShapesAreWellFormed() {
        for (Map.Entry<Class<? extends OutputField>, OperationMembers.DeclaredShape> e
                : OperationMembers.DECLARED_SHAPES.entrySet()) {
            var overlap = e.getValue().required().stream()
                .filter(e.getValue().optional()::contains)
                .toList();
            assertThat(overlap)
                .as("required/optional overlap of %s", e.getKey().getSimpleName())
                .isEmpty();
        }
    }
}
