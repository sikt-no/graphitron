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
import static no.sikt.graphitron.model.Tables.GRAPHITRON_ERROR_HANDLER_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_NODE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_NODE_KEYCOLUMN;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_RESULT;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_RESULT_CLASS;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_RESULT_TABLE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_TYPE;
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

    /** A row of a table the type holds, with the table's own comment. */
    public record RowOf(String schema, String table, String description) {}

    /** The type's node identity: its type id and key columns in key order. */
    public record Node(String typeId, List<String> keyColumns) {}

    /** One handler of an {@code @error} type, in declaration order. */
    public record Handler(String handler, String className) {}

    /** Everything one statement read about the type. */
    public record Information(
        Signature signature, List<String> shapes, List<RowOf> rows, List<String> classes, List<Node> nodes,
        List<Handler> handlers, List<FieldInformation.Added> added, List<String> defects
    ) {
        /** The store does not know the type, which is a declaration with no block. */
        public boolean isEmpty() {
            return signature == null;
        }
    }

    private final Field<List<Signature>> signature;
    private final Field<List<String>> shapes;
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

        var res = GRAPHITRON_RESULT;
        shapes = multiset(select(res.SHAPE).from(res)
            .where(res.GRAPH_NAME.eq(graph), res.TYPE_NAME.eq(typeName))
            .orderBy(res.SHAPE))
            .convertFrom(r -> r.map(Record1::value1))
            .as("type_info_shapes");

        var rt = GRAPHITRON_RESULT_TABLE;
        var st = SQL_TABLE;
        rows = multiset(select(rt.TABLE_SCHEMA, rt.TABLE_NAME, st.DESCRIPTION).from(rt)
            .join(st).on(st.SOURCE_NAME.eq(rt.TABLE_SOURCE_NAME), st.TABLE_SCHEMA.eq(rt.TABLE_SCHEMA),
                st.TABLE_NAME.eq(rt.TABLE_NAME))
            .where(rt.GRAPH_NAME.eq(graph), rt.TYPE_NAME.eq(typeName))
            .orderBy(rt.TABLE_SCHEMA, rt.TABLE_NAME))
            .convertFrom(r -> r.map(Records.mapping(RowOf::new)))
            .as("type_info_rows");

        var rc = GRAPHITRON_RESULT_CLASS;
        classes = multiset(select(rc.CLASS_NAME).from(rc)
            .where(rc.GRAPH_NAME.eq(graph), rc.TYPE_NAME.eq(typeName))
            .orderBy(rc.CLASS_NAME))
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

        var h = GRAPHITRON_ERROR_HANDLER_ENTRY;
        handlers = multiset(select(h.HANDLER, h.CLASS_NAME).from(h)
            .where(h.GRAPH_NAME.eq(graph), h.TYPE_NAME.eq(typeName))
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
        return List.of(signature, shapes, rows, classes, nodes, handlers, added, defects);
    }

    /** The information, read off the record the caller's statement returned. */
    public Information read(Record row) {
        var signatures = row.get(signature);
        return new Information(signatures.isEmpty() ? null : signatures.getFirst(), row.get(shapes),
            row.get(rows), row.get(classes), row.get(nodes), row.get(handlers), row.get(added),
            row.get(defects));
    }
}
