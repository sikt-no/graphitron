package no.sikt.graphitron.rewrite.model;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * A child field whose generated fetcher reads one or more columns off the parent's
 * already-materialized row by <em>base</em> name. The parent-projection contract
 * (R333, shipped by R432) says every such column must appear in the parent anchor's
 * {@code $fields} SELECT; this capability is what carries that demand uniformly, so the
 * three walks that enforce the contract key on the capability rather than on leaf identity
 * (the R425 walk-omission family).
 *
 * <p>Standalone (does not extend {@link GraphitronField}) so it applies as an orthogonal
 * capability without being restricted by the {@link ChildField} seal, mirroring
 * {@link BatchKeyField}. Generators receive {@link GraphitronField} and pattern-match with
 * {@code instanceof ParentRowDemand}.
 *
 * <p>Implemented by the field variants whose fetchers correlate against the parent row by
 * name:
 * <ul>
 *   <li>{@link ChildField.TableMethodField} — its single-FK-hop source-side columns, read
 *       by {@code TypeFetcherGenerator.buildChildTableMethodFetcher} as
 *       {@code parentRecord.get(DSL.name("<src>"), …)}.</li>
 *   <li>{@link ChildField.InterfaceField} / {@link ChildField.UnionField} — the multi-table
 *       polymorphic child fetchers. The demand forks on the field's cardinality (see
 *       {@link #polymorphicParentRowColumns}): a single-cardinality field's single-fetch form
 *       reads each branch's parent-side correlation columns off {@code parentRecord}
 *       (the union across participants of what {@code MultiTablePolymorphicEmitter
 *       .singleBranchCorrelationWhere} reads); a list/connection field's batched form extracts
 *       the DataLoader key by reading {@code parentSourceKey.columns()} off the parent row.</li>
 * </ul>
 *
 * <p>Record-backed parents never carry a parent-row demand: their single-fetch accessor reads a
 * held {@code TableRecord} whose row type is complete, and their batched key lift rides the held
 * object, not the parent SELECT. The walks that consume this capability are all gated on a
 * table-backed parent, so a record-sourced field reaching one is a generator bug (the walks fail
 * loudly rather than force-projecting the wrong columns).
 */
public interface ParentRowDemand {

    /**
     * The columns this child field's generated fetcher reads off the parent's already-materialized
     * row by base name. Possibly empty (a {@link ChildField.TableMethodField} whose path is not a
     * single FK hop demands nothing; a single-cardinality polymorphic field with only unbound
     * participants demands nothing). Never {@code null}.
     */
    List<ColumnRef> parentRowColumns();

    /**
     * The parent-row column demand for a multi-table polymorphic child field, shared by
     * {@link ChildField.InterfaceField} and {@link ChildField.UnionField} (identical shape).
     *
     * <p>Forked on cardinality:
     * <ul>
     *   <li><b>List / connection</b> ({@code isList}) — the batched forms extract the DataLoader key
     *       by reading {@code parentSourceKey.columns()} off {@code env.getSource()}
     *       (via {@code GeneratorUtils.buildRecordParentKeyExtraction}), regardless of the
     *       per-participant correlation shape. Returns exactly those columns (gap B).</li>
     *   <li><b>Single</b> — the single-fetch form reads each branch's parent side off
     *       {@code parentRecord}. Returns the union, across {@code participantJoinPaths} values, of
     *       what {@code MultiTablePolymorphicEmitter.singleBranchCorrelationWhere} reads (gap A):
     *       a {@link ParticipantCorrelation.KeyTupleWhere} slot's {@code sourceSide()} columns; a
     *       {@link ParticipantCorrelation.JoinedCorrelation} FK hop-0's slot {@code sourceSide()}
     *       columns; the parent's bound key ({@code parentSourceKey.columns()}) for a
     *       {@code JoinedCorrelation} condition hop-0 (which {@code parentKeyBoundWhere} pins the
     *       joined parent alias to). An {@link On.Lateral} hop-0 throws
     *       {@link IllegalStateException}, mirroring the emitter's own unreachable-arm guard.</li>
     * </ul>
     */
    static List<ColumnRef> polymorphicParentRowColumns(
            boolean isList,
            Map<String, ParticipantCorrelation> participantJoinPaths,
            SourceKey parentSourceKey) {
        if (isList) {
            return List.copyOf(parentSourceKey.columns());
        }
        var columns = new LinkedHashSet<ColumnRef>();
        for (var correlation : participantJoinPaths.values()) {
            switch (correlation) {
                case ParticipantCorrelation.KeyTupleWhere k -> columns.addAll(k.on().sourceSideColumns());
                case ParticipantCorrelation.JoinedCorrelation jc -> {
                    switch (((JoinStep.Hop) jc.hops().get(0)).on()) {
                        case On.ColumnPairs cp -> columns.addAll(cp.sourceSideColumns());
                        case On.Predicate ignored -> columns.addAll(parentSourceKey.columns());
                        case On.Lateral ignored -> throw new IllegalStateException(
                            "a lateral hop cannot head a @referenceFor path");
                    }
                }
            }
        }
        return List.copyOf(columns);
    }
}
