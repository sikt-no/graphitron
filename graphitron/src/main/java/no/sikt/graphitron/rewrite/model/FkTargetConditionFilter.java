package no.sikt.graphitron.rewrite.model;

import java.util.List;

/**
 * A developer-supplied {@code @condition} method on an FK-target {@code @nodeId} filter input
 * field: the field carries both {@code @nodeId(typeName: "X")} and {@code @condition},
 * where {@code X} is reached from the input's own table through a foreign-key {@code joinPath}.
 *
 * <p>This is the FK-target sibling of {@link ConditionFilter}. The two diverge at exactly one
 * point, and the divergence is structural rather than a flag: the implicit first parameter of a
 * plain {@link ConditionFilter} is the field's <em>own</em> table (emitted as the {@code table}
 * local), but for an FK-target field the developer method's first parameter is the FK-<em>target</em>
 * table {@code X}, not the input's own table. Handing the own-table local to such a method is the
 * bug this filter exists to fix (a {@code Soknadsmangeltype} passed where {@code Regelverksamling}
 * is expected, failing at consumer compile). {@link QueryConditionsGenerator} forks on this type and emits a correlated
 * {@code EXISTS} over {@code joinPath} so the developer method receives an alias for {@code X}
 * rather than the root {@code table}.
 *
 * <p>The condition method itself is held as {@link #delegate()} (already rewrapped for nested
 * extraction by {@code ConditionResolver.rewrapForNested} when this filter is built); the
 * {@link WhereFilter} accessors delegate to it. The added components carry the FK correlation the
 * emitter needs:
 *
 * <ul>
 *   <li>{@link #targetTable()} — the FK-target table {@code X} the developer method expects; the
 *       terminal table of {@link #joinPath()}.</li>
 *   <li>{@link #joinPath()} — the resolved FK-derived {@link JoinStep.Hop}s from the input's own table
 *       to {@code X}. Single-hop for the common case; multi-hop walked inside the {@code EXISTS}.</li>
 *   <li>{@link #liftedSourceColumns()} — the FK-child column tuple on the input's own table
 *       (the {@code override: false} implicit-predicate path binds decoded keys against these
 *       directly, with no join; carried here for symmetry and validation).</li>
 *   <li>{@link #keyColumns()} — {@code X}'s key columns the decoded NodeId maps to.</li>
 * </ul>
 *
 * <p>Composite-key FK targets ({@code keyColumns().size() > 1}) are supported: the
 * correlated {@code EXISTS} ANDs every composite-FK slot via
 * {@code JoinPathEmitter.emitCorrelationWhere}, so the composite case wraps in this same filter and
 * reaches the emitter exactly like the single-column case.
 */
public record FkTargetConditionFilter(
    ConditionFilter delegate,
    TableRef targetTable,
    List<JoinStep> joinPath,
    List<ColumnRef> liftedSourceColumns,
    List<ColumnRef> keyColumns
) implements WhereFilter {

    public FkTargetConditionFilter {
        joinPath = List.copyOf(joinPath);
        liftedSourceColumns = List.copyOf(liftedSourceColumns);
        keyColumns = List.copyOf(keyColumns);
    }

    @Override
    public String className() {
        return delegate.className();
    }

    @Override
    public String methodName() {
        return delegate.methodName();
    }

    @Override
    public List<CallParam> callParams() {
        return delegate.callParams();
    }
}
