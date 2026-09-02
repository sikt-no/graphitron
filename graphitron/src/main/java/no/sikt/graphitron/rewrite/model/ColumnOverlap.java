package no.sikt.graphitron.rewrite.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import no.sikt.graphitron.model.jooq.ColumnRef;

/**
 * The shared per-column overlap analysis for the DML mutation write paths.
 *
 * <p>"Group the writers of a record / SET clause by backing column; a column with two or more
 * writers is an overlap; a decode-involving overlap needs a runtime value-agreement check, and what
 * an all-plain overlap means is the consuming site's call" was hand-rolled in six places, accreted
 * one per write surface as the agreement gap was closed across the write surfaces. This is the one
 * grouping those six now read. All-plain is a build-time reject on the DML SET-map paths, whose
 * runtime keeps one value per column; on the {@code @service} jOOQ-record path a declared
 * {@code @deprecated}-alias group is instead merged into one writer with ordered read paths, so an
 * all-plain overlap never survives the classifier to reach that site's emitter. The grouping is a
 * pure structural fold over already-resolved
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
     * One target column paired with the decode-record slot the writer reads its value from: the
     * 0-based position of the column in the {@code Record<N>} the writer's {@code @nodeId} decode
     * returns, or {@code 0} for a plain writer that performs no decode.
     *
     * <p>The pairing is the contract, and it replaces a prose invariant that said the order of a
     * writer's column list <em>was</em> its decode-record slot order. That invariant held only while
     * a carrier handed its columns to one consumer whole. The UPDATE partition can now give a
     * cross-table {@code @nodeId} reference's in-key column to the WHERE side and its out-of-key
     * column to the SET side, leaving each side a non-contiguous slice of one decode record; a
     * reader recovering the slot from list position would then read the wrong slot and silently
     * write one decoded key column's value into another column. Stating the slot makes that a
     * compile-time obligation on every adapter instead of a comment.
     */
    public record SlotColumn(int slot, ColumnRef column) {
        public SlotColumn {
            if (slot < 0) {
                throw new IllegalArgumentException("decode-record slot cannot be negative: " + slot);
            }
        }

        /**
         * The slot pairing for a writer whose columns <em>are</em> one whole decode record, in
         * order: slot {@code i} for the column at position {@code i}. The adapters whose carrier
         * never splits a decode record across consumers (the {@code @service} jOOQ-record writers,
         * the INSERT leaves, a plain single-column field) build their pairing with this; the UPDATE
         * partition, which does split, carries a stated slot on each column instead.
         */
        public static List<SlotColumn> contiguous(List<ColumnRef> columns) {
            var out = new ArrayList<SlotColumn>(columns.size());
            for (int i = 0; i < columns.size(); i++) {
                out.add(new SlotColumn(i, columns.get(i)));
            }
            return List.copyOf(out);
        }
    }

    /**
     * A minimal read-only view of one writer of a record / SET clause: the target columns it writes
     * with the decode slot each reads from, whether it involves a {@code @nodeId} decode, and a
     * dotted access-path label used only for the agreement / reject message. Each of the three
     * carrier families (the {@code @service} {@code Writer}, the INSERT {@code SetField} leaves, the
     * UPDATE-SET {@code SetGroup}s) adapts into this view at its site; the private carrier records do
     * not implement it, so no shared base class is forced onto them. Every consumer reads all three
     * accessors, so the view carries no dead field per consumer.
     */
    public interface ColumnWriter {
        /** The target columns this writer writes, each paired with the decode-record slot it reads
         *  ({@link SlotColumn#contiguous} for a writer whose columns are one whole decode record). */
        List<SlotColumn> targetColumns();

        /** Whether this writer involves a {@code @nodeId} decode (a composite / reference carrier, or a
         *  {@code ColumnField} whose extraction is {@code NodeIdDecodeKeys}). A decode-involving overlap
         *  needs the runtime value-agreement check; what an all-plain overlap means is per-site (see the
         *  class javadoc). */
        boolean decode();

        /** A dotted SDL access-path reference (e.g. {@code details.title}) for the agreement / reject
         *  message; never read for control flow. */
        String label();
    }

    /**
     * One writer's contribution to one column: the {@code slot} the writer reads the column's value
     * from in its decode record (see {@link SlotColumn}) and the resolved {@link ColumnRef}.
     * Generalizes the per-site contributor records the six sites hand-rolled ({@code SlotRef},
     * {@code InsertColWriter}, {@code SetColWriter}, the raw {@code int[]{groupIndex, slot}} tuples).
     */
    public record Contributor(ColumnWriter writer, int slot, ColumnRef column) {}

    /**
     * One backing column with its ordered contributing writers. {@code shared()} when two or more
     * writers land on it (the dedup + agreement case); {@code allPlain()} when no contributor is a
     * decode, which the DML validators reject when also shared and the {@code @service} jOOQ-record
     * classifier instead admits as a merged alias group (see the class javadoc). Generalizes
     * {@code InsertCol} and its near-identical clone {@code SetCol}.
     */
    public record OverlapColumn(ColumnRef column, List<Contributor> contributors) {
        public boolean shared() { return contributors.size() >= 2; }

        public boolean allPlain() { return contributors.stream().noneMatch(c -> c.writer().decode()); }
    }

    /**
     * Groups {@code writers} by backing-column {@link ColumnRef#sqlName()} into per-column
     * {@link OverlapColumn}s, keyed in writer-encounter order. A writer contributes one
     * {@link Contributor} per target column, carrying that column's stated decode slot; a
     * single-column plain field contributes one at slot 0. <em>Every</em> column is kept, size-one
     * included, so each consumer filters by the predicate it forks on ({@code shared()} /
     * {@code shared() && allPlain()}) rather than the primitive pre-filtering. The
     * {@code OverlapColumn}'s {@link ColumnRef} is the first contributor's, so two writers landing on
     * one SQL name (the overlap case) collapse to one column entry.
     */
    public static List<OverlapColumn> groupByColumn(List<? extends ColumnWriter> writers) {
        var byColumn = new LinkedHashMap<String, List<Contributor>>();
        for (var w : writers) {
            for (var sc : w.targetColumns()) {
                byColumn.computeIfAbsent(sc.column().sqlName(), k -> new ArrayList<>())
                    .add(new Contributor(w, sc.slot(), sc.column()));
            }
        }
        var out = new ArrayList<OverlapColumn>();
        byColumn.forEach((k, v) -> out.add(new OverlapColumn(v.get(0).column(), v)));
        return out;
    }
}
