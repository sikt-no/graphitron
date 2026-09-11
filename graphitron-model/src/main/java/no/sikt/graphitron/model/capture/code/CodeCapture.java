package no.sikt.graphitron.model.capture.code;

import no.sikt.graphitron.model.classpath.ScalarConstantInput;
import no.sikt.graphitron.model.config.ClasspathEntry;
import no.sikt.graphitron.model.sink.RowChunks;
import org.jooq.DSLContext;
import org.jooq.Rows;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static no.sikt.graphitron.model.Tables.CODE_SCALAR_CONSTANT;
import static no.sikt.graphitron.model.Tables.STORE_SOURCE;
import static org.jooq.impl.DSL.excluded;
import static org.jooq.impl.DSL.val;

/**
 * Writes what a schema may name in Java: one arm per directive that takes a Java reference, each
 * admitting the members that directive can take.
 *
 * <p>This is not a census with a filter on top. A census answers what the classpath holds, so its
 * one scope rule has to serve every reader and serves none of them exactly; the classfile census
 * this replaces was read by nobody for that reason, the generator resolving its own names
 * reflectively instead. An arm answers what may be written at one coordinate, and the predicate
 * that decides it is the arm's own.
 *
 * <p>One arm so far, the scalar constants. The rest are named in this family's charter: five over
 * the reactor, for what a consumer writes at {@code @service}, {@code @condition},
 * {@code @externalField}, {@code @enum} and a reference's condition, and one more over the whole
 * classpath for the throwables an {@code @error} handler names. Arguments, return types and
 * declared exceptions get no arm of their own, being facts about a method, and a method has
 * exactly one arm.
 *
 * <p>Keyed and swept on the classpath entry, because an entry is shared: several graphs may read
 * one, and a reading replaces the rows of the entries it read and no others.
 */
public final class CodeCapture {

    /** The declared type a constant must have to be nameable in {@code @scalarType(scalar:)}. */
    private static final String SCALAR_TYPE = "graphql.schema.GraphQLScalarType";

    private CodeCapture() {}

    /**
     * Makes the code rows of {@code entries} be what their classfiles now declare.
     *
     * @param entries    the classpath as its producer classified them; each carries how it reached
     *                   the classpath, which a bare path could not say
     * @param skipPrefix a package to leave out, or null to leave nothing out
     * @param loader     the run's codegen classloader, for the one fact a classfile cannot answer;
     *                   null falls back to the thread's, which is what a caller with no catalog has
     */
    public static void capture(DSLContext dsl, List<ClasspathEntry> entries, String skipPrefix,
                               ClassLoader loader, LocalDateTime touchedAt) {
        var census = ClassfileCensus.read(entries, skipPrefix);
        if (census.entries().isEmpty()) {
            return;
        }
        sources(dsl, census.entries(), touchedAt);
        scalarConstants(dsl, census.classes(), loader, touchedAt);
        // Every entry read, not only the ones that declared something, so an entry that stopped
        // declaring a constant loses its row.
        sweep(dsl, census.entries().stream().map(ClassfileCensus.EntryAt::source).toList(),
            touchedAt);
    }

    /** The registry rows every code row hangs its source on, one per classpath entry. */
    private static void sources(DSLContext dsl, List<ClassfileCensus.EntryAt> entries,
                                LocalDateTime touchedAt) {
        var t = STORE_SOURCE;
        var rows = entries.stream().collect(Rows.toRowList(
            at -> val(at.source(), t.SOURCE_NAME),
            at -> val(at.kind(), t.SOURCE_KIND),
            at -> val(at.origin(), t.ORIGIN),
            at -> val(at.coordinate(), t.COORDINATE),
            at -> val(touchedAt, t.LAST_SEEN),
            at -> val(touchedAt, t.READ_AT)));
        RowChunks.execute(rows, chunk ->
            dsl.insertInto(t, t.SOURCE_NAME, t.SOURCE_KIND, t.ORIGIN, t.COORDINATE, t.LAST_SEEN,
                    t.READ_AT)
                .valuesOfRows(chunk)
                .onDuplicateKeyUpdate()
                .set(t.ORIGIN, excluded(t.ORIGIN))
                .set(t.COORDINATE, excluded(t.COORDINATE))
                .set(t.LAST_SEEN, excluded(t.LAST_SEEN))
                .set(t.READ_AT, excluded(t.READ_AT)));
    }

    /**
     * The constants {@code @scalarType(scalar:)} may name: a public static field whose declared
     * type is exactly {@code GraphQLScalarType}.
     *
     * <p>Exactly, rather than assignable to it: the reference an author writes is resolved by
     * reading the field, and a field declared as a supertype would admit a value that is not one.
     * The admission is read from the classfile; the input type beside it cannot be, the coercing
     * being a live object rather than a signature, so that column alone costs a class load and is
     * null wherever the load or the read does not reach one.
     */
    private static void scalarConstants(DSLContext dsl, List<ClassfileCensus.ClassAt> classes,
                                        ClassLoader loader, LocalDateTime touchedAt) {
        record Constant(String source, String className, String fieldName) {}
        var found = new ArrayList<Constant>();
        for (ClassfileCensus.ClassAt at : classes) {
            for (ClassfileCensus.FieldAt field : at.fields()) {
                if (field.isStatic() && SCALAR_TYPE.equals(field.type())) {
                    found.add(new Constant(at.source(), at.className(), field.name()));
                }
            }
        }
        if (found.isEmpty()) {
            return;
        }
        var t = CODE_SCALAR_CONSTANT;
        var rows = found.stream().collect(Rows.toRowList(
            c -> val(c.source(), t.SOURCE_NAME),
            c -> val(c.className(), t.CLASS_NAME),
            c -> val(c.fieldName(), t.FIELD_NAME),
            c -> val(ScalarConstantInput.of(c.className(), c.fieldName(), loader), t.INPUT_TYPE),
            c -> val(touchedAt, t.TOUCHED_AT)));
        RowChunks.execute(rows, chunk ->
            dsl.insertInto(t, t.SOURCE_NAME, t.CLASS_NAME, t.FIELD_NAME, t.INPUT_TYPE, t.TOUCHED_AT)
                .valuesOfRows(chunk)
                .onDuplicateKeyUpdate()
                .set(t.INPUT_TYPE, excluded(t.INPUT_TYPE))
                .set(t.TOUCHED_AT, excluded(t.TOUCHED_AT)));
    }

    /**
     * Deletes the rows of the entries this reading read that it did not touch, which are the
     * constants those entries no longer declare.
     */
    private static void sweep(DSLContext dsl, List<String> sources, LocalDateTime touchedAt) {
        var t = CODE_SCALAR_CONSTANT;
        dsl.deleteFrom(t)
            .where(t.SOURCE_NAME.in(sources))
            .and(t.TOUCHED_AT.ne(touchedAt))
            .execute();
    }
}
