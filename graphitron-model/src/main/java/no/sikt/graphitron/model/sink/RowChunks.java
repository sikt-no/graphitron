package no.sikt.graphitron.model.sink;

import org.jooq.Row;

import java.util.ArrayList;
import java.util.List;

/**
 * Rows in statement-sized runs, for a writer whose relation is larger than one statement.
 *
 * <p>A multi-row {@code VALUES} is parsed by recursing once per row group, so a whole relation in
 * one statement overflows H2's parser stack: a classfile census is tens of thousands of rows and a
 * consumer database's columns are thousands. Bounding the rows per statement is the whole fix, and
 * the bound lives here so the writers that need it cannot drift apart on the number.
 */
public final class RowChunks {

    private static final int ROWS_PER_STATEMENT = 500;

    private RowChunks() {}

    public static <R extends Row> List<List<R>> of(List<R> rows) {
        var chunks = new ArrayList<List<R>>();
        for (int from = 0; from < rows.size(); from += ROWS_PER_STATEMENT) {
            chunks.add(rows.subList(from, Math.min(from + ROWS_PER_STATEMENT, rows.size())));
        }
        return chunks;
    }
}
