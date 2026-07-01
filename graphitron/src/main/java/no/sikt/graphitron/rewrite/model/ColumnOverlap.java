package no.sikt.graphitron.rewrite.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * The shared per-column overlap analysis for the DML mutation write paths (R356).
 *
 * <p>"Group the writers of a record / SET clause by backing column; a column with two or more
 * writers is an overlap; an all-plain overlap is a build-time reject and a decode-involving one
 * needs a runtime value-agreement check" was hand-rolled in six places, accreted one per write
 * surface as R322 / R354 / R342 closed the agreement gap (see the R356 spec). This is the one
 * grouping those six now read. The grouping is a pure structural fold over already-resolved
 * {@link ColumnRef#sqlName()} values, so it is a shared <em>function</em> invoked at each site, not
 * a fact stored on a carrier: the {@code @mutation} validator runs at resolution time, before the
 * emit carriers exist, so a per-carrier stored fact would force the validator to keep its own walk.
 *
 * <p>Governed by "Builder-step results are sealed" and "Sub-taxonomies for resolution outcomes":
 * {@link #groupByColumn} folds a list of {@link ColumnWriter}s into a typed {@link OverlapColumn}
 * list. Each consumer reads off the predicate it forks on ({@link OverlapColumn#shared()} for the
 * agreement emitters, {@code shared() && }{@link OverlapColumn#allPlain()} for the validator reject)
 * rather than the primitive pre-filtering, so the validator's reject and the emitters' agreement
 * trigger read <em>one</em> fact instead of two hand-rolled walks that can diverge.
 */
public final class ColumnOverlap {

    private ColumnOverlap() {}

    /**
     * A minimal read-only view of one writer of a record / SET clause: the target columns it writes,
     * whether it involves a {@code @nodeId} decode, and a dotted access-path label used only for the
     * agreement / reject message. Each of the three carrier families (the {@code @service}
     * {@code Writer}, the INSERT {@code SetField} leaves, the UPDATE-SET {@code SetGroup}s) adapts
     * into this view at its site; the private carrier records do not implement it, so no shared base
     * class is forced onto them. Every consumer reads all three accessors, so the view carries no
     * dead field per consumer.
     *
     * <p><strong>Load-bearing invariant:</strong> the order of {@link #targetColumns()} <em>is</em>
     * the decode-record slot order. A {@link Contributor}'s {@code slot} indexes into this list, and
     * the agreement emit reads it back as {@code value<slot+1>()} off a decode {@code Record<N>}. An
     * adapter that returned columns in a different order than its decode-record slots would silently
     * misread the wrong slot.
     */
    public interface ColumnWriter {
        /** The target columns this writer writes, in decode-record slot order (see the invariant above). */
        List<ColumnRef> targetColumns();

        /** Whether this writer involves a {@code @nodeId} decode (a composite / reference carrier, or a
         *  {@code ColumnField} whose extraction is {@code NodeIdDecodeKeys}). An all-plain overlap is a
         *  build-time reject; a decode-involving one needs the runtime value-agreement check. */
        boolean decode();

        /** A dotted SDL access-path reference (e.g. {@code details.title}) for the agreement / reject
         *  message; never read for control flow. */
        String label();
    }

    /**
     * One writer's contribution to one column: the {@code slot} index of the column within the
     * writer's {@link ColumnWriter#targetColumns()} (always 0 for a single-column plain field) and
     * the resolved {@link ColumnRef}. Generalizes the per-site contributor records the six sites
     * hand-rolled ({@code SlotRef}, {@code InsertColWriter}, {@code SetColWriter}, the raw
     * {@code int[]{groupIndex, slot}} tuples).
     */
    public record Contributor(ColumnWriter writer, int slot, ColumnRef column) {}

    /**
     * One backing column with its ordered contributing writers. {@code shared()} when two or more
     * writers land on it (the dedup + agreement case); {@code allPlain()} when no contributor is a
     * decode (the validator's build-time reject when also shared). Generalizes {@code InsertCol} and
     * R342's near-identical clone {@code SetCol}.
     */
    public record OverlapColumn(ColumnRef column, List<Contributor> contributors) {
        public boolean shared() { return contributors.size() >= 2; }

        public boolean allPlain() { return contributors.stream().noneMatch(c -> c.writer().decode()); }
    }

    /**
     * Groups {@code writers} by backing-column {@link ColumnRef#sqlName()} into per-column
     * {@link OverlapColumn}s, keyed in writer-encounter order. A writer contributes one
     * {@link Contributor} per target column (carrying that column's slot index); a single-column
     * plain field contributes one at slot 0. <em>Every</em> column is kept, size-one included, so
     * each consumer filters by the predicate it forks on ({@code shared()} /
     * {@code shared() && allPlain()}) rather than the primitive pre-filtering. The
     * {@code OverlapColumn}'s {@link ColumnRef} is the first contributor's, so two writers landing on
     * one SQL name (the overlap case) collapse to one column entry.
     */
    public static List<OverlapColumn> groupByColumn(List<? extends ColumnWriter> writers) {
        var byColumn = new LinkedHashMap<String, List<Contributor>>();
        for (var w : writers) {
            var cols = w.targetColumns();
            for (int s = 0; s < cols.size(); s++) {
                byColumn.computeIfAbsent(cols.get(s).sqlName(), k -> new ArrayList<>())
                    .add(new Contributor(w, s, cols.get(s)));
            }
        }
        var out = new ArrayList<OverlapColumn>();
        byColumn.forEach((k, v) -> out.add(new OverlapColumn(v.get(0).column(), v)));
        return out;
    }
}
