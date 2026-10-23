package no.sikt.graphitron.rewrite.model;

import no.sikt.graphitron.model.jooq.ColumnRef;
import no.sikt.graphitron.model.jooq.TableRef;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/**
 * The columns a record-carrier DML statement's {@code RETURNING} captures, decided by the
 * classifier: the write table's primary key, then {@link #nodeKeyTail()}, the payload id's
 * node-key columns outside the primary key in node-key order. "Primary key first" is structural, so
 * an emitter reads the record shape off this one value rather than re-deciding it per verb.
 *
 * <p>The tail is non-empty only on a DELETE whose payload ids encode a node key reaching beyond the
 * primary key: the row is gone after the statement, so those columns must be read back from the
 * deleted row for the payload to encode them. Every other statement captures the primary key alone,
 * and its follow-up SELECT (or a node key equal to the primary key) supplies the rest.
 */
public record Returning(List<ColumnRef> primaryKey, List<ColumnRef> nodeKeyTail) {

    public Returning {
        primaryKey = List.copyOf(primaryKey);
        nodeKeyTail = List.copyOf(nodeKeyTail);
        var pkNames = new HashSet<String>();
        for (var c : primaryKey) pkNames.add(c.sqlName());
        for (var c : nodeKeyTail) {
            if (pkNames.contains(c.sqlName())) {
                throw new IllegalArgumentException(
                    "Returning.nodeKeyTail column '" + c.sqlName() + "' is already a primary-key column");
            }
        }
    }

    /** The primary key alone: every statement whose payload needs nothing beyond it. */
    public static Returning primaryKeyOf(TableRef table) {
        return new Returning(table.primaryKeyColumns(), List.of());
    }

    /**
     * The primary key, widened to {@code nodeKey}'s columns outside it, in {@code nodeKey} order:
     * a DELETE whose payload ids encode {@code nodeKey}.
     */
    public static Returning widenedToNodeKey(TableRef table, List<ColumnRef> nodeKey) {
        return new Returning(table.primaryKeyColumns(), tailOutside(table.primaryKeyColumns(), nodeKey));
    }

    /** {@code nodeKey}'s columns outside {@code primaryKey}, in {@code nodeKey} order. */
    public static List<ColumnRef> tailOutside(List<ColumnRef> primaryKey, List<ColumnRef> nodeKey) {
        var pkNames = new HashSet<String>();
        for (var c : primaryKey) pkNames.add(c.sqlName());
        var tail = new ArrayList<ColumnRef>();
        for (var c : nodeKey) {
            if (!pkNames.contains(c.sqlName())) tail.add(c);
        }
        return List.copyOf(tail);
    }

    /** Every captured column: the primary key, then the tail. */
    public List<ColumnRef> columns() {
        var out = new ArrayList<ColumnRef>(primaryKey.size() + nodeKeyTail.size());
        out.addAll(primaryKey);
        out.addAll(nodeKeyTail);
        return List.copyOf(out);
    }
}
