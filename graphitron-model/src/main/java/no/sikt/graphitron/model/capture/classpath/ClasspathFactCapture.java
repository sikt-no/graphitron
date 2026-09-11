package no.sikt.graphitron.model.capture.classpath;

import no.sikt.graphitron.model.sink.RowChunks;
import org.jooq.DSLContext;
import org.jooq.Rows;
import org.jooq.Table;

import no.sikt.graphitron.model.config.ClasspathEntry;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import static no.sikt.graphitron.model.Tables.JVM_CLASSFILE;
import static no.sikt.graphitron.model.Tables.JVM_CLASSFILE_FIELD;
import static no.sikt.graphitron.model.Tables.JVM_CLASSFILE_METHOD;
import static no.sikt.graphitron.model.Tables.JVM_CLASSFILE_PARAMETER;
import static no.sikt.graphitron.model.Tables.JVM_CLASSFILE_RECORD_COMPONENT;
import static no.sikt.graphitron.model.Tables.JVM_CLASSFILE_SUPERTYPE;
import static no.sikt.graphitron.model.Tables.STORE_SOURCE;
import static org.jooq.impl.DSL.excluded;
import static org.jooq.impl.DSL.val;

/**
 * Writes what the classfiles on a classpath declare.
 *
 * <p>Keyed and swept on the classpath entry, because an entry is shared: several graphs may read
 * one, and a reading replaces the rows of the entries it read and no others.
 *
 * <p>Which entries to read is the caller's, so this gatherer holds no rule about what a consumer
 * may name and no rule about which dependency is nameable.
 */
public final class ClasspathFactCapture {

    private ClasspathFactCapture() {}

    /**
     * Makes the census rows of {@code entries} be what their classfiles now declare.
     *
     * @param entries    the classpath as its producer classified it, each carrying how it
     *                   reached the classpath; a bare path could not say
     * @param skipPrefix a package to leave out of the census, or null to leave nothing out
     */
    public static void capture(DSLContext dsl, List<ClasspathEntry> entries, String skipPrefix,
                               LocalDateTime touchedAt) {
        var census = ClassfileCensus.read(entries, skipPrefix);
        if (census.entries().isEmpty()) {
            return;
        }
        var classes = census.classes();
        // Parents before children, which the sweep then takes in reverse.
        sources(dsl, census.entries(), touchedAt);
        classes(dsl, classes, touchedAt);
        supertypes(dsl, classes, touchedAt);
        methods(dsl, classes, touchedAt);
        parameters(dsl, classes, touchedAt);
        recordComponents(dsl, classes, touchedAt);
        fields(dsl, classes, touchedAt);
        // Every entry read, not only the ones that declared something, so an entry emptied since
        // the last reading loses its rows.
        sweep(dsl, census.entries().stream().map(ClassfileCensus.EntryAt::source).toList(),
            touchedAt);
    }

    /** A class paired with one thing it declares. */
    private record Declared<T>(ClassfileCensus.ClassAt at, T what) {}

    /** A method's parameter, which needs the method as well as the class it is on. */
    private record InMethod(ClassfileCensus.ClassAt at, ClassfileCensus.MethodAt method,
                            ClassfileCensus.ParameterAt parameter) {}

    /** The registry rows every census row hangs its source on, one per classpath entry. */
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

    private static void classes(DSLContext dsl, List<ClassfileCensus.ClassAt> classes,
                                LocalDateTime touchedAt) {
        var t = JVM_CLASSFILE;
        var rows = classes.stream().collect(Rows.toRowList(
            at -> val(at.source(), t.SOURCE_NAME),
            at -> val(at.className(), t.CLASS_NAME),
            at -> val(at.kind(), t.CLASS_KIND),
            at -> val(touchedAt, t.TOUCHED_AT)));
        RowChunks.execute(rows, chunk ->
            dsl.insertInto(t, t.SOURCE_NAME, t.CLASS_NAME, t.CLASS_KIND, t.TOUCHED_AT)
                .valuesOfRows(chunk)
                .onDuplicateKeyUpdate()
                .set(t.CLASS_KIND, excluded(t.CLASS_KIND))
                .set(t.TOUCHED_AT, excluded(t.TOUCHED_AT)));
    }

    private static void supertypes(DSLContext dsl, List<ClassfileCensus.ClassAt> classes,
                                   LocalDateTime touchedAt) {
        var t = JVM_CLASSFILE_SUPERTYPE;
        var rows = flatten(classes, ClassfileCensus.ClassAt::supertypes).stream()
            .collect(Rows.toRowList(
                d -> val(d.at().source(), t.SOURCE_NAME),
                d -> val(d.at().className(), t.CLASS_NAME),
                d -> val(d.what().name(), t.SUPERTYPE_NAME),
                d -> val(d.what().declaredVia(), t.DECLARED_VIA),
                d -> val(touchedAt, t.TOUCHED_AT)));
        RowChunks.execute(rows, chunk ->
            dsl.insertInto(t, t.SOURCE_NAME, t.CLASS_NAME, t.SUPERTYPE_NAME, t.DECLARED_VIA,
                    t.TOUCHED_AT)
                .valuesOfRows(chunk)
                .onDuplicateKeyUpdate()
                .set(t.DECLARED_VIA, excluded(t.DECLARED_VIA))
                .set(t.TOUCHED_AT, excluded(t.TOUCHED_AT)));
    }

    private static void methods(DSLContext dsl, List<ClassfileCensus.ClassAt> classes,
                                LocalDateTime touchedAt) {
        var t = JVM_CLASSFILE_METHOD;
        var rows = flatten(classes, ClassfileCensus.ClassAt::methods).stream()
            .collect(Rows.toRowList(
                d -> val(d.at().source(), t.SOURCE_NAME),
                d -> val(d.at().className(), t.CLASS_NAME),
                d -> val(d.what().name(), t.METHOD_NAME),
                d -> val(d.what().descriptor(), t.DESCRIPTOR),
                d -> val(d.what().returnType(), t.RETURN_TYPE),
                d -> val(touchedAt, t.TOUCHED_AT)));
        RowChunks.execute(rows, chunk ->
            dsl.insertInto(t, t.SOURCE_NAME, t.CLASS_NAME, t.METHOD_NAME, t.DESCRIPTOR,
                    t.RETURN_TYPE, t.TOUCHED_AT)
                .valuesOfRows(chunk)
                .onDuplicateKeyUpdate()
                .set(t.RETURN_TYPE, excluded(t.RETURN_TYPE))
                .set(t.TOUCHED_AT, excluded(t.TOUCHED_AT)));
    }

    private static void parameters(DSLContext dsl, List<ClassfileCensus.ClassAt> classes,
                                   LocalDateTime touchedAt) {
        var t = JVM_CLASSFILE_PARAMETER;
        var declared = new ArrayList<InMethod>();
        for (var at : classes) {
            for (var method : at.methods()) {
                method.parameters().forEach(p -> declared.add(new InMethod(at, method, p)));
            }
        }
        var rows = declared.stream().collect(Rows.toRowList(
            d -> val(d.at().source(), t.SOURCE_NAME),
            d -> val(d.at().className(), t.CLASS_NAME),
            d -> val(d.method().name(), t.METHOD_NAME),
            d -> val(d.method().descriptor(), t.DESCRIPTOR),
            d -> val(d.parameter().position(), t.POSITION),
            d -> val(d.parameter().name(), t.PARAMETER_NAME),
            d -> val(d.parameter().type(), t.PARAMETER_TYPE),
            d -> val(touchedAt, t.TOUCHED_AT)));
        RowChunks.execute(rows, chunk ->
            dsl.insertInto(t, t.SOURCE_NAME, t.CLASS_NAME, t.METHOD_NAME, t.DESCRIPTOR, t.POSITION,
                    t.PARAMETER_NAME, t.PARAMETER_TYPE, t.TOUCHED_AT)
                .valuesOfRows(chunk)
                .onDuplicateKeyUpdate()
                .set(t.PARAMETER_NAME, excluded(t.PARAMETER_NAME))
                .set(t.PARAMETER_TYPE, excluded(t.PARAMETER_TYPE))
                .set(t.TOUCHED_AT, excluded(t.TOUCHED_AT)));
    }

    private static void recordComponents(DSLContext dsl, List<ClassfileCensus.ClassAt> classes,
                                         LocalDateTime touchedAt) {
        var t = JVM_CLASSFILE_RECORD_COMPONENT;
        var rows = flatten(classes, ClassfileCensus.ClassAt::components).stream()
            .collect(Rows.toRowList(
                d -> val(d.at().source(), t.SOURCE_NAME),
                d -> val(d.at().className(), t.CLASS_NAME),
                d -> val(d.what().name(), t.COMPONENT_NAME),
                d -> val(d.what().position(), t.POSITION),
                d -> val(d.what().type(), t.COMPONENT_TYPE),
                d -> val(touchedAt, t.TOUCHED_AT)));
        RowChunks.execute(rows, chunk ->
            dsl.insertInto(t, t.SOURCE_NAME, t.CLASS_NAME, t.COMPONENT_NAME, t.POSITION,
                    t.COMPONENT_TYPE, t.TOUCHED_AT)
                .valuesOfRows(chunk)
                .onDuplicateKeyUpdate()
                .set(t.POSITION, excluded(t.POSITION))
                .set(t.COMPONENT_TYPE, excluded(t.COMPONENT_TYPE))
                .set(t.TOUCHED_AT, excluded(t.TOUCHED_AT)));
    }

    private static void fields(DSLContext dsl, List<ClassfileCensus.ClassAt> classes,
                               LocalDateTime touchedAt) {
        var t = JVM_CLASSFILE_FIELD;
        var rows = flatten(classes, ClassfileCensus.ClassAt::fields).stream()
            .collect(Rows.toRowList(
                d -> val(d.at().source(), t.SOURCE_NAME),
                d -> val(d.at().className(), t.CLASS_NAME),
                d -> val(d.what().name(), t.FIELD_NAME),
                d -> val(d.what().type(), t.FIELD_TYPE),
                d -> val(d.what().isStatic(), t.IS_STATIC),
                d -> val(touchedAt, t.TOUCHED_AT)));
        RowChunks.execute(rows, chunk ->
            dsl.insertInto(t, t.SOURCE_NAME, t.CLASS_NAME, t.FIELD_NAME, t.FIELD_TYPE, t.IS_STATIC,
                    t.TOUCHED_AT)
                .valuesOfRows(chunk)
                .onDuplicateKeyUpdate()
                .set(t.FIELD_TYPE, excluded(t.FIELD_TYPE))
                .set(t.IS_STATIC, excluded(t.IS_STATIC))
                .set(t.TOUCHED_AT, excluded(t.TOUCHED_AT)));
    }

    /** Every class paired with each of the things {@code of} reads off it. */
    private static <T> List<Declared<T>> flatten(List<ClassfileCensus.ClassAt> classes,
                                                 Function<ClassfileCensus.ClassAt, List<T>> of) {
        var declared = new ArrayList<Declared<T>>();
        classes.forEach(at -> of.apply(at).forEach(what -> declared.add(new Declared<>(at, what))));
        return declared;
    }

    /**
     * Every relation this gatherer writes, parents first. Listed rather than found by prefix: a
     * relation added above and not here would keep its stale rows silently.
     */
    private static final List<Table<?>> TABLES_TO_SWEEP = List.of(
        JVM_CLASSFILE, JVM_CLASSFILE_SUPERTYPE, JVM_CLASSFILE_METHOD, JVM_CLASSFILE_PARAMETER,
        JVM_CLASSFILE_RECORD_COMPONENT, JVM_CLASSFILE_FIELD);

    /**
     * Deletes the rows of the entries this reading read that it did not touch, which are the
     * classes and members those entries no longer declare.
     *
     * <p>Reversed, because these relations reference one another: a class deleted before its
     * methods is a foreign key violation.
     */
    private static void sweep(DSLContext dsl, List<String> sources, LocalDateTime touchedAt) {
        for (Table<?> table : TABLES_TO_SWEEP.reversed()) {
            // Table.field(Field) is a lookup by name returning this table's own typed column.
            var named = JVM_CLASSFILE;
            dsl.deleteFrom(table)
                .where(table.field(named.SOURCE_NAME).in(sources))
                .and(table.field(named.TOUCHED_AT).ne(touchedAt))
                .execute();
        }
    }
}
