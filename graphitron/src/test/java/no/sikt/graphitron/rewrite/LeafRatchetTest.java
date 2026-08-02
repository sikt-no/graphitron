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
 * <p><b>These pins move only downward</b>, one dissolution slice at a time, each move recorded
 * as a history line on its constant in the same commit that folds the leaves. A rise is a new
 * operation-encoding leaf, which the dissolution programme exists to make unnecessary: add a
 * fact or a member row instead. Surviving leaf distinctions are source, delivery and target
 * grain; the acceptance for the programme is that the reconstruction key
 * {@code leaf = f(source, delivery, target)} holds with no operation term.
 */
@UnitTier
class LeafRatchetTest {

    /** Installed at 12 (census 2026-08-02, unchanged from the 2026-08-01 baseline). */
    private static final int QUERY_FIELD_LEAVES = 12;

    /** Installed at 15 (census 2026-08-02, unchanged from the 2026-08-01 baseline). */
    private static final int MUTATION_FIELD_LEAVES = 15;

    /** Installed at 24 (census 2026-08-02, unchanged from the 2026-08-01 baseline). */
    private static final int CHILD_FIELD_LEAVES = 24;

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
