package no.sikt.graphitron.model.capture.document;

import graphql.language.SourceLocation;
import no.sikt.graphitron.model.schema.SchemaError;
import no.sikt.graphitron.model.schema.SchemaLoader;
import no.sikt.graphitron.model.sink.BindBatch;
import org.jooq.DSLContext;
import org.jooq.Rows;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.stream.IntStream;

import static no.sikt.graphitron.model.Tables.GRAPHQL_SCHEMA_PROBLEM;
import static org.jooq.impl.DSL.excluded;
import static org.jooq.impl.DSL.val;

/**
 * Records what went wrong when the documents were read and made into a schema, as graphql-java
 * stated it.
 *
 * <p>One relation for all three stages, the stage a column. Parsing a file, merging the files and
 * building a schema of the result are not three questions a reader has, and none of it is
 * re-derived: computing any of these checks from the declaration entries would be a second
 * implementation of a validation the toolchain already runs.
 *
 * <p>A file the parser rejected reaches nothing else. It contributes no entries, so this is the
 * only relation that can say it was read, which is what the site columns are for.
 *
 * <p>Absence is success, and it is load-bearing: no rows means the corpus made a schema, so asking
 * whether it is sound is a count. It says nothing about anchors, which come from the entries and
 * stand whether or not the corpus builds.
 *
 * <p>Swept per graph. Whether a problem is raised depends on the whole document set, so an edit to
 * one file can retire a problem that names another, and no per-file scope could find it.
 */
public final class SdlSchemaProblems {

    private SdlSchemaProblems() {}

    /** The parse stage's own name, which no enum carries: the parser fails before a stage does. */
    private static final String PARSE_STAGE = "PARSE";

    /**
     * A problem reduced to what is recorded of it, so two of them can be compared. One shape for
     * all three stages: a syntax failure and a build refusal are different objects in
     * graphql-java's vocabulary and the same four facts here, which is what lets them share the
     * relation.
     */
    private record Problem(String stage, String errorClass, String message, String sourceName,
                           Integer line, Integer column) {}

    /**
     * Makes {@code graph}'s parse-stage problems be exactly the sources this reading could not
     * parse. Written by the gatherer that ran the parser, which is the only thing that sees a
     * syntax failure at all.
     */
    public static void writeParsed(DSLContext dsl, String graph,
                                   List<SchemaLoader.SyntaxFailure> failures,
                                   LocalDateTime touchedAt) {
        var seen = new ArrayList<Problem>();
        failures.forEach(failure -> seen.add(parseProblem(failure)));
        write(dsl, graph, seen, touchedAt);
        sweep(dsl, graph, List.of(PARSE_STAGE), touchedAt);
    }

    /**
     * Makes {@code graph}'s merge- and assembly-stage problems be exactly what those two refused,
     * in the order the stages ran. Written by the gatherer that assembles, each row naming its own
     * stage.
     */
    public static void writeAssembled(DSLContext dsl, String graph, List<SchemaError> raised,
                                      LocalDateTime touchedAt) {
        var seen = new ArrayList<Problem>();
        raised.forEach(error -> seen.add(stageProblem(error)));
        write(dsl, graph, seen, touchedAt);
        sweep(dsl, graph, Arrays.stream(SchemaError.Stage.values()).map(Enum::name).toList(),
            touchedAt);
    }

    /**
     * One stage's worth, or several, numbered within each stage and swept within each stage.
     *
     * <p>Distinct because the same problem said twice is not two problems. One missing type
     * referenced five times raises five errors whose sentences are byte-identical, the message
     * naming the absent type and the type that reached for it but never the field, and
     * graphql-java locating all five at the enclosing declaration. How many references there were
     * is a count over the entries.
     *
     * <p>Grouped by stage before it is numbered, because the ordinal is per stage: a caller passing
     * two stages' problems gets each numbered from zero, and a caller passing one leaves the other
     * alone. The sweep is scoped the same way, so a stage with nothing to say this reading clears
     * only its own rows and a stage that never ran keeps none of anybody else's.
     */
    private static void write(DSLContext dsl, String graph, List<Problem> seen,
                              LocalDateTime touchedAt) {
        var t = GRAPHQL_SCHEMA_PROBLEM;
        var byStage = new LinkedHashMap<String, List<Problem>>();
        seen.stream().distinct().forEach(problem ->
            byStage.computeIfAbsent(problem.stage(), stage -> new ArrayList<>()).add(problem));

        for (var stage : byStage.entrySet()) {
            var problems = stage.getValue();
            var rows = IntStream.range(0, problems.size()).boxed()
                .collect(Rows.toRowList(
                    i -> val(graph, t.GRAPH_NAME),
                    i -> val(i, t.ORDINAL),
                    i -> val(touchedAt, t.TOUCHED_AT),
                    i -> val(problems.get(i).stage(), t.STAGE),
                    i -> val(problems.get(i).errorClass(), t.ERROR_CLASS),
                    i -> val(problems.get(i).message(), t.MESSAGE),
                    i -> val(problems.get(i).sourceName(), t.SOURCE_NAME),
                    i -> val(problems.get(i).line(), t.SOURCE_LINE),
                    i -> val(problems.get(i).column(), t.SOURCE_COLUMN)));

            BindBatch.execute(dsl, rows, markers ->
                dsl.insertInto(t, t.GRAPH_NAME, t.ORDINAL, t.TOUCHED_AT, t.STAGE, t.ERROR_CLASS,
                        t.MESSAGE, t.SOURCE_NAME, t.SOURCE_LINE, t.SOURCE_COLUMN)
                    .values(markers)
                    .onDuplicateKeyUpdate()
                    .set(t.TOUCHED_AT, excluded(t.TOUCHED_AT))
                    .set(t.STAGE, excluded(t.STAGE))
                    .set(t.ERROR_CLASS, excluded(t.ERROR_CLASS))
                    .set(t.MESSAGE, excluded(t.MESSAGE))
                    .set(t.SOURCE_NAME, excluded(t.SOURCE_NAME))
                    .set(t.SOURCE_LINE, excluded(t.SOURCE_LINE))
                    .set(t.SOURCE_COLUMN, excluded(t.SOURCE_COLUMN)));
        }
    }

    /**
     * The stages' rows this reading did not write.
     *
     * <p>Scoped to the stages the caller owns rather than to what it wrote, which is the difference
     * that matters: a stage with nothing to say this reading has to clear last reading's rows, and
     * a stage that wrote nothing appears nowhere in the rows above. Naming the stages is also what
     * keeps one writer from reclaiming another's, so the two faces can be called independently and
     * in either order.
     */
    private static void sweep(DSLContext dsl, String graph, List<String> stages,
                              LocalDateTime touchedAt) {
        var t = GRAPHQL_SCHEMA_PROBLEM;
        dsl.deleteFrom(t)
            .where(t.GRAPH_NAME.eq(graph))
            .and(t.STAGE.in(stages))
            .and(t.TOUCHED_AT.ne(touchedAt))
            .execute();
    }

    /**
     * A rejected source as a row. The file is taken from the reader rather than from the location,
     * the parser having reported no location for some of what it refuses, and the message is the
     * parser's own rather than the attributed one-liner, which repeats coordinates these columns
     * already carry.
     */
    private static Problem parseProblem(SchemaLoader.SyntaxFailure failure) {
        var at = failure.location();
        return new Problem(PARSE_STAGE, failure.cause().getClass().getSimpleName(),
            failure.verbatimMessage(), failure.sourceName(), line(at), column(at));
    }

    /** A refusal from either document-wide stage as a row, its stage named by its own enum. */
    private static Problem stageProblem(SchemaError error) {
        var at = error.location();
        return new Problem(error.stage().name(), error.errorClass(), error.message(),
            at == null ? null : at.getSourceName(), line(at), column(at));
    }

    private static Integer line(SourceLocation at) {
        return at == null ? null : at.getLine();
    }

    private static Integer column(SourceLocation at) {
        return at == null ? null : at.getColumn();
    }
}
