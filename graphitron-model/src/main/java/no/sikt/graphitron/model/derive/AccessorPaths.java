package no.sikt.graphitron.model.derive;

import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Select;

import java.time.LocalDateTime;

import static no.sikt.graphitron.model.Tables.CODE_CLASS;
import static no.sikt.graphitron.model.Tables.CODE_READ_SLOT;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_ACCESSOR_PATH;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_ACCESSOR_PATH_STEP;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_FIELD;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_PRODUCER_LIFT_CANDIDATE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_PATH;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_PATH_NEED_OBJECT;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_TYPE_BACKING;
import static no.sikt.graphitron.model.Tables.STORE_GRAPH_SOURCE;
import static org.jooq.impl.DSL.castNull;
import static org.jooq.impl.DSL.coalesce;
import static org.jooq.impl.DSL.excluded;
import static org.jooq.impl.DSL.exists;
import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.inline;
import static org.jooq.impl.DSL.min;
import static org.jooq.impl.DSL.notExists;
import static org.jooq.impl.DSL.select;
import static org.jooq.impl.DSL.selectCount;
import static org.jooq.impl.DSL.selectDistinct;
import static org.jooq.impl.DSL.selectOne;
import static org.jooq.impl.DSL.val;

/**
 * The writer of the accessor paths: how each need that starts on an object is met, by the members it
 * calls on that object, a depth at a time.
 *
 * <p>A step of {@link OccurrenceDepths}, after the depth's needs and before the next depth's
 * sources, a child's object source being where its parent's accessor path ends. Two rules. The
 * field's bound name finds exactly one member on the start class in the graph's classpath, and the
 * path calls it, origin {@code INFERRED}, reaching the class it delivers. Or it finds none and the
 * field's type is backed by the start class, and the object passes through, origin {@code EMPTY},
 * reaching its start. A producer taking its keys through a member, its one candidate in
 * {@link OccurrenceLifts}, lifts onto a table there and its need has no accessor path. Marked here and swept by {@link Paths}, whose
 * sweep takes every inferred and empty path a reading did not mark, the subtypes going with them.
 */
public final class AccessorPaths {

    private AccessorPaths() {}

    private static final String ACCESSOR = "ACCESSOR";
    private static final String INFERRED = "INFERRED";
    private static final String EMPTY = "EMPTY";

    /** States the accessor paths meeting the object needs of the occurrences at {@code depth}. */
    public static void writeDepth(DSLContext dsl, String graph, int depth, LocalDateTime touchedAt) {
        var c = member(graph, depth, touchedAt).unionAll(passesThrough(graph, depth, touchedAt))
            .asTable("c");
        Field<String> cGraph = c.field("graph_name", String.class);
        Field<String> cCoordinate = c.field("coordinate", String.class);
        Field<String> cTarget = c.field("target_type", String.class);
        Field<String> cOrigin = c.field("origin", String.class);
        Field<String> cClass = c.field("target_class", String.class);

        var p = GRAPHITRON_PATH;
        dsl.insertInto(p)
            .columns(p.GRAPH_NAME, p.COORDINATE, p.TARGET_TYPE, p.KIND, p.ORIGIN, p.TOUCHED_AT)
            .select(selectDistinct(cGraph, cCoordinate, cTarget, inline(ACCESSOR), cOrigin,
                    val(touchedAt, p.TOUCHED_AT))
                .from(c))
            .onConflict(p.GRAPH_NAME, p.COORDINATE, p.TARGET_TYPE)
            .doUpdate()
            .set(p.KIND, excluded(p.KIND))
            .set(p.ORIGIN, excluded(p.ORIGIN))
            .set(p.TOUCHED_AT, excluded(p.TOUCHED_AT))
            .execute();

        // The class reached is referenced in the graph's own classpath entries, the first by name
        // where two entries declare it.
        var a = GRAPHITRON_ACCESSOR_PATH;
        var cc = CODE_CLASS.as("cc");
        var ccAny = CODE_CLASS.as("cc_any");
        var cgs = STORE_GRAPH_SOURCE.as("cgs");
        dsl.insertInto(a)
            .columns(a.GRAPH_NAME, a.COORDINATE, a.TARGET_TYPE, a.KIND, a.TARGET_CLASS_NAME,
                a.TOUCHED_AT, a.TARGET_CLASS_SOURCE_REF, a.TARGET_CLASS_NAME_REF)
            .select(selectDistinct(cGraph, cCoordinate, cTarget, inline(ACCESSOR), cClass,
                    val(touchedAt, a.TOUCHED_AT), cc.SOURCE_NAME, cc.CLASS_NAME)
                .from(c)
                .leftJoin(cc).on(cc.CLASS_NAME.eq(cClass), cc.SOURCE_NAME.eq(field(
                    select(min(ccAny.SOURCE_NAME)).from(ccAny)
                        .join(cgs).on(cgs.SOURCE_NAME.eq(ccAny.SOURCE_NAME))
                        .where(cgs.GRAPH_NAME.eq(cGraph), ccAny.CLASS_NAME.eq(cClass))))))
            .onConflict(a.GRAPH_NAME, a.COORDINATE, a.TARGET_TYPE)
            .doUpdate()
            .set(a.TARGET_CLASS_NAME, excluded(a.TARGET_CLASS_NAME))
            .set(a.TOUCHED_AT, excluded(a.TOUCHED_AT))
            .set(a.TARGET_CLASS_SOURCE_REF, excluded(a.TARGET_CLASS_SOURCE_REF))
            .set(a.TARGET_CLASS_NAME_REF, excluded(a.TARGET_CLASS_NAME_REF))
            .execute();

        var s = GRAPHITRON_ACCESSOR_PATH_STEP;
        dsl.insertInto(s)
            .columns(s.GRAPH_NAME, s.COORDINATE, s.TARGET_TYPE, s.POSITION, s.SLOT_SOURCE_NAME,
                s.SLOT_CLASS_NAME, s.SLOT_METHOD_NAME, s.SLOT_DESCRIPTOR, s.TOUCHED_AT)
            .select(selectDistinct(cGraph, cCoordinate, cTarget, inline(0),
                    c.field("slot_source_name", String.class), c.field("slot_class_name", String.class),
                    c.field("slot_method_name", String.class), c.field("slot_descriptor", String.class),
                    val(touchedAt, s.TOUCHED_AT))
                .from(c)
                .where(cOrigin.eq(INFERRED)))
            .onConflict(s.GRAPH_NAME, s.COORDINATE, s.TARGET_TYPE, s.POSITION)
            .doUpdate()
            .set(s.SLOT_SOURCE_NAME, excluded(s.SLOT_SOURCE_NAME))
            .set(s.SLOT_CLASS_NAME, excluded(s.SLOT_CLASS_NAME))
            .set(s.SLOT_METHOD_NAME, excluded(s.SLOT_METHOD_NAME))
            .set(s.SLOT_DESCRIPTOR, excluded(s.SLOT_DESCRIPTOR))
            .set(s.TOUCHED_AT, excluded(s.TOUCHED_AT))
            .execute();
        // A step this reading no longer takes, its path having passed through instead.
        dsl.deleteFrom(s).where(s.GRAPH_NAME.eq(graph)).and(s.TOUCHED_AT.ne(touchedAt))
            .and(exists(selectOne().from(a).where(a.GRAPH_NAME.eq(s.GRAPH_NAME),
                a.COORDINATE.eq(s.COORDINATE), a.TARGET_TYPE.eq(s.TARGET_TYPE),
                a.TOUCHED_AT.eq(touchedAt))))
            .execute();
    }

    /** The one member the field's bound name finds on the start class. */
    private static Select<org.jooq.Record9<String, String, String, String, String, String, String, String, String>> member(String graph, int depth, LocalDateTime touchedAt) {
        var n = GRAPHITRON_PATH_NEED_OBJECT.as("n");
        var o = GRAPHITRON_OCCURRENCE.as("o");
        var f = GRAPHITRON_FIELD.as("f");
        var gs = STORE_GRAPH_SOURCE.as("gs");
        var sl = CODE_READ_SLOT.as("sl");
        var gs2 = STORE_GRAPH_SOURCE.as("gs2");
        var sl2 = CODE_READ_SLOT.as("sl2");
        var c = GRAPHITRON_OCCURRENCE_PRODUCER_LIFT_CANDIDATE.as("c");
        var c2 = GRAPHITRON_OCCURRENCE_PRODUCER_LIFT_CANDIDATE.as("c2");
        return select(n.GRAPH_NAME.as("graph_name"), n.COORDINATE.as("coordinate"),
                n.TARGET_TYPE.as("target_type"), inline(INFERRED).as("origin"),
                coalesce(sl.ELEMENT_CLASS, sl.ERASED_CLASS, sl.SLOT_TYPE).as("target_class"),
                sl.SOURCE_NAME.as("slot_source_name"), sl.CLASS_NAME.as("slot_class_name"),
                sl.METHOD_NAME.as("slot_method_name"), sl.DESCRIPTOR.as("slot_descriptor"))
            .from(n)
            .join(o).on(o.GRAPH_NAME.eq(n.GRAPH_NAME), o.COORDINATE.eq(n.COORDINATE))
            .join(f).on(f.GRAPH_NAME.eq(o.GRAPH_NAME), f.COORDINATE.eq(o.ELEMENT_COORDINATE))
            .join(gs).on(gs.GRAPH_NAME.eq(n.GRAPH_NAME))
            .join(sl).on(sl.SOURCE_NAME.eq(gs.SOURCE_NAME), sl.CLASS_NAME.eq(n.START_CLASS),
                sl.SLOT_NAME.eq(f.BOUND_NAME))
            .where(n.GRAPH_NAME.eq(graph), n.TOUCHED_AT.eq(touchedAt), o.DEPTH.eq(depth))
            .and(field(selectCount().from(gs2)
                .join(sl2).on(sl2.SOURCE_NAME.eq(gs2.SOURCE_NAME), sl2.CLASS_NAME.eq(n.START_CLASS),
                    sl2.SLOT_NAME.eq(f.BOUND_NAME))
                .where(gs2.GRAPH_NAME.eq(n.GRAPH_NAME))).eq(1))
            // A producer that takes its keys through its one candidate member reads them there; its
            // member is the lift's, not a path's.
            .and(notExists(selectOne().from(c)
                .where(c.GRAPH_NAME.eq(n.GRAPH_NAME), c.COORDINATE.eq(n.COORDINATE),
                    c.TOUCHED_AT.eq(touchedAt))
                .and(notExists(selectOne().from(c2)
                    .where(c2.GRAPH_NAME.eq(c.GRAPH_NAME), c2.COORDINATE.eq(c.COORDINATE),
                        c2.TOUCHED_AT.eq(touchedAt))
                    .and(c2.SLOT_SOURCE_NAME.ne(c.SLOT_SOURCE_NAME).or(c2.SLOT_CLASS_NAME.ne(c.SLOT_CLASS_NAME))
                        .or(c2.SLOT_METHOD_NAME.ne(c.SLOT_METHOD_NAME)).or(c2.SLOT_DESCRIPTOR.ne(c.SLOT_DESCRIPTOR)))))));
    }

    /** No member by the bound name, and the target type backed by the start class. */
    private static Select<org.jooq.Record9<String, String, String, String, String, String, String, String, String>> passesThrough(String graph, int depth, LocalDateTime touchedAt) {
        var n = GRAPHITRON_PATH_NEED_OBJECT.as("n");
        var o = GRAPHITRON_OCCURRENCE.as("o");
        var f = GRAPHITRON_FIELD.as("f");
        var gs = STORE_GRAPH_SOURCE.as("gs");
        var sl = CODE_READ_SLOT.as("sl");
        var tb = GRAPHITRON_TYPE_BACKING.as("tb");
        return select(n.GRAPH_NAME, n.COORDINATE, n.TARGET_TYPE, inline(EMPTY), n.START_CLASS,
                castNull(String.class), castNull(String.class), castNull(String.class),
                castNull(String.class))
            .from(n)
            .join(o).on(o.GRAPH_NAME.eq(n.GRAPH_NAME), o.COORDINATE.eq(n.COORDINATE))
            .join(f).on(f.GRAPH_NAME.eq(o.GRAPH_NAME), f.COORDINATE.eq(o.ELEMENT_COORDINATE))
            .where(n.GRAPH_NAME.eq(graph), n.TOUCHED_AT.eq(touchedAt), o.DEPTH.eq(depth))
            .and(notExists(selectOne().from(gs)
                .join(sl).on(sl.SOURCE_NAME.eq(gs.SOURCE_NAME), sl.CLASS_NAME.eq(n.START_CLASS),
                    sl.SLOT_NAME.eq(f.BOUND_NAME))
                .where(gs.GRAPH_NAME.eq(n.GRAPH_NAME))))
            .and(exists(selectOne().from(tb)
                .where(tb.GRAPH_NAME.eq(n.GRAPH_NAME), tb.TYPE_NAME.eq(n.TARGET_TYPE),
                    tb.CLASS_NAME.eq(n.START_CLASS))));
    }
}
