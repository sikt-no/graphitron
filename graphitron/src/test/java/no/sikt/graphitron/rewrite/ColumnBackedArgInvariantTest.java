package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.rewrite.model.CallSiteExtraction;
import no.sikt.graphitron.rewrite.model.ColumnRef;
import no.sikt.graphitron.rewrite.model.HelperRef;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Compact-constructor invariants of the merged column-backed argument carriers
 * {@link ArgumentRef.ScalarArg.ColumnBackedArg} and
 * {@link ArgumentRef.ScalarArg.ColumnBackedReferenceArg}: the columns list is non-empty (arity
 * floor), and a multi-column {@code ColumnBackedArg} always carries a
 * {@link CallSiteExtraction.NodeIdDecodeKeys} extraction (the reference carrier's slot is
 * narrowed to it at the type level, so only the floor is checked there). Argument-axis sibling
 * of {@code ColumnBackedFieldInvariantTest} / {@code InputColumnBackedFieldInvariantTest}.
 */
@UnitTier
class ColumnBackedArgInvariantTest {

    private static final ColumnRef ID_1 = new ColumnRef("id_1", "ID_1", "java.lang.Integer");
    private static final ColumnRef ID_2 = new ColumnRef("id_2", "ID_2", "java.lang.Integer");

    private static CallSiteExtraction.NodeIdDecodeKeys decode(List<ColumnRef> columns) {
        return new CallSiteExtraction.ThrowOnMismatch(
            new HelperRef.Decode(ClassName.get("fixture", "Enc"), "decodeBar", columns, "Bar"));
    }

    @Test
    void columnBackedArg_acceptsAnyArityUnderNodeIdDecodeKeys() {
        var single = new ArgumentRef.ScalarArg.ColumnBackedArg("id", "ID", true, false,
            List.of(ID_1), decode(List.of(ID_1)), Optional.empty(), false, true, List.of());
        var composite = new ArgumentRef.ScalarArg.ColumnBackedArg("id", "ID", true, false,
            List.of(ID_1, ID_2), decode(List.of(ID_1, ID_2)), Optional.empty(), false, true, List.of());
        assertThat(single.isComposite()).isFalse();
        assertThat(composite.isComposite()).isTrue();
    }

    @Test
    void columnBackedArg_rejectsEmptyColumns() {
        assertThatThrownBy(() -> new ArgumentRef.ScalarArg.ColumnBackedArg("id", "ID", true, false,
                List.of(), new CallSiteExtraction.Direct(), Optional.empty(), false, false, List.of()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("at least one column");
    }

    @Test
    void columnBackedArg_rejectsMultiColumnSingleScalarExtraction() {
        // Direct (and every other single-scalar extraction) implies arity 1.
        assertThatThrownBy(() -> new ArgumentRef.ScalarArg.ColumnBackedArg("id", "ID", true, false,
                List.of(ID_1, ID_2), new CallSiteExtraction.Direct(), Optional.empty(), false, false, List.of()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("NodeIdDecodeKeys");
    }

    @Test
    void columnBackedReferenceArg_acceptsAnyArity() {
        var single = new ArgumentRef.ScalarArg.ColumnBackedReferenceArg("ref", "ID", true, false,
            List.of(ID_1), List.of(), List.of(ID_1), decode(List.of(ID_1)), Optional.empty(), false);
        var composite = new ArgumentRef.ScalarArg.ColumnBackedReferenceArg("ref", "ID", true, false,
            List.of(ID_1, ID_2), List.of(), List.of(ID_1, ID_2), decode(List.of(ID_1, ID_2)),
            Optional.empty(), false);
        assertThat(single.isComposite()).isFalse();
        assertThat(composite.isComposite()).isTrue();
    }

    @Test
    void columnBackedReferenceArg_rejectsEmptyColumns() {
        assertThatThrownBy(() -> new ArgumentRef.ScalarArg.ColumnBackedReferenceArg("ref", "ID", true, false,
                List.of(), List.of(), List.of(), decode(List.of(ID_1)), Optional.empty(), false))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("at least one column");
    }
}
