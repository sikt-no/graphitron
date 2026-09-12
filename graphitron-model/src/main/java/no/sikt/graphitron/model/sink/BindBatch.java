package no.sikt.graphitron.model.sink;

import org.jooq.DSLContext;
import org.jooq.Param;
import org.jooq.Query;
import org.jooq.Row;

import java.util.Collections;
import java.util.List;
import java.util.function.Function;

/**
 * Rows through one statement, bound once each, for a writer holding a whole relation's rows.
 *
 * <p>The alternative is a statement carrying the rows in its text, and the cost of that is not the
 * bytes: H2 parses per statement, and it renders a multi-row upsert as a MERGE over a chain of
 * unioned SELECTs whose parser recurses once per row group and clones the token list at each level.
 * The work is quadratic in the rows one statement carries, so a whole relation exhausts the heap on
 * a large schema and a bounded chunk merely moves the cost rather than removing it: total time comes
 * out linear in the bound, and no bound reaches what one statement does. A relation written here
 * renders once and parses once however many rows it has.
 *
 * <p>Rows arrive as {@link Row} because that is what the collector at the call site already builds,
 * one function per column, and keeping that spelling is the point: the statement changes and the
 * column list, the extractors and the conflict clause do not. Every extractor bottoms out in a bind
 * value, which is what lets the values be read back off the row and handed to jOOQ as a table; an
 * expression that is not one has no value to bind and fails here rather than binding something
 * else.
 *
 * <p>The statement is built against a row of markers, so its column types come from the relation
 * named in the insert rather than from the rows, and a bucket that is empty writes nothing.
 *
 * <p>One row per statement execution also settles what two rows sharing a key mean. In a multi-row
 * MERGE they can collide; here the second updates the first, which is the disposition the conflict
 * clause already states for rows arriving in separate readings.
 *
 * @see FactWrites for the same mechanism spelled per relation, where the rows are records
 */
public final class BindBatch {

    private BindBatch() {}

    /**
     * Writes every row of {@code rows} through one execution of {@code statement}, which is rendered
     * once against a row of bind markers and bound once per row.
     *
     * @param statement given one marker per column, the statement to bind. The markers are never
     *                  sent; their count is what the render needs
     */
    public static <R extends Row> void execute(DSLContext dsl, List<R> rows,
                                               Function<List<Object>, ? extends Query> statement) {
        if (rows.isEmpty()) {
            return;
        }
        int width = rows.getFirst().size();
        var bindings = new Object[rows.size()][];
        for (int i = 0; i < rows.size(); i++) {
            var fields = rows.get(i).$fields();
            var values = new Object[width];
            for (int j = 0; j < width; j++) {
                if (!(fields.get(j) instanceof Param<?> bound)) {
                    throw new IllegalArgumentException(
                        "column " + j + " of this relation's rows is " + fields.get(j)
                            + ", which has no value to bind. Every column written here reads its "
                            + "value off the element, so an expression the database evaluates "
                            + "belongs in the statement rather than in the row");
                }
                values[j] = bound.getValue();
            }
            bindings[i] = values;
        }
        dsl.batch(statement.apply(Collections.nCopies(width, null)), bindings).execute();
    }
}
