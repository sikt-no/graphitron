package no.sikt.graphitron.rewrite.model;

import graphql.language.SourceLocation;
import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Compact-constructor invariants of the merged column-backed input carriers
 * {@link InputField.ColumnBackedField} and {@link InputField.ColumnBackedReferenceField}: the
 * columns list is non-empty (arity floor), and a multi-column carrier always carries a
 * {@link CallSiteExtraction.NodeIdDecodeKeys} extraction (no plain multi-column input shape
 * exists; the single-scalar extraction shapes imply arity 1). Input-axis sibling of
 * {@link ColumnBackedFieldInvariantTest}; pins the checked form of what the retired composite
 * input leaf pair used to state at the type level.
 */
@UnitTier
class InputColumnBackedFieldInvariantTest {

    private static final ColumnRef ID_1 = new ColumnRef("id_1", "ID_1", "java.lang.Integer");
    private static final ColumnRef ID_2 = new ColumnRef("id_2", "ID_2", "java.lang.Integer");
    private static final SourceLocation LOC = new SourceLocation(1, 1);

    private static CallSiteExtraction.NodeIdDecodeKeys decode(List<ColumnRef> columns) {
        return new CallSiteExtraction.ThrowOnMismatch(
            new HelperRef.Decode(ClassName.get("fixture", "Enc"), "decodeBar", columns, "Bar"));
    }

    @Test
    void inputColumnBackedField_acceptsAnyArityUnderNodeIdDecodeKeys() {
        var single = new InputField.ColumnBackedField("In", "id", LOC, "ID", true, false,
            List.of(ID_1), Optional.empty(), decode(List.of(ID_1)));
        var composite = new InputField.ColumnBackedField("In", "id", LOC, "ID", true, false,
            List.of(ID_1, ID_2), Optional.empty(), decode(List.of(ID_1, ID_2)));
        assertThat(single.isComposite()).isFalse();
        assertThat(composite.isComposite()).isTrue();
    }

    @Test
    void inputColumnBackedField_rejectsEmptyColumns() {
        assertThatThrownBy(() -> new InputField.ColumnBackedField("In", "id", LOC, "ID", true, false,
                List.of(), Optional.empty(), new CallSiteExtraction.Direct()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("at least one column");
    }

    @Test
    void inputColumnBackedField_rejectsMultiColumnSingleScalarExtraction() {
        // Direct (and every other single-scalar extraction) implies arity 1.
        assertThatThrownBy(() -> new InputField.ColumnBackedField("In", "id", LOC, "ID", true, false,
                List.of(ID_1, ID_2), Optional.empty(), new CallSiteExtraction.Direct()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("NodeIdDecodeKeys");
    }

    @Test
    void inputColumnBackedReferenceField_acceptsAnyArityUnderNodeIdDecodeKeys() {
        var single = new InputField.ColumnBackedReferenceField("In", "ref", LOC, "ID", true, false,
            List.of(ID_1), List.of(), List.of(ID_1), false, Optional.empty(), decode(List.of(ID_1)));
        var composite = new InputField.ColumnBackedReferenceField("In", "ref", LOC, "ID", true, false,
            List.of(ID_1, ID_2), List.of(), List.of(ID_1, ID_2), false, Optional.empty(),
            decode(List.of(ID_1, ID_2)));
        assertThat(single.isComposite()).isFalse();
        assertThat(composite.isComposite()).isTrue();
    }

    @Test
    void inputColumnBackedReferenceField_rejectsEmptyColumns() {
        assertThatThrownBy(() -> new InputField.ColumnBackedReferenceField("In", "ref", LOC, "ID", true, false,
                List.of(), List.of(), List.of(), false, Optional.empty(), new CallSiteExtraction.Direct()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("at least one column");
    }

    @Test
    void inputColumnBackedReferenceField_rejectsMultiColumnSingleScalarExtraction() {
        assertThatThrownBy(() -> new InputField.ColumnBackedReferenceField("In", "ref", LOC, "ID", true, false,
                List.of(ID_1, ID_2), List.of(), List.of(ID_1, ID_2), false, Optional.empty(),
                new CallSiteExtraction.Direct()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("NodeIdDecodeKeys");
    }
}
