package no.sikt.graphitron.rewrite.model;

import graphql.language.SourceLocation;
import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.rewrite.TestFixtures;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Compact-constructor invariants of the merged column-backed output carriers
 * {@link ChildField.ColumnBackedField} and {@link ChildField.ColumnBackedReferenceField}:
 * the columns list is non-empty (arity floor), a multi-column carrier always carries
 * {@link CallSiteCompaction.NodeIdEncodeKeys} (no plain composite projection exists), and by
 * corollary every {@link CallSiteCompaction.Direct} instance is single-column. The composite
 * narrowing is a deferred-generalization seam documented at the constructors; these tests pin
 * the checked form of what the retired composite leaf pair used to state at the type level.
 */
@UnitTier
class ColumnBackedFieldInvariantTest {

    private static final ColumnRef ID_1 = new ColumnRef("id_1", "ID_1", "java.lang.Integer");
    private static final ColumnRef ID_2 = new ColumnRef("id_2", "ID_2", "java.lang.Integer");
    private static final SourceLocation LOC = new SourceLocation(1, 1, "schema.graphqls");
    private static final CallSiteCompaction.NodeIdEncodeKeys ENCODE =
        new CallSiteCompaction.NodeIdEncodeKeys(new HelperRef.Encode(
            ClassName.bestGuess("com.example.NodeIds"), "encodeBar", List.of(ID_1, ID_2)));

    private static List<JoinStep> fkPath() {
        return List.of(TestFixtures.fkJoin(
            TestFixtures.foreignKeyRef("bar_parent_fkey"), null, List.of(),
            TestFixtures.joinTarget("bar"), List.of(), null, ""));
    }

    // ===== ColumnBackedField =====

    @Test
    void columnBackedField_acceptsSingleColumnDirect() {
        assertThatCode(() -> new ChildField.ColumnBackedField(
                "Bar", "id", LOC, List.of(ID_1), new CallSiteCompaction.Direct()))
            .doesNotThrowAnyException();
    }

    @Test
    void columnBackedField_acceptsAnyArityUnderNodeIdEncodeKeys() {
        var single = new ChildField.ColumnBackedField("Bar", "id", LOC, List.of(ID_1), ENCODE);
        var composite = new ChildField.ColumnBackedField("Bar", "id", LOC, List.of(ID_1, ID_2), ENCODE);
        assertThat(single.isComposite()).isFalse();
        assertThat(composite.isComposite()).isTrue();
    }

    @Test
    void columnBackedField_rejectsEmptyColumns() {
        assertThatThrownBy(() -> new ChildField.ColumnBackedField(
                "Bar", "id", LOC, List.of(), new CallSiteCompaction.Direct()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("at least one column");
    }

    @Test
    void columnBackedField_rejectsMultiColumnDirect() {
        // Direct implies arity 1: no plain composite projection exists today.
        assertThatThrownBy(() -> new ChildField.ColumnBackedField(
                "Bar", "id", LOC, List.of(ID_1, ID_2), new CallSiteCompaction.Direct()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("NodeIdEncodeKeys");
    }

    @Test
    void columnBackedField_copiesColumnsDefensively() {
        var mutable = new java.util.ArrayList<>(List.of(ID_1));
        var field = new ChildField.ColumnBackedField(
            "Bar", "id", LOC, mutable, new CallSiteCompaction.Direct());
        mutable.add(ID_2);
        assertThat(field.columns()).containsExactly(ID_1);
    }

    // ===== ColumnBackedReferenceField =====

    @Test
    void columnBackedReferenceField_acceptsAnyArityUnderNodeIdEncodeKeys() {
        var path = fkPath();
        var pc = TestFixtures.pcFor(path, TestFixtures.filmTable());
        var single = new ChildField.ColumnBackedReferenceField(
            "Child", "parentId", LOC, List.of(ID_1), path, ENCODE, pc);
        var composite = new ChildField.ColumnBackedReferenceField(
            "Child", "parentId", LOC, List.of(ID_1, ID_2), path, ENCODE, pc);
        assertThat(single.isComposite()).isFalse();
        assertThat(composite.isComposite()).isTrue();
    }

    @Test
    void columnBackedReferenceField_rejectsEmptyColumns() {
        var path = fkPath();
        assertThatThrownBy(() -> new ChildField.ColumnBackedReferenceField(
                "Child", "parentId", LOC, List.of(), path, ENCODE,
                TestFixtures.pcFor(path, TestFixtures.filmTable())))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("at least one column");
    }

    @Test
    void columnBackedReferenceField_rejectsMultiColumnDirect() {
        var path = fkPath();
        assertThatThrownBy(() -> new ChildField.ColumnBackedReferenceField(
                "Child", "parentId", LOC, List.of(ID_1, ID_2), path,
                new CallSiteCompaction.Direct(),
                TestFixtures.pcFor(path, TestFixtures.filmTable())))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("NodeIdEncodeKeys");
    }

    @Test
    void columnBackedReferenceField_runsParentCorrelationInvariantUnconditionally() {
        // A non-empty joinPath with a null parentCorrelation trips
        // ParentCorrelation.checkCarrierInvariant for every arity, composite included —
        // the merged construction site derives the correlation arity-independently.
        assertThatThrownBy(() -> new ChildField.ColumnBackedReferenceField(
                "Child", "parentId", LOC, List.of(ID_1, ID_2), fkPath(), ENCODE, null))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("parentCorrelation must not be null");
    }
}
