package no.sikt.graphitron.rewrite.model;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import no.sikt.graphitron.model.jooq.ColumnRef;

/**
 * A child field whose generated fetcher reads one or more columns off the parent's
 * already-materialized row by <em>base</em> name. The parent-projection contract requires every
 * such column to appear in the parent anchor's {@code $project} SELECT; this capability carries
 * that demand uniformly, so the walks that enforce the contract key on the capability rather
 * than on leaf identity.
 *
 * <p>Standalone (does not extend {@link GraphitronField}) so it applies as an orthogonal
 * capability without being restricted by the {@link ChildField} seal, mirroring
 * {@link BatchKeyField}. Generators receive {@link GraphitronField} and pattern-match with
 * {@code instanceof ParentRowDemand}.
 *
 * <p>Implementers: the multi-table polymorphic {@link ChildField.InterfaceField} /
 * {@link ChildField.UnionField}, whose demand {@link #polymorphicParentRowColumns} derives, and
 * the single-table {@link ChildField.TableInterfaceField}, whose demand is its FK hop's
 * source-side columns.
 *
 * <p>Record-backed parents never carry a parent-row demand: their single-fetch accessor reads a
 * held {@code TableRecord} whose row type is complete, and their batched key lift rides the held
 * object, not the parent SELECT. The consuming walks are all gated on a table-backed parent, so a
 * record-sourced field reaching one is a generator bug; the walks fail loudly rather than
 * force-projecting the wrong columns.
 */
public interface ParentRowDemand {

    /**
     * The columns this child field's generated fetcher reads off the parent's already-materialized
     * row by base name. Possibly empty (a single-cardinality polymorphic field with only
     * unbound participants demands nothing). Never {@code null}.
     */
    List<ColumnRef> parentRowColumns();

    /**
     * The parent-row column demand for a multi-table polymorphic child field, shared by
     * {@link ChildField.InterfaceField} and {@link ChildField.UnionField} (identical shape).
     *
     * <p>Forked on cardinality. A list/connection field's batched form extracts the DataLoader
     * key by reading {@code parentSourceKey.columns()} off the parent row regardless of the
     * per-participant correlation shape, so the demand is exactly those columns. A
     * single-cardinality field's single-fetch form correlates each branch against
     * {@code parentRecord}, so the demand is the union, across {@code participantJoinPaths}
     * values, of what {@code MultiTablePolymorphicEmitter.singleBranchCorrelationWhere} reads.
     * A condition-headed hop demands the parent's bound key because the emitter pins the joined
     * parent alias to it; an {@link On.Lateral} hop-0 throws, mirroring the emitter's own
     * unreachable-arm guard.
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
