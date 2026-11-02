package no.sikt.graphitron.model.derive;

import org.jooq.DSLContext;
import org.jooq.Field;

import java.time.LocalDateTime;

import static no.sikt.graphitron.model.Tables.CODE_METHOD;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_CODE_REFERENCE_SITE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_ENTRY_DEFECT;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_ENTRY_DEFECT_METHOD;
import static no.sikt.graphitron.model.Tables.STORE_GRAPH_SOURCE;
import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.val;

/**
 * The declarations a {@code CONDITION_OVERLOAD_SHAPE_DISAGREES} defect is about, written under it in
 * {@code graphitron_entry_defect_method}: every public method of the name the {@code @condition}
 * names, in the graph's classpath. The defect itself is a row of the graphitron rule, which
 * {@link EntryDefects} writes just before this runs in the graphitron gatherer's anchor phase; this
 * reads the rows that reading marked and marks its own with the same instant, and
 * {@link EntryDefects#sweep} takes what the reading did not mark.
 */
public final class ConditionShapeDefectMethods {

    private ConditionShapeDefectMethods() {}

    /** The code whose declarations this writes. */
    public static final String CODE = "CONDITION_OVERLOAD_SHAPE_DISAGREES";

    /** Writes the declarations under each of the graph's shape defects the reading marked. */
    public static void derive(DSLContext dsl, String graph, LocalDateTime readAt) {
        var d = GRAPHITRON_ENTRY_DEFECT;
        var c = GRAPHITRON_CODE_REFERENCE_SITE;
        var g = STORE_GRAPH_SOURCE;
        var m = CODE_METHOD;
        var t = GRAPHITRON_ENTRY_DEFECT_METHOD;
        var source = dsl.selectDistinct(d.GRAPH_NAME.as("GRAPH_NAME"), d.SOURCE_NAME.as("SOURCE_NAME"),
                d.SOURCE_LINE.as("SOURCE_LINE"), d.SOURCE_COLUMN.as("SOURCE_COLUMN"),
                d.CODE.as("CODE"), m.SOURCE_NAME.as("METHOD_SOURCE_NAME"),
                m.CLASS_NAME.as("METHOD_CLASS_NAME"), m.METHOD_NAME.as("METHOD_NAME"),
                m.DESCRIPTOR.as("METHOD_DESCRIPTOR"))
            .from(d)
            .join(c).on(c.GRAPH_NAME.eq(d.GRAPH_NAME), c.SOURCE_NAME.eq(d.SOURCE_NAME),
                c.SOURCE_LINE.eq(d.SOURCE_LINE), c.SOURCE_COLUMN.eq(d.SOURCE_COLUMN))
            .join(g).on(g.GRAPH_NAME.eq(c.GRAPH_NAME))
            .join(m).on(m.SOURCE_NAME.eq(g.SOURCE_NAME), m.CLASS_NAME.eq(c.CLASS_NAME),
                m.METHOD_NAME.eq(c.METHOD_NAME))
            .where(d.GRAPH_NAME.eq(graph), d.CODE.eq(CODE), d.TOUCHED_AT.eq(readAt))
            .asTable("s");
        Field<String> graphName = column(source, "GRAPH_NAME");
        Field<String> file = column(source, "SOURCE_NAME");
        Field<Integer> line = field(name("s", "SOURCE_LINE"), Integer.class);
        Field<Integer> col = field(name("s", "SOURCE_COLUMN"), Integer.class);
        Field<String> code = column(source, "CODE");
        Field<String> methodSource = column(source, "METHOD_SOURCE_NAME");
        Field<String> methodClass = column(source, "METHOD_CLASS_NAME");
        Field<String> methodName = column(source, "METHOD_NAME");
        Field<String> descriptor = column(source, "METHOD_DESCRIPTOR");
        dsl.mergeInto(t).using(source)
            .on(t.GRAPH_NAME.eq(graphName), t.SOURCE_NAME.eq(file), t.SOURCE_LINE.eq(line),
                t.SOURCE_COLUMN.eq(col), t.CODE.eq(code), t.METHOD_SOURCE_NAME.eq(methodSource),
                t.METHOD_CLASS_NAME.eq(methodClass), t.METHOD_NAME.eq(methodName),
                t.METHOD_DESCRIPTOR.eq(descriptor))
            .whenMatchedThenUpdate()
            .set(t.TOUCHED_AT, val(readAt, t.TOUCHED_AT))
            .whenNotMatchedThenInsert(t.GRAPH_NAME, t.SOURCE_NAME, t.SOURCE_LINE, t.SOURCE_COLUMN,
                t.CODE, t.METHOD_SOURCE_NAME, t.METHOD_CLASS_NAME, t.METHOD_NAME,
                t.METHOD_DESCRIPTOR, t.TOUCHED_AT)
            .values(graphName, file, line, col, code, methodSource, methodClass, methodName,
                descriptor, val(readAt, t.TOUCHED_AT))
            .execute();
    }

    private static Field<String> column(org.jooq.Table<?> t, String column) {
        return field(name(t.getName(), column), String.class);
    }
}
