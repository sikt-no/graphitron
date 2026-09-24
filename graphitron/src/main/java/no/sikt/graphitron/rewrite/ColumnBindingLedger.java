package no.sikt.graphitron.rewrite;

import graphql.schema.FieldCoordinates;
import no.sikt.graphitron.model.jooq.ColumnRef;
import no.sikt.graphitron.model.jooq.TableRef;
import no.sikt.graphitron.rewrite.model.BodyParam;
import no.sikt.graphitron.rewrite.model.CallSiteExtraction;
import no.sikt.graphitron.rewrite.model.GeneratedConditionFilter;
import no.sikt.graphitron.rewrite.model.OperationMember;
import no.sikt.graphitron.rewrite.model.WhereFilter;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Which columns each filter slot of a classified field binds, whatever an authored
 * {@code @condition} does to the slot's implicit predicate: one row per use site and filtered
 * table, minted by the filter projection where the walk holds both. The tenant fold
 * ({@link TenantBindingIndex}) reads it for a condition member's tenant slots, so that
 * {@code override:} and an input field's own {@code @condition} decide which SQL predicate is
 * emitted and never which tenant a column-bound slot divines.
 *
 * <p><b>Why a walk-side ledger.</b> The fact is already captured in the store (the argument and
 * input-field column-match relations), but classification runs before capture, so this run's
 * rows do not exist when the tenant fold runs. The ledger carries the existing fact to that one
 * reader, grained the way the relations are, and retires with the reader when the fold re-sources
 * onto the store. {@link NodeIdDecodeLedger} is its shape precedent.
 *
 * <p><b>Keyed by the table.</b> On a multi-table polymorphic coordinate the projection runs once
 * per participant table, and one slot can bind a different tuple per participant (a bare
 * {@code @nodeId} decodes as each participant's own node type), so a row is keyed by the
 * {@code (coordinate, table)} a condition member carries
 * ({@link OperationMember.Condition#table()}).
 *
 * <p><b>Completeness.</b> The compiler owns the suppressed half: the question a row answers is
 * asked by an exhaustive switch over the carriers, ahead of every emission guard. The other half,
 * that every predicate the projection emits comes from a slot the ledger holds, is
 * {@link #requireCovers}, run on every schema build.
 */
public final class ColumnBindingLedger {

    /**
     * One filter slot's column binding: which columns the argument or input field binds, and how
     * its value is read at the call site. The columns are the ones the slot's implicit predicate
     * binds (for a {@code @reference} carrier, the tuple its binding lands on); a list because a
     * node-key carrier binds a tuple. The extraction is the one the predicate carries, so the
     * fold resolves the same read and transform whether or not the predicate was emitted.
     */
    public record ColumnBoundSlot(String slotName, List<ColumnRef> columns, CallSiteExtraction extraction) {
        public ColumnBoundSlot {
            Objects.requireNonNull(slotName, "slotName");
            Objects.requireNonNull(extraction, "extraction");
            columns = List.copyOf(columns);
            if (columns.isEmpty()) {
                throw new IllegalArgumentException("ColumnBoundSlot '" + slotName + "' binds no column");
            }
        }
    }

    private record Key(FieldCoordinates coordinate, TableRef table) {}

    /**
     * The not-computed sentinel for schemas built without the classify walk. The tenant fold
     * refuses it by reference identity, so a hand-built schema is refused rather than silently
     * classifying every condition path unbound.
     */
    public static final ColumnBindingLedger EMPTY = new ColumnBindingLedger(true);

    private final boolean sentinel;
    private final Map<Key, List<ColumnBoundSlot>> rows = new LinkedHashMap<>();

    ColumnBindingLedger() {
        this(false);
    }

    private ColumnBindingLedger(boolean sentinel) {
        this.sentinel = sentinel;
    }

    /**
     * Records the row for one filter surface: the column-bound slots the projection of
     * {@code coordinate}'s arguments against {@code table} produced, beside the final filter list
     * it returned.
     *
     * @throws IllegalStateException when a non-empty row comes with no filters (every suppression
     *     is an authored {@code @condition} whose own filter lands in the list, and a leaf with
     *     no filters mints no condition member to read the row), or when a repeat record at the
     *     same key carries a different row (the same classification of the same slots cannot
     *     differ, so a difference is a walk defect)
     */
    void record(FieldCoordinates coordinate, TableRef table, List<ColumnBoundSlot> slots,
                List<WhereFilter> filters) {
        if (sentinel) {
            throw new IllegalStateException("the EMPTY column-binding ledger records nothing");
        }
        var row = List.copyOf(slots);
        if (!row.isEmpty() && filters.isEmpty()) {
            throw new IllegalStateException("generator invariant failure: '" + render(coordinate)
                + "' on table '" + table.tableName() + "' binds filter slots "
                + row.stream().map(ColumnBoundSlot::slotName).toList()
                + " but projects no filter, so no condition member would read them");
        }
        var key = new Key(coordinate, table);
        var existing = rows.putIfAbsent(key, row);
        if (existing != null && !existing.equals(row)) {
            throw new IllegalStateException("generator invariant failure: '" + render(coordinate)
                + "' on table '" + table.tableName() + "' was projected twice with different"
                + " column bindings: " + existing + " and " + row);
        }
    }

    /** The slots recorded for {@code coordinate} against {@code table}, empty when none were. */
    public List<ColumnBoundSlot> slotsAt(FieldCoordinates coordinate, TableRef table) {
        return rows.getOrDefault(new Key(coordinate, table), List.of());
    }

    /**
     * Every emitted predicate has its slot: for every condition member of {@code members}, each
     * {@link BodyParam} of its {@link GeneratedConditionFilter}s (unwrapping
     * {@link BodyParam.RemoteColumnPredicate}) must have a slot in the row at the member's
     * {@code (coordinate, table)} with the same name, the same columns and an equal extraction.
     * The extraction is compared because it is half of what the tenant fold reads.
     *
     * <p>Containment in one direction only: a row may hold slots beyond the body params, which is
     * where the suppressed bindings live. The check reads body params to verify coverage; nothing
     * reads them to classify.
     *
     * @throws IllegalStateException naming the coordinate, table and slot of the first predicate
     *     with no matching slot
     */
    public void requireCovers(OperationMemberRelation members) {
        for (var entry : members.byCoordinate().entrySet()) {
            for (OperationMember member : entry.getValue()) {
                if (!(member instanceof OperationMember.Condition condition)) continue;
                var row = slotsAt(entry.getKey(), condition.table());
                for (WhereFilter filter : condition.filters()) {
                    if (!(filter instanceof GeneratedConditionFilter gcf)) continue;
                    for (BodyParam param : gcf.bodyParams()) {
                        requireSlot(entry.getKey(), condition.table(), row, predicateOf(param));
                    }
                }
            }
        }
    }

    private static void requireSlot(FieldCoordinates coordinate, TableRef table,
                                    List<ColumnBoundSlot> row, BodyParam.ColumnPredicate predicate) {
        var expected = new ColumnBoundSlot(predicate.name(), columnsOf(predicate), predicate.extraction());
        if (!row.contains(expected)) {
            throw new IllegalStateException("generator invariant failure: '" + render(coordinate)
                + "' on table '" + table.tableName() + "' emits a predicate for slot '"
                + predicate.name() + "' that the column-binding ledger holds no matching slot for"
                + " (expected " + expected + ", row " + row + ")");
        }
    }

    private static BodyParam.ColumnPredicate predicateOf(BodyParam param) {
        return switch (param) {
            case BodyParam.ColumnPredicate p -> p;
            case BodyParam.RemoteColumnPredicate remote -> remote.inner();
        };
    }

    private static List<ColumnRef> columnsOf(BodyParam.ColumnPredicate predicate) {
        return switch (predicate) {
            case BodyParam.Eq eq -> List.of(eq.column());
            case BodyParam.In in -> List.of(in.column());
            case BodyParam.RowEq rowEq -> rowEq.columns();
            case BodyParam.RowIn rowIn -> rowIn.columns();
        };
    }

    private static String render(FieldCoordinates coordinate) {
        return coordinate.getTypeName() + "." + coordinate.getFieldName();
    }
}
