package no.sikt.graphitron.model.capture.document;

import no.sikt.graphitron.model.schema.SchemaError;
import org.jooq.DSLContext;
import org.jooq.Rows;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.IntStream;

import static no.sikt.graphitron.model.Tables.GRAPHQL_SCHEMA_PROBLEM;
import static org.jooq.impl.DSL.excluded;
import static org.jooq.impl.DSL.val;

/**
 * Records what went wrong when the documents were made into a schema, as graphql-java stated it.
 *
 * <p>One relation for the whole attempt rather than one per stage. Merging the documents into a
 * registry and building a schema out of it are not two questions a reader has: both are what
 * happened when we tried to make a schema out of these files. The merge's own contribution is only
 * whether a name was declared twice, three error classes in all, where the build runs around thirty
 * checks over references, interface contracts, input and output position, directive locations and
 * the schema's own shape. Splitting them would offer a distinction that costs a reader something to
 * learn and buys nothing.
 *
 * <p>Nothing here is re-derived. The build is the one step that decides whether the documents work
 * together, and computing any of it from the declaration entries would be a second implementation of
 * a validation this build already runs, which would disagree with the first the moment the library
 * moved.
 *
 * <p>Absence is success, and it is meant to be load-bearing: a graph whose documents made a schema
 * has no rows here and has anchors, and a graph with rows here has none. So asking whether the
 * corpus is sound is a count, and a reader who finds anchors need not ask at all.
 *
 * <p>Swept per graph. Whether a problem is raised depends on the whole document set, so an edit to
 * one file can retire a problem that names another, and no per-file scope could find it.
 */
public final class SdlSchemaProblems {

    private SdlSchemaProblems() {}

    /**
     * A problem reduced to what is recorded of it, so two of them can be compared.
     *
     * <p>A record rather than a pair of list elements: the components are named where an index is
     * not, and a record tolerates a null component where {@link List#of} throws. Nothing in
     * graphql-java promises a message, and a gatherer that fell over on one would be refusing to
     * report the very thing it exists to report.
     */
    private record Problem(String errorClass, String message) {}

    /**
     * Makes {@code graph}'s problems be exactly {@code raised}, distinct, in the order they came.
     *
     * <p>Distinct because the same problem said twice is not two problems. One missing type
     * referenced five times raises five errors whose sentences are byte-identical: the message
     * formats the absent type and the type that reached for it, never the field, so the five carry
     * nothing that tells them apart. How many references there were is a count over the declaration
     * entries, which hold each field and its written type, so nothing is lost by keeping one row and
     * a reader is spared four.
     */
    public static void write(DSLContext dsl, String graph, List<SchemaError> raised,
                             LocalDateTime touchedAt) {
        var t = GRAPHQL_SCHEMA_PROBLEM;
        var problems = raised.stream()
            .map(problem -> new Problem(problem.errorClass(), problem.message()))
            .distinct()
            .toList();
        var rows = IntStream.range(0, problems.size()).boxed()
            .collect(Rows.toRowList(
                i -> val(graph, t.GRAPH_NAME),
                i -> val(i, t.ORDINAL),
                i -> val(touchedAt, t.TOUCHED_AT),
                i -> val(problems.get(i).errorClass(), t.ERROR_CLASS),
                i -> val(problems.get(i).message(), t.MESSAGE)));

        if (!rows.isEmpty()) {
            dsl.insertInto(t, t.GRAPH_NAME, t.ORDINAL, t.TOUCHED_AT, t.ERROR_CLASS, t.MESSAGE)
                .valuesOfRows(rows)
                .onDuplicateKeyUpdate()
                .set(t.TOUCHED_AT, excluded(t.TOUCHED_AT))
                .set(t.ERROR_CLASS, excluded(t.ERROR_CLASS))
                .set(t.MESSAGE, excluded(t.MESSAGE))
                .execute();
        }

        dsl.deleteFrom(t)
            .where(t.GRAPH_NAME.eq(graph))
            .and(t.TOUCHED_AT.ne(touchedAt))
            .execute();
    }
}
