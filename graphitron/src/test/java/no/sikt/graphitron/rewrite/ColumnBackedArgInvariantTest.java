package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.rewrite.model.CallSiteExtraction;
import no.sikt.graphitron.model.jooq.ColumnRef;
import no.sikt.graphitron.rewrite.model.FilterBinding;
import no.sikt.graphitron.rewrite.model.HelperRef;
import no.sikt.graphitron.rewrite.model.JoinStep;
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
 * narrowed to it at the type level, so only the floor is checked there). Both carriers also police
 * their {@link FilterBinding}: a {@link FilterBinding.Remote} binding needs a join path to reach the
 * terminal table through, and on {@code ColumnBackedArg} (whose two arms bind the same one column
 * slot) a {@link FilterBinding.Local} tuple must be the same arity as {@code columns}, so the
 * restated slot cannot drift from what it restates. Argument-axis sibling of
 * {@code ColumnBackedFieldInvariantTest} / {@code InputColumnBackedFieldInvariantTest}.
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
            List.of(ID_1), decode(List.of(ID_1)), Optional.empty(), false, true, List.of(),
            new FilterBinding.Local(List.of(ID_1)));
        var composite = new ArgumentRef.ScalarArg.ColumnBackedArg("id", "ID", true, false,
            List.of(ID_1, ID_2), decode(List.of(ID_1, ID_2)), Optional.empty(), false, true, List.of(),
            new FilterBinding.Local(List.of(ID_1, ID_2)));
        assertThat(single.isComposite()).isFalse();
        assertThat(composite.isComposite()).isTrue();
    }

    @Test
    void columnBackedArg_rejectsEmptyColumns() {
        assertThatThrownBy(() -> new ArgumentRef.ScalarArg.ColumnBackedArg("id", "ID", true, false,
                List.of(), new CallSiteExtraction.Direct(), Optional.empty(), false, false, List.of(),
                new FilterBinding.Local(List.of(ID_1))))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("at least one column");
    }

    @Test
    void columnBackedArg_rejectsMultiColumnSingleScalarExtraction() {
        // Direct (and every other single-scalar extraction) implies arity 1.
        assertThatThrownBy(() -> new ArgumentRef.ScalarArg.ColumnBackedArg("id", "ID", true, false,
                List.of(ID_1, ID_2), new CallSiteExtraction.Direct(), Optional.empty(), false, false,
                List.of(), new FilterBinding.Local(List.of(ID_1, ID_2))))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("NodeIdDecodeKeys");
    }

    @Test
    void columnBackedReferenceArg_acceptsAnyArity() {
        var single = new ArgumentRef.ScalarArg.ColumnBackedReferenceArg("ref", "ID", true, false,
            List.of(ID_1), List.of(), new FilterBinding.Local(List.of(ID_1)),
            decode(List.of(ID_1)), Optional.empty(), false);
        var composite = new ArgumentRef.ScalarArg.ColumnBackedReferenceArg("ref", "ID", true, false,
            List.of(ID_1, ID_2), List.of(), new FilterBinding.Local(List.of(ID_1, ID_2)),
            decode(List.of(ID_1, ID_2)), Optional.empty(), false);
        assertThat(single.isComposite()).isFalse();
        assertThat(composite.isComposite()).isTrue();
    }

    @Test
    void columnBackedReferenceArg_rejectsEmptyColumns() {
        assertThatThrownBy(() -> new ArgumentRef.ScalarArg.ColumnBackedReferenceArg("ref", "ID", true, false,
                List.of(), List.of(), new FilterBinding.Local(List.of(ID_1)),
                decode(List.of(ID_1)), Optional.empty(), false))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("at least one column");
    }

    @Test
    void referenceArg_rejectsRemoteBindingWithEmptyJoinPath() {
        // Remote means "the predicate binds columns() on the terminal table of joinPath". With no
        // path there is no terminal table, and BodyParam.RemoteColumnPredicate would reject the
        // empty path at emit; catching it on the carrier keeps the invalid state unconstructible.
        assertThatThrownBy(() -> new ArgumentRef.ScalarArg.ColumnBackedReferenceArg("ref", "ID", true, false,
                List.of(ID_1), List.of(), new FilterBinding.Remote(),
                decode(List.of(ID_1)), Optional.empty(), false))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("empty joinPath");
    }

    @Test
    void columnBackedArg_rejectsRemoteBindingWithEmptyJoinPath() {
        assertThatThrownBy(() -> new ArgumentRef.ScalarArg.ColumnBackedArg("name", "String", true, false,
                List.of(ID_1), new CallSiteExtraction.Direct(), Optional.empty(), false, false,
                List.of(), new FilterBinding.Remote()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("empty joinPath");
    }

    @Test
    void columnBackedArg_rejectsLocalTupleOfDifferentArityFromColumns() {
        // On this carrier the local tuple *is* columns(); the arm restates it so both families can
        // share one component, and the constructor is what keeps the restatement honest.
        assertThatThrownBy(() -> new ArgumentRef.ScalarArg.ColumnBackedArg("id", "ID", true, false,
                List.of(ID_1, ID_2), decode(List.of(ID_1, ID_2)), Optional.empty(), false, true,
                List.of(), new FilterBinding.Local(List.of(ID_1))))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("the local tuple is columns()");
    }

    @Test
    void localBinding_rejectsEmptyTuple() {
        assertThatThrownBy(() -> new FilterBinding.Local(List.of()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("is Remote");
    }
}
