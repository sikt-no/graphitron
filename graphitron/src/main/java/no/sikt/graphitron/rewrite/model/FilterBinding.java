package no.sikt.graphitron.rewrite.model;

import java.util.List;
import no.sikt.graphitron.model.jooq.ColumnRef;
import no.sikt.graphitron.model.diagnostics.Rejection;

/**
 * Which table a filter carrier's value predicate binds against: the field's own table, or the
 * terminal table of the carrier's join path.
 *
 * <p>The axis is named outright rather than inferred. Before this component the same question was
 * answered three different implicit ways: an empty-{@code joinPath} sentinel on
 * {@link no.sikt.graphitron.rewrite.ArgumentRef.ScalarArg.ColumnBackedArg}, an extraction-type test
 * ({@code Direct} vs {@code NodeIdDecodeKeys}) on the reference carriers, and a column slot whose
 * referent depended on which of those two cases produced it. The extraction test encoded
 * "{@code @nodeId} implies local", which a translated FK-target {@code @nodeId} falsifies: its
 * decoded key is the target's key column, and the field's own table holds no column carrying that
 * value, so the predicate can only be written by visiting the target table.
 *
 * <p>Consumers switch exhaustively over the two arms, so a third binding shape breaks every reader
 * at compile time rather than silently taking a wrong branch.
 *
 * <p>{@code joinPath} on the carrying record stays orthogonal to this component and is deliberately
 * <em>not</em> folded into {@link Remote}: a {@link Local}-bound FK-target {@code @nodeId} with an
 * authored {@code @condition} still needs the path for the {@link FkTargetConditionFilter}
 * correlation, so "{@code Local} plus a non-empty {@code joinPath}" is a legitimate state.
 * {@code Remote} means specifically that the <em>value</em> predicate reaches through the path.
 */
public sealed interface FilterBinding {

    /**
     * The predicate binds columns on the field's own table: a bare {@link BodyParam.Eq} /
     * {@link BodyParam.In} / {@link BodyParam.RowEq} / {@link BodyParam.RowIn}.
     *
     * <p>{@code ownTableColumns} is the tuple to bind, positionally aligned with whatever the
     * call-site extraction produces (for a {@code @nodeId} lift, the target NodeType's key columns
     * in {@code __NODE_KEY_COLUMNS} order). The arm carries the tuple because for a lifted
     * FK-target carrier it is genuinely different from the carrier's own {@code columns()}: the
     * FK-child columns on this table, not the target's key columns. The arm name states the referent
     * so no reader has to recover which table the tuple lives on from context.
     */
    record Local(List<ColumnRef> ownTableColumns) implements FilterBinding {

        public Local {
            ownTableColumns = List.copyOf(ownTableColumns);
            if (ownTableColumns.isEmpty()) {
                throw new IllegalArgumentException(
                    "FilterBinding.Local requires at least one column; a carrier with no own-table"
                    + " column to bind is Remote");
            }
        }
    }

    /**
     * The predicate binds the carrier's own {@code columns()} against the terminal table of the
     * carrier's {@code joinPath}; the emitter wraps it in a
     * {@link BodyParam.RemoteColumnPredicate} (a correlated {@code EXISTS}).
     *
     * <p>Payload-free on purpose. In both cases reaching this arm the terminal tuple already
     * <em>is</em> the carrier's {@code columns()}: a translated FK-target {@code @nodeId} binds the
     * target NodeType's key columns, and a plain joined {@code @reference} binds the resolved
     * terminal column. A second slot holding a copy of {@code columns()} would be a drift risk with
     * no enforcer, so only {@link Local} carries a tuple that {@code columns()} cannot supply.
     *
     * <p>The carrying record's compact constructor enforces the invariant this arm depends on: a
     * {@code Remote} binding requires a non-empty {@code joinPath}.
     */
    record Remote() implements FilterBinding {}

    /**
     * The one text for "this carrier's value reaches its target through a join, and the rail asking
     * cannot emit that". Four rails refuse a {@link Remote} binding independently (INSERT, UPDATE,
     * DELETE, and the query-side {@code @lookupKey} lookup) and they do not share an error channel:
     * two take a bare {@link Rejection} and two take a {@link Rejection.AuthorError} sibling arm
     * that a {@link Rejection.Deferred} does not type-check against. One cause keeps one identity by
     * sharing this message rather than by forcing one {@code Rejection} instance through four
     * differently-shaped channels.
     *
     * @param railDescription what the rail was trying to do with the carrier, as a verb phrase
     *     ("written on INSERT", "used as a lookup key")
     */
    static String remoteBindingUnsupported(String fieldName, String railDescription) {
        return "field '" + fieldName + "': the FK-target @nodeId reference reaches its target's key"
            + " columns through a join (the foreign key targets columns other than the NodeType's"
            + " key columns), so this table holds no column the decoded id can bind against. Being "
            + railDescription + " needs a key-to-FK-column subquery, which is not implemented;"
            + " the read-side filter predicate is supported.";
    }
}
