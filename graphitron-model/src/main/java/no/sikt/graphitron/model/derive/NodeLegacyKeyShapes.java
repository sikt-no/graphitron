package no.sikt.graphitron.model.derive;

import org.jooq.DSLContext;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_NODE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_NODE_KEYCOLUMN;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_NODE_LEGACYKEYCOLUMN_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_NODE_LEGACY_KEYCOLUMN;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_NODE_LEGACY_KEY_DEFECT;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_TABLETYPE;
import static no.sikt.graphitron.model.Tables.SQL_COLUMN;
import static no.sikt.graphitron.model.Tables.SQL_NODE_KEY_COLUMN;
import static no.sikt.graphitron.model.Tables.SQL_NODE_LEGACY_KEY_COLUMN;
import static no.sikt.graphitron.model.Tables.SQL_NODE_METADATA;
import static org.jooq.impl.DSL.notExists;
import static org.jooq.impl.DSL.selectOne;

/**
 * The capture-cadence writer of {@code graphitron_node_legacy_keycolumn} and
 * {@code graphitron_node_legacy_key_defect}: the legacy key shape each wire type id answers to, and
 * the rules a stated shape breaks.
 *
 * <p>A legacy key shape is the column list a type's ids were encoded from before its key was
 * narrowed; the generated runtime converts an id in that shape to the current one before reading
 * it. It is keyed by type id because the conversion runs where an id is decoded and knows only the
 * type id it expects.
 *
 * <p>The contributors to a type id are every node type, its current key from
 * {@code graphitron_node_keycolumn}, and every table-bound non-node type over a table with usable
 * node metadata, its type id and current key from the table class: the population an id can be
 * decoded for. A node type states its shape on {@code @node(legacyKeyColumns:)}; failing that, and
 * for every other contributor, the bound table class's {@code __NODE_LEGACY_KEY_COLUMNS} states it.
 * Where both state one they must be the same columns in the same order, since the order is the wire
 * format of ids already issued.
 *
 * <p>The rules are evaluated once, here, and both halves are written from that one evaluation: a
 * type id is anchored, all positions or none, exactly when it has a stated shape and none of its
 * contributors broke a rule, and each broken rule is a defect row the rule view places at the
 * {@code @node} or {@code @table} the author edits. A stated shape must resolve every spelling to one
 * column of the bound table, name no column twice, cover the current key, and be longer than it (a
 * shape of the current key's length holding its columns is a reordering, which a conversion cannot
 * tell apart by arity). A type id two tables or two current keys answer to cannot carry one, since
 * the conversion would rewrite ids the other owns.
 *
 * <p>Runs as a stage of the graphitron gatherer after {@link NodeKeyColumns}, whose rows the node
 * contributors' current keys are.
 */
public final class NodeLegacyKeyShapes {

    private NodeLegacyKeyShapes() {}

    /** Where a defect is shown: the {@code @node} that stated the shape, or the type's {@code @table}. */
    private enum Site { NODE, TABLE }

    private record TableKey(String source, String schema, String name) {}

    /** One type an id of {@code typeId} can be decoded for, with its current key in key order. */
    private record Contributor(String typeName, String typeId, TableKey table, List<String> currentKey) {}

    /** A stated shape resolved to the bound table's columns, in the order it was stated. */
    private record Shape(List<String> columns, String origin) {}

    private record Defect(String typeName, String code, String detail, Site site) {}

    /** Clears and re-derives the graph's legacy key shapes and their defects; see the class javadoc. */
    public static void derive(DSLContext dsl, String graphName) {
        dsl.deleteFrom(GRAPHITRON_NODE_LEGACY_KEYCOLUMN)
            .where(GRAPHITRON_NODE_LEGACY_KEYCOLUMN.GRAPH_NAME.eq(graphName)).execute();
        dsl.deleteFrom(GRAPHITRON_NODE_LEGACY_KEY_DEFECT)
            .where(GRAPHITRON_NODE_LEGACY_KEY_DEFECT.GRAPH_NAME.eq(graphName)).execute();

        var written = writtenLists(dsl, graphName);
        var published = publishedLists(dsl);
        var contributors = contributors(dsl, graphName);

        var defects = new ArrayList<Defect>();
        var shapes = new LinkedHashMap<String, Map<Contributor, Shape>>();
        var byTypeId = new LinkedHashMap<String, List<Contributor>>();
        for (var c : contributors) {
            byTypeId.computeIfAbsent(c.typeId(), k -> new ArrayList<>()).add(c);
            stated(dsl, c, written.get(c.typeName()), published.get(c.table()), defects)
                .ifPresent(shape -> shapes.computeIfAbsent(c.typeId(), k -> new LinkedHashMap<>())
                    .put(c, shape));
        }

        for (var entry : byTypeId.entrySet()) {
            var members = entry.getValue();
            boolean stating = members.stream().anyMatch(c -> written.containsKey(c.typeName())
                || published.containsKey(c.table()));
            if (!stating) {
                continue;
            }
            var first = members.getFirst();
            boolean shared = members.stream().anyMatch(c -> !c.table().equals(first.table())
                || !upper(c.currentKey()).equals(upper(first.currentKey())));
            var stated = shapes.getOrDefault(entry.getKey(), Map.of());
            boolean disagreeing = stated.values().stream().map(s -> upper(s.columns())).distinct().count() > 1;
            if (shared || disagreeing) {
                for (var c : members) {
                    defects.add(new Defect(c.typeName(), "NODE_LEGACY_TYPE_ID_SHARED", c.typeId(), Site.TABLE));
                }
            }
        }

        var failed = new HashSet<String>();
        for (var d : defects) {
            contributors.stream().filter(c -> c.typeName().equals(d.typeName()))
                .forEach(c -> failed.add(c.typeId()));
        }
        for (var entry : shapes.entrySet()) {
            if (failed.contains(entry.getKey()) || entry.getValue().isEmpty()) {
                continue;
            }
            var c = entry.getValue().keySet().iterator().next();
            anchor(dsl, graphName, c, entry.getValue().get(c));
        }
        record(dsl, graphName, defects);
    }

    /**
     * The shape {@code c} states, checked against every rule that is about one contributor; empty
     * where it states none or breaks one, the broken rule added to {@code defects}.
     */
    private static Optional<Shape> stated(DSLContext dsl, Contributor c, List<String> written,
                                          List<String> published, List<Defect> defects) {
        if (c.currentKey().isEmpty()) {
            return Optional.empty();
        }
        Shape shape;
        Site site;
        if (written != null) {
            site = Site.NODE;
            var columns = new ArrayList<String>();
            for (var spelling : written) {
                var column = resolve(dsl, c.table(), spelling);
                if (column.isEmpty()) {
                    defects.add(new Defect(c.typeName(), "NODE_LEGACY_KEY_COLUMN_UNRESOLVED", spelling, site));
                    return Optional.empty();
                }
                columns.add(column.get());
            }
            if (published != null && !upper(published).equals(upper(columns))) {
                defects.add(new Defect(c.typeName(), "NODE_LEGACY_KEY_DISAGREES_WITH_METADATA",
                    String.join(", ", published), site));
                return Optional.empty();
            }
            shape = new Shape(List.copyOf(columns), "SDL_PINNED");
        } else if (published != null) {
            site = Site.TABLE;
            shape = new Shape(published, "JOOQ_METADATA");
        } else {
            return Optional.empty();
        }

        var seen = new HashSet<String>();
        for (var column : shape.columns()) {
            if (!seen.add(column.toUpperCase(Locale.ROOT))) {
                defects.add(new Defect(c.typeName(), "NODE_LEGACY_KEY_REPEATS_COLUMN", column, site));
                return Optional.empty();
            }
        }
        for (var column : c.currentKey()) {
            if (!seen.contains(column.toUpperCase(Locale.ROOT))) {
                defects.add(new Defect(c.typeName(), "NODE_LEGACY_KEY_MISSES_KEY_COLUMN", column, site));
                return Optional.empty();
            }
        }
        if (shape.columns().size() == c.currentKey().size()) {
            defects.add(new Defect(c.typeName(), "NODE_LEGACY_KEY_REORDERS_KEY",
                String.join(", ", shape.columns()), site));
            return Optional.empty();
        }
        return Optional.of(shape);
    }

    /**
     * The one column of the table a spelling names, folded against its SQL name and its generated
     * field name alike, on {@link NodeKeyColumns}' terms; empty where none or two answer.
     */
    private static Optional<String> resolve(DSLContext dsl, TableKey table, String spelling) {
        var c = SQL_COLUMN;
        String folded = spelling.toUpperCase(Locale.ROOT);
        var matches = dsl.select(c.COLUMN_NAME).from(c)
            .where(c.SOURCE_NAME.eq(table.source()), c.TABLE_SCHEMA.eq(table.schema()),
                c.TABLE_NAME.eq(table.name()))
            .and(c.COLUMN_NAME_UPPER.eq(folded).or(c.JOOQ_NAME_UPPER.eq(folded)))
            .fetch(c.COLUMN_NAME);
        return matches.size() == 1 ? Optional.of(matches.getFirst()) : Optional.empty();
    }

    /** Every node type and every table-bound non-node type over a table publishing usable metadata. */
    private static List<Contributor> contributors(DSLContext dsl, String graphName) {
        var out = new ArrayList<Contributor>();
        var n = GRAPHITRON_NODE;
        var b = GRAPHITRON_TABLETYPE;
        var k = GRAPHITRON_NODE_KEYCOLUMN;
        dsl.select(n.TYPE_NAME, n.TYPE_ID, b.TABLE_SOURCE_NAME, b.TABLE_SCHEMA, b.TABLE_NAME)
            .from(n)
            .join(b).on(b.GRAPH_NAME.eq(n.GRAPH_NAME), b.TYPE_NAME.eq(n.TYPE_NAME))
            .where(n.GRAPH_NAME.eq(graphName))
            .orderBy(n.TYPE_NAME)
            .forEach(r -> out.add(new Contributor(r.value1(), r.value2(),
                new TableKey(r.value3(), r.value4(), r.value5()),
                dsl.select(k.COLUMN_NAME).from(k)
                    .where(k.GRAPH_NAME.eq(graphName), k.TYPE_NAME.eq(r.value1()))
                    .orderBy(k.POSITION)
                    .fetch(k.COLUMN_NAME))));

        var m = SQL_NODE_METADATA;
        var kc = SQL_NODE_KEY_COLUMN;
        dsl.select(b.TYPE_NAME, m.TYPE_ID, b.TABLE_SOURCE_NAME, b.TABLE_SCHEMA, b.TABLE_NAME)
            .from(b)
            .join(m).on(m.SOURCE_NAME.eq(b.TABLE_SOURCE_NAME), m.TABLE_SCHEMA.eq(b.TABLE_SCHEMA),
                m.TABLE_NAME.eq(b.TABLE_NAME))
            .where(b.GRAPH_NAME.eq(graphName))
            .and(notExists(selectOne().from(n)
                .where(n.GRAPH_NAME.eq(b.GRAPH_NAME), n.TYPE_NAME.eq(b.TYPE_NAME))))
            .orderBy(b.TYPE_NAME)
            .forEach(r -> out.add(new Contributor(r.value1(), r.value2(),
                new TableKey(r.value3(), r.value4(), r.value5()),
                dsl.select(kc.COLUMN_NAME).from(kc)
                    .where(kc.SOURCE_NAME.eq(r.value3()), kc.TABLE_SCHEMA.eq(r.value4()),
                        kc.TABLE_NAME.eq(r.value5()))
                    .orderBy(kc.POSITION)
                    .fetch(kc.COLUMN_NAME))));
        return out;
    }

    /** Each type's {@code @node(legacyKeyColumns:)} spellings, in the order written. */
    private static Map<String, List<String>> writtenLists(DSLContext dsl, String graphName) {
        var e = GRAPHITRON_NODE_LEGACYKEYCOLUMN_ENTRY;
        var out = new LinkedHashMap<String, List<String>>();
        dsl.select(e.TYPE_NAME, e.COLUMN_REF).from(e)
            .where(e.GRAPH_NAME.eq(graphName))
            .orderBy(e.TYPE_NAME, e.POSITION)
            .forEach(r -> out.computeIfAbsent(r.value1(), k -> new ArrayList<>()).add(r.value2()));
        return out;
    }

    /** Each table class's {@code __NODE_LEGACY_KEY_COLUMNS}, resolved to columns, in array order. */
    private static Map<TableKey, List<String>> publishedLists(DSLContext dsl) {
        var l = SQL_NODE_LEGACY_KEY_COLUMN;
        var out = new LinkedHashMap<TableKey, List<String>>();
        dsl.select(l.SOURCE_NAME, l.TABLE_SCHEMA, l.TABLE_NAME, l.COLUMN_NAME).from(l)
            .orderBy(l.SOURCE_NAME, l.TABLE_SCHEMA, l.TABLE_NAME, l.POSITION)
            .forEach(r -> out.computeIfAbsent(new TableKey(r.value1(), r.value2(), r.value3()),
                k -> new ArrayList<>()).add(r.value4()));
        return out;
    }

    private static void anchor(DSLContext dsl, String graphName, Contributor c, Shape shape) {
        var t = GRAPHITRON_NODE_LEGACY_KEYCOLUMN;
        var current = upper(c.currentKey());
        for (int i = 0; i < shape.columns().size(); i++) {
            var column = shape.columns().get(i);
            int at = current.indexOf(column.toUpperCase(Locale.ROOT));
            dsl.insertInto(t, t.GRAPH_NAME, t.TYPE_ID, t.POSITION, t.COLUMN_NAME, t.CURRENT_POSITION,
                    t.COLUMN_ORIGIN, t.TABLE_SOURCE_NAME, t.TABLE_SCHEMA, t.TABLE_NAME)
                .values(graphName, c.typeId(), i, column, at < 0 ? null : at, shape.origin(),
                    c.table().source(), c.table().schema(), c.table().name())
                .execute();
        }
    }

    private static void record(DSLContext dsl, String graphName, List<Defect> defects) {
        var t = GRAPHITRON_NODE_LEGACY_KEY_DEFECT;
        var seen = new HashSet<List<String>>();
        for (var d : defects) {
            if (!seen.add(List.of(d.typeName(), d.code()))) {
                continue;
            }
            dsl.insertInto(t, t.GRAPH_NAME, t.TYPE_NAME, t.CODE, t.DETAIL, t.SITE)
                .values(graphName, d.typeName(), d.code(), d.detail(), d.site().name())
                .execute();
        }
    }

    private static List<String> upper(List<String> columns) {
        return columns.stream().map(s -> s.toUpperCase(Locale.ROOT)).toList();
    }
}
