package no.sikt.graphitron.rewrite.model;

import java.util.List;

/**
 * Orthogonal capability marking a field whose split-rows emitter blocks if its join path contains
 * a {@code @condition} step. Implemented by the four {@link ChildField} variants that share the
 * predicate ({@link ChildField.SplitTableField}, {@link ChildField.SplitLookupTableField},
 * {@link ChildField.RecordTableField}, {@link ChildField.RecordLookupTableField}) so the four
 * near-identical {@code SplitRowsMethodEmitter.unsupportedReason} overloads collapse to a single
 * dispatch and the validator's matching 4-arm {@code instanceof} chain collapses to one capability
 * check. Mirrors the pattern of {@link BatchKeyField}: standalone, does not extend
 * {@link GraphitronField}, applied via {@code instanceof}.
 *
 * <p>{@link #joinPath()} is the data the predicate evaluates over;
 * {@link #emitBlockReason()} is the {@link Rejection.EmitBlockReason} value the dispatch tags onto
 * its {@link Rejection.Deferred}; {@link #displayLabel()} is the per-variant prose label that
 * appears at the head of the rendered deferred message ("@splitQuery", "@splitQuery @lookupKey",
 * "RecordTableField", "RecordLookupTableField") so the validator log surface holds byte-stable
 * across the lift.
 */
public interface ConditionJoinReportable {
    List<JoinStep> joinPath();
    Rejection.EmitBlockReason emitBlockReason();
    String displayLabel();
}
