package no.sikt.graphitron.model.sink;

import org.jooq.Query;
import org.jooq.Row;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Rows in statement-sized runs, for a writer whose relation is larger than one statement.
 *
 * <p>A multi-row {@code VALUES} is parsed by recursing once per row group, so a whole relation in
 * one statement overflows H2's parser stack: a classfile census is tens of thousands of rows and a
 * consumer database's columns are thousands. Bounding the rows per statement is the whole fix, and
 * the bound lives here so the writers that need it cannot drift apart on the number.
 *
 * <p>Worse than a stack overflow where the statement is an upsert. H2 renders one for a multi-row
 * insert as a MERGE over a chain of unioned SELECTs, one per row, and its parser clones the whole
 * token list once per nesting level, so N rows cost on the order of N squared token references. A
 * consumer with a 27000-line schema met that as an {@code OutOfMemoryError} on a 16 GB heap ninety
 * seconds into a capture; raising the heap does not help a quadratic.
 *
 * <p>Why 500 and not another number. Tokens copied per statement go as rows squared times tokens
 * per row, so 500 rows of a 40-token row is on the order of ten million token references per
 * statement, which is nothing. Relation width is a small constant factor on that, which is why the
 * bound is not made width-aware. Anyone meeting a slow capture later can price a larger bound off
 * that sentence rather than deriving it again.
 */
public final class RowChunks {

    private static final int ROWS_PER_STATEMENT = 500;

    private RowChunks() {}

    /**
     * Runs {@code statement} once per chunk of {@code rows}, which is the one way this module
     * writes more than one row at a time.
     *
     * <p>Splitting one statement into several is the same write only where no two rows in one call
     * share a primary key, or a collision one statement would surface becomes an upsert over an
     * upsert across chunks. Every caller here has that by construction rather than by luck: the
     * entry writers key on the position a node was written at, so two rows out of one parse of one
     * file cannot collide; the rest key on an ordinal assigned by enumeration or on a name drawn
     * from a set.
     *
     * <p>An empty list runs no statement, so a caller needs no guard of its own for that.
     */
    public static <R extends Row> void execute(List<R> rows,
                                               Function<List<R>, ? extends Query> statement) {
        for (List<R> chunk : of(rows)) {
            statement.apply(chunk).execute();
        }
    }

    public static <R extends Row> List<List<R>> of(List<R> rows) {
        var chunks = new ArrayList<List<R>>();
        for (int from = 0; from < rows.size(); from += ROWS_PER_STATEMENT) {
            chunks.add(rows.subList(from, Math.min(from + ROWS_PER_STATEMENT, rows.size())));
        }
        return chunks;
    }
}
