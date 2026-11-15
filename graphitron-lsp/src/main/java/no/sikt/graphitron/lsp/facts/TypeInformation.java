package no.sikt.graphitron.lsp.facts;

import no.sikt.graphitron.model.read.StoreHandle;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.Record1;
import org.jooq.Records;

import java.util.List;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_DIRECTIVE_APPLICATION;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_DIRECTIVE_APPLICATION_ARG;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_ENTRY_DEFECT;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_AST_ERROR_GENERIC_HANDLER_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_AST_ERROR_HANDLER_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_NODE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_NODE_KEYCOLUMN;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_FIELD;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_SOURCE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_SOURCE_OBJECT;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_SOURCE_TABLE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_TYPE;
import static no.sikt.graphitron.model.Tables.GRAPHQL_DIRECTIVE_APPLICATION;
import static no.sikt.graphitron.model.Tables.GRAPHQL_TYPE_DECLARATION;
import static no.sikt.graphitron.model.Tables.SQL_TABLE;
import static org.jooq.impl.DSL.multiset;
import static org.jooq.impl.DSL.row;
import static org.jooq.impl.DSL.select;
import static org.jooq.impl.DSL.selectDistinct;

/**
 * What the store states about one type, for the declaration hover: each line a lookup by key in a
 * stored relation, and never a view, on {@link FieldInformation}'s terms.
 */
public final class TypeInformation {

    /** The type as declared: its kind and its description, null where none is written. */
    public record Signature(String kind, String description) {}

    /** A table the type's fields are read from, with the table's own comment. */
    public record RowOf(String schema, String table, String description) {}

    /** The type's node identity: its type id and key columns in key order. */
    public record Node(String typeId, List<String> keyColumns) {}

    /** One handler of an {@code @error} type, in declaration order. */
    public record Handler(String handler, String className) {}

    /** Everything one statement read about the type. */
    public record Information(
        Signature signature, List<RowOf> rows, List<String> classes, List<Node> nodes,
        List<Handler> handlers, List<FieldInformation.Added> added, List<String> defects
    ) {
        /** The store does not know the type, which is a declaration with no block. */
        public boolean isEmpty() {
            return signature == null;
        }
    }

    private final Field<List<Signature>> signature;
    private final Field<List<RowOf>> rows;
    private final Field<List<String>> classes;
    private final Field<List<Node>> nodes;
    private final Field<List<Handler>> handlers;
    private final Field<List<FieldInformation.Added>> added;
    private final Field<List<String>> defects;

    private TypeInformation(StoreHandle store, String typeName) {
        String graph = store.graphName();

        var t = GRAPHITRON_TYPE;
        signature = multiset(select(t.KIND, t.DESCRIPTION).from(t)
            .where(t.GRAPH_NAME.eq(graph), t.TYPE_NAME.eq(typeName)))
            .convertFrom(r -> r.map(Records.mapping(Signature::new)))
            .as("type_info_signature");

        // What the type's fields are read from, over every occurrence of each: the tables whose rows
        // they read and the classes whose instances they do. A source an occurrence's own producer
        // claims, a child's own service record or a root's routine, is what that field produces and
        // not what the type is read from.
        var f = GRAPHITRON_FIELD;
        var o = GRAPHITRON_OCCURRENCE;
        var s = GRAPHITRON_OCCURRENCE_SOURCE;
        var st = GRAPHITRON_OCCURRENCE_SOURCE_TABLE;
        var sql = SQL_TABLE;
        rows = multiset(selectDistinct(st.TABLE_SCHEMA, st.TABLE_NAME, sql.DESCRIPTION).from(f)
            .join(o).on(o.GRAPH_NAME.eq(f.GRAPH_NAME), o.ELEMENT_COORDINATE.eq(f.COORDINATE), o.KIND.eq("FIELD"))
            .join(s).on(s.GRAPH_NAME.eq(o.GRAPH_NAME), s.COORDINATE.eq(o.COORDINATE),
                s.RULE.notIn("ownServiceRecord", "rootRoutine"))
            .join(st).on(st.GRAPH_NAME.eq(s.GRAPH_NAME), st.COORDINATE.eq(s.COORDINATE))
            .join(sql).on(sql.SOURCE_NAME.eq(st.TABLE_SOURCE_NAME), sql.TABLE_SCHEMA.eq(st.TABLE_SCHEMA),
                sql.TABLE_NAME.eq(st.TABLE_NAME))
            .where(f.GRAPH_NAME.eq(graph), f.TYPE_NAME.eq(typeName))
            .orderBy(st.TABLE_SCHEMA, st.TABLE_NAME))
            .convertFrom(r -> r.map(Records.mapping(RowOf::new)))
            .as("type_info_rows");

        var so = GRAPHITRON_OCCURRENCE_SOURCE_OBJECT;
        classes = multiset(selectDistinct(so.CLASS_NAME).from(f)
            .join(o).on(o.GRAPH_NAME.eq(f.GRAPH_NAME), o.ELEMENT_COORDINATE.eq(f.COORDINATE), o.KIND.eq("FIELD"))
            .join(so).on(so.GRAPH_NAME.eq(o.GRAPH_NAME), so.COORDINATE.eq(o.COORDINATE))
            .where(f.GRAPH_NAME.eq(graph), f.TYPE_NAME.eq(typeName))
            .orderBy(so.CLASS_NAME))
            .convertFrom(r -> r.map(Record1::value1))
            .as("type_info_classes");

        var n = GRAPHITRON_NODE;
        var k = GRAPHITRON_NODE_KEYCOLUMN;
        nodes = multiset(select(n.TYPE_ID,
                multiset(select(k.COLUMN_NAME).from(k)
                    .where(k.GRAPH_NAME.eq(n.GRAPH_NAME), k.TYPE_NAME.eq(n.TYPE_NAME))
                    .orderBy(k.POSITION))
                    .convertFrom(keys -> keys.map(Record1::value1)))
            .from(n)
            .where(n.GRAPH_NAME.eq(graph), n.TYPE_NAME.eq(typeName)))
            .convertFrom(r -> r.map(Records.mapping(Node::new)))
            .as("type_info_nodes");

        // The handlers of the type's first @error application, @error not being repeatable; a
        // class is written on a GENERIC handler alone.
        var app = GRAPHQL_DIRECTIVE_APPLICATION;
        var h = GRAPHITRON_AST_ERROR_HANDLER_ENTRY;
        var g = GRAPHITRON_AST_ERROR_GENERIC_HANDLER_ENTRY;
        handlers = multiset(select(h.HANDLER, g.CLASS_NAME).from(app)
            .join(h).on(h.GRAPH_NAME.eq(app.GRAPH_NAME), h.SOURCE_NAME.eq(app.SOURCE_NAME),
                h.APPLICATION_LINE.eq(app.SOURCE_LINE), h.APPLICATION_COLUMN.eq(app.SOURCE_COLUMN))
            .leftJoin(g).on(g.GRAPH_NAME.eq(h.GRAPH_NAME), g.SOURCE_NAME.eq(h.SOURCE_NAME),
                g.SOURCE_LINE.eq(h.SOURCE_LINE), g.SOURCE_COLUMN.eq(h.SOURCE_COLUMN))
            .where(app.GRAPH_NAME.eq(graph), app.COORDINATE.eq(typeName),
                app.DIRECTIVE_NAME.eq("error"), app.ORDINAL.eq(0))
            .orderBy(h.POSITION))
            .convertFrom(r -> r.map(Records.mapping(Handler::new)))
            .as("type_info_handlers");

        var d = GRAPHITRON_DIRECTIVE_APPLICATION;
        var a = GRAPHITRON_DIRECTIVE_APPLICATION_ARG;
        added = multiset(select(d.DIRECTIVE_NAME, d.ORIGIN,
                multiset(select(a.DIRECTIVE_ARGUMENT_NAME, a.VALUE_SDL).from(a)
                    .where(a.GRAPH_NAME.eq(d.GRAPH_NAME), a.COORDINATE.eq(d.COORDINATE),
                        a.DIRECTIVE_NAME.eq(d.DIRECTIVE_NAME), a.ORDINAL.eq(d.ORDINAL))
                    .orderBy(a.DIRECTIVE_ARGUMENT_NAME))
                    .convertFrom(args -> args.map(Records.mapping(FieldInformation.Argument::new))))
            .from(d)
            .where(d.GRAPH_NAME.eq(graph), d.COORDINATE.eq(typeName), d.ORIGIN.ne("AUTHORED"))
            .orderBy(d.DIRECTIVE_NAME, d.ORDINAL))
            .convertFrom(r -> r.map(Records.mapping(FieldInformation.Added::new)))
            .as("type_info_added");

        // A defect at the type is reported at one of its declarations, base or extension, or at a
        // directive applied to it.
        var e = GRAPHITRON_ENTRY_DEFECT;
        var td = GRAPHQL_TYPE_DECLARATION;
        var position = row(e.SOURCE_NAME, e.SOURCE_LINE, e.SOURCE_COLUMN);
        defects = multiset(selectDistinct(e.CODE).from(e)
            .where(e.GRAPH_NAME.eq(graph))
            .and(position.in(select(td.SOURCE_NAME, td.SOURCE_LINE, td.SOURCE_COLUMN).from(td)
                    .where(td.GRAPH_NAME.eq(graph), td.TYPE_NAME.eq(typeName)))
                .or(position.in(select(d.SOURCE_NAME, d.SOURCE_LINE, d.SOURCE_COLUMN).from(d)
                    .where(d.GRAPH_NAME.eq(graph), d.COORDINATE.eq(typeName), d.ORIGIN.eq("AUTHORED")))))
            .orderBy(e.CODE))
            .convertFrom(r -> r.map(Record1::value1))
            .as("type_info_defects");
    }

    /** The lines of the type {@code typeName}, for a caller assembling them into a select of its own. */
    public static TypeInformation of(StoreHandle store, String typeName) {
        return new TypeInformation(store, typeName);
    }

    /** The arms, in the order the block renders them. */
    public List<Field<?>> fields() {
        return List.of(signature, rows, classes, nodes, handlers, added, defects);
    }

    /** The information, read off the record the caller's statement returned. */
    public Information read(Record row) {
        var signatures = row.get(signature);
        return new Information(signatures.isEmpty() ? null : signatures.getFirst(),
            row.get(rows), row.get(classes), row.get(nodes), row.get(handlers), row.get(added),
            row.get(defects));
    }
}
