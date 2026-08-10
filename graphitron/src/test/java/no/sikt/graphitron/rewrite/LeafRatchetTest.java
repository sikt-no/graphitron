package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.rewrite.generators.GeneratorCoverageTest;
import no.sikt.graphitron.rewrite.model.ChildField;
import no.sikt.graphitron.rewrite.model.InputField;
import no.sikt.graphitron.rewrite.model.MutationField;
import no.sikt.graphitron.rewrite.model.QueryField;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The leaf ratchet: the per-hierarchy sealed leaf counts, pinned at the measured current sizes
 * in {@link CommandSeamRatchetTest}'s style so the classifier's leaf model can only shrink as
 * operation-encoding distinctions dissolve onto the coordinate's
 * {@link no.sikt.graphitron.rewrite.model.OperationMember} rows. The counting rule is
 * {@link GeneratorCoverageTest#sealedLeaves} (the recursive walk through nested seals), the same
 * rule every other leaf-set obligation uses, so this number cannot be re-derived differently by
 * the next reader.
 *
 * <p><b>The pins move downward as dissolution slices land</b>, one slice at a time, each move
 * recorded as a history line on its constant in the same commit that folds the leaves. A pin
 * rises only for a distinction the reconstruction key names as surviving grain — source,
 * delivery or target — declared as a new triple in {@link LeafReconstructionKeyTest} in the
 * same commit; it never rises for an operation-encoding leaf, which the dissolution programme
 * exists to make unnecessary: add a fact or a member row instead. The acceptance for the
 * programme is that the reconstruction key {@code leaf = f(source, delivery, target)} holds
 * with no operation term.
 */
@UnitTier
class LeafRatchetTest {

    /**
     * Installed at 12 (census 2026-08-02, unchanged from the 2026-08-01 baseline).
     * 12 to 11 (2026-08-03): QueryLookupTableField folded onto QueryTableField plus the
     * lookup member; the leaf's information became the {@code LookupResolution} payload.
     * 11 to 10 (2026-08-03): QueryRoutineTableField folded onto QueryTableField plus the
     * {@code RoutineResolution} source axis; routine-sourced-ness became a source component
     * (the child side's joinPath precedent), closing the read-family dissolution.
     */
    private static final int QUERY_FIELD_LEAVES = 10;

    /**
     * Installed at 15 (census 2026-08-02, unchanged from the 2026-08-01 baseline).
     * 15 to 8 (2026-08-03): the DML verb split dissolved onto the write member family. The
     * four DmlTableField verb leaves folded into one direct-return record and the four
     * payload-verb leaves onto the two record carriers; the verb identity and per-verb input
     * surfaces became the carried {@code OperationMember.Write.Dml} payload.
     * 8 to 9 (2026-08-10): the routine carrier landed
     * ({@code MutationRoutineWriteRecordField}), a grain addition on both the source and
     * target terms — a bare routine call vs the sibling's routine chain, a payload record vs
     * its post-commit terminus table — with the operation unchanged (the same routine write,
     * the same {@code OperationMember.Write.RoutineWrite}). The count-preserving fold was
     * rejected because it would give one leaf two targets, making
     * {@code leaf = f(source, delivery, target)} untrue as a function.
     */
    private static final int MUTATION_FIELD_LEAVES = 9;

    /**
     * Installed at 24 (census 2026-08-02, unchanged from the 2026-08-01 baseline).
     * 24 to 22 (2026-08-03): LookupTableField and BatchedLookupTableField folded onto their
     * fetch siblings plus the lookup member, the first dissolution slice.
     */
    private static final int CHILD_FIELD_LEAVES = 22;

    /** Installed at 4 (census 2026-08-02, unchanged from the 2026-08-01 baseline). */
    private static final int INPUT_FIELD_LEAVES = 4;

    @Test
    void leafCountsHoldAtTheirPins() {
        assertThat(GeneratorCoverageTest.sealedLeaves(QueryField.class))
            .as("QueryField leaves; a drop means lowering the pin in the same commit with a "
                + "history line, a rise is a new operation-encoding leaf (mint a member instead)")
            .hasSize(QUERY_FIELD_LEAVES);
        assertThat(GeneratorCoverageTest.sealedLeaves(MutationField.class))
            .as("MutationField leaves; same rule as the query pin")
            .hasSize(MUTATION_FIELD_LEAVES);
        assertThat(GeneratorCoverageTest.sealedLeaves(ChildField.class))
            .as("ChildField leaves; same rule as the query pin")
            .hasSize(CHILD_FIELD_LEAVES);
        assertThat(GeneratorCoverageTest.sealedLeaves(InputField.class))
            .as("InputField leaves; same rule as the query pin")
            .hasSize(INPUT_FIELD_LEAVES);
    }
}
