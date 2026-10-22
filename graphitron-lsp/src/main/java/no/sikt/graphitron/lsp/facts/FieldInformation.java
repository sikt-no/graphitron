package no.sikt.graphitron.lsp.facts;

import no.sikt.graphitron.model.read.StoreHandle;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.Record1;
import org.jooq.Records;

import java.util.List;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_AST_FIELD_PATH_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_CODE_REFERENCE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_DIRECTIVE_APPLICATION;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_DIRECTIVE_APPLICATION_ARG;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_ENTRY_DEFECT;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_FIELD;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_FIELD_SOURCE_TABLE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_FIELD_TARGET;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_FIELD_TARGET_COLUMN;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_FIELD_TARGET_TABLE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_NODE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_NODE_KEYCOLUMN;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_PRODUCER;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_READER;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_RESOLVED_TYPE_BINDING;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_ROUTINE_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_TYPE;
import static no.sikt.graphitron.model.Tables.GRAPHQL_AST_ELEMENT_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHQL_AST_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHQL_FIELD;
import static no.sikt.graphitron.model.Tables.SQL_COLUMN;
import static org.jooq.impl.DSL.multiset;
import static org.jooq.impl.DSL.row;
import static org.jooq.impl.DSL.select;
import static org.jooq.impl.DSL.selectCount;
import static org.jooq.impl.DSL.selectDistinct;
import static org.jooq.impl.DSL.selectOne;

/**
 * What the store states about one output field, for the declaration hover: each line a lookup by
 * key in a stored relation, and never a view. The views the hover read before were the cost, H2
 * evaluating one whole for every hover because it cannot push the coordinate into a window or a
 * recursive CTE; a stored relation answers a key in the time it takes to find it.
 *
 * <p>One statement, a multiset per relation: nothing is joined across lines, so no line's fan-out
 * multiplies another's.
 */
public final class FieldInformation {

    /** The field as declared: its type expression and its description, null where none is written. */
    public record Signature(String typeSdl, String description) {}

    /** The column a scalar reads, on the table holding it. */
    public record Column(String table, String column, String sqlType, boolean nullable,
                         String matchedBy, String description) {}

    /** The field's target type, the connection it was unwrapped from, and the target's kind. */
    public record Target(String typeName, String wrapperTypeName, String kind) {}

    /** A table one of the field's statements lands on, through the type that binds it. */
    public record TargetTable(String typeName, String schema, String table) {}

    /** A table the field's read departs from, and which rule named it. */
    public record Source(String table, String basis) {}

    /** The target's node identity: its type id and key columns in key order. */
    public record Node(String typeId, List<String> keyColumns) {}

    /** A method a code reference written at the field resolves to. */
    public record Method(String directive, String className, String methodName, String descriptor) {}

    /** A directive graphitron applies at the field that its author did not write. */
    public record Added(String directive, String origin, List<Argument> arguments) {}

    /** One argument of an added directive, in the form it is printed. */
    public record Argument(String name, String valueSdl) {}

    /**
     * The field's fetcher: what it runs where it produces a result, how it reads its parent's where
     * it reads one, and neither where the store states no fetcher for it.
     */
    public record Fetch(String produces, String reads) {}

    /** Everything one statement read about the field. */
    public record Information(
        Signature signature, Fetch fetch, Target target, List<Column> columns, List<TargetTable> targetTables,
        List<Source> sources, List<Node> nodes, List<Method> methods, List<String> routines,
        List<Added> added, int pathElements, List<String> defects
    ) {
        /** The store does not know the field, which is a declaration with no block. */
        public boolean isEmpty() {
            return signature == null && target == null;
        }
    }

    private final Field<List<Signature>> signature;
    private final Field<List<String>> produces;
    private final Field<List<String>> reads;
    private final Field<List<Target>> target;
    private final Field<List<Column>> columns;
    private final Field<List<TargetTable>> targetTables;
    private final Field<List<Source>> sources;
    private final Field<List<Node>> nodes;
    private final Field<List<Method>> methods;
    private final Field<List<String>> routines;
    private final Field<List<Added>> added;
    private final Field<Integer> pathElements;
    private final Field<List<String>> defects;

    private FieldInformation(StoreHandle store, String typeName, String fieldName) {
        String graph = store.graphName();
        String coordinate = typeName + "." + fieldName;

        var f = GRAPHITRON_FIELD;
        signature = multiset(select(f.TYPE_SDL, f.DESCRIPTION).from(f)
            .where(f.GRAPH_NAME.eq(graph), f.TYPE_NAME.eq(typeName), f.FIELD_NAME.eq(fieldName)))
            .convertFrom(rows -> rows.map(Records.mapping(Signature::new)))
            .as("info_signature");

        var pr = GRAPHITRON_PRODUCER;
        produces = multiset(select(pr.KIND).from(pr)
            .where(pr.GRAPH_NAME.eq(graph), pr.TYPE_NAME.eq(typeName), pr.FIELD_NAME.eq(fieldName)))
            .convertFrom(rows -> rows.map(Record1::value1))
            .as("info_produces");
        var rd = GRAPHITRON_READER;
        reads = multiset(select(rd.READS).from(rd)
            .where(rd.GRAPH_NAME.eq(graph), rd.TYPE_NAME.eq(typeName), rd.FIELD_NAME.eq(fieldName)))
            .convertFrom(rows -> rows.map(Record1::value1))
            .as("info_reads");

        var t = GRAPHITRON_FIELD_TARGET;
        var kind = GRAPHITRON_TYPE;
        target = multiset(select(t.TARGET_TYPE_NAME, t.WRAPPER_TYPE_NAME, kind.KIND).from(t)
            .leftJoin(kind).on(kind.GRAPH_NAME.eq(t.GRAPH_NAME), kind.TYPE_NAME.eq(t.TARGET_TYPE_NAME))
            .where(t.GRAPH_NAME.eq(graph), t.TYPE_NAME.eq(typeName), t.FIELD_NAME.eq(fieldName)))
            .convertFrom(rows -> rows.map(Records.mapping(Target::new)))
            .as("info_target");

        var c = GRAPHITRON_FIELD_TARGET_COLUMN;
        var sc = SQL_COLUMN;
        columns = multiset(selectDistinct(c.TO_TABLE, c.COLUMN_NAME, sc.SQL_TYPE, sc.NULLABLE, c.MATCHED_BY,
                sc.DESCRIPTION)
            .from(c)
            .join(sc).on(sc.SOURCE_NAME.eq(c.TO_SOURCE_NAME), sc.TABLE_SCHEMA.eq(c.TO_SCHEMA),
                sc.TABLE_NAME.eq(c.TO_TABLE), sc.COLUMN_NAME.eq(c.COLUMN_NAME))
            .where(c.GRAPH_NAME.eq(graph), c.TYPE_NAME.eq(typeName), c.FIELD_NAME.eq(fieldName))
            .orderBy(c.TO_TABLE, c.COLUMN_NAME))
            .convertFrom(rows -> rows.map(Records.mapping(Column::new)))
            .as("info_columns");

        var tt = GRAPHITRON_FIELD_TARGET_TABLE;
        var b = GRAPHITRON_RESOLVED_TYPE_BINDING;
        targetTables = multiset(select(tt.ARRIVING_TYPE_NAME, b.TABLE_SCHEMA, b.TABLE_NAME).from(tt)
            .join(b).on(b.GRAPH_NAME.eq(tt.GRAPH_NAME), b.TYPE_NAME.eq(tt.ARRIVING_TYPE_NAME))
            .where(tt.GRAPH_NAME.eq(graph), tt.TYPE_NAME.eq(typeName), tt.FIELD_NAME.eq(fieldName))
            .orderBy(tt.ARRIVING_TYPE_NAME))
            .convertFrom(rows -> rows.map(Records.mapping(TargetTable::new)))
            .as("info_target_tables");

        var s = GRAPHITRON_FIELD_SOURCE_TABLE;
        sources = multiset(select(s.FROM_TABLE, s.BASIS).from(s)
            .where(s.GRAPH_NAME.eq(graph), s.TYPE_NAME.eq(typeName), s.FIELD_NAME.eq(fieldName))
            .orderBy(s.FROM_SCHEMA, s.FROM_TABLE))
            .convertFrom(rows -> rows.map(Records.mapping(Source::new)))
            .as("info_sources");

        var n = GRAPHITRON_NODE;
        var k = GRAPHITRON_NODE_KEYCOLUMN;
        nodes = multiset(select(n.TYPE_ID,
                multiset(select(k.COLUMN_NAME).from(k)
                    .where(k.GRAPH_NAME.eq(n.GRAPH_NAME), k.TYPE_NAME.eq(n.TYPE_NAME))
                    .orderBy(k.POSITION))
                    .convertFrom(keys -> keys.map(Record1::value1)))
            .from(t)
            .join(n).on(n.GRAPH_NAME.eq(t.GRAPH_NAME), n.TYPE_NAME.eq(t.TARGET_TYPE_NAME))
            .where(t.GRAPH_NAME.eq(graph), t.TYPE_NAME.eq(typeName), t.FIELD_NAME.eq(fieldName)))
            .convertFrom(rows -> rows.map(Records.mapping(Node::new)))
            .as("info_nodes");

        // A code reference is keyed by the object value it was written as, two levels under the
        // directive: the application, its argument, the value.
        var d = GRAPHITRON_DIRECTIVE_APPLICATION;
        var arg = GRAPHQL_AST_ENTRY.as("info_arg");
        var val = GRAPHQL_AST_ENTRY.as("info_val");
        var r = GRAPHITRON_CODE_REFERENCE;
        methods = multiset(select(d.DIRECTIVE_NAME, r.METHOD_CLASS_NAME, r.METHOD_NAME, r.METHOD_DESCRIPTOR)
            .from(d)
            .join(arg).on(arg.GRAPH_NAME.eq(d.GRAPH_NAME), arg.SOURCE_NAME.eq(d.SOURCE_NAME),
                arg.PARENT_LINE.eq(d.SOURCE_LINE), arg.PARENT_COLUMN.eq(d.SOURCE_COLUMN))
            .join(val).on(val.GRAPH_NAME.eq(arg.GRAPH_NAME), val.SOURCE_NAME.eq(arg.SOURCE_NAME),
                val.PARENT_LINE.eq(arg.SOURCE_LINE), val.PARENT_COLUMN.eq(arg.SOURCE_COLUMN))
            .join(r).on(r.GRAPH_NAME.eq(val.GRAPH_NAME), r.SOURCE_NAME.eq(val.SOURCE_NAME),
                r.SOURCE_LINE.eq(val.SOURCE_LINE), r.SOURCE_COLUMN.eq(val.SOURCE_COLUMN))
            .where(d.GRAPH_NAME.eq(graph), d.COORDINATE.eq(coordinate), d.ORIGIN.eq("AUTHORED"))
            .orderBy(d.DIRECTIVE_NAME, d.ORDINAL))
            .convertFrom(rows -> rows.map(Records.mapping(Method::new)))
            .as("info_methods");

        var re = GRAPHITRON_ROUTINE_ENTRY;
        routines = multiset(select(re.ROUTINE_REF).from(re)
            .where(re.GRAPH_NAME.eq(graph), re.TYPE_NAME.eq(typeName), re.FIELD_NAME.eq(fieldName))
            .orderBy(re.ORDINAL))
            .convertFrom(rows -> rows.map(Record1::value1))
            .as("info_routines");

        var a = GRAPHITRON_DIRECTIVE_APPLICATION_ARG;
        added = multiset(select(d.DIRECTIVE_NAME, d.ORIGIN,
                multiset(select(a.DIRECTIVE_ARGUMENT_NAME, a.VALUE_SDL).from(a)
                    .where(a.GRAPH_NAME.eq(d.GRAPH_NAME), a.COORDINATE.eq(d.COORDINATE),
                        a.DIRECTIVE_NAME.eq(d.DIRECTIVE_NAME), a.ORDINAL.eq(d.ORDINAL))
                    .orderBy(a.DIRECTIVE_ARGUMENT_NAME))
                    .convertFrom(args -> args.map(Records.mapping(Argument::new))))
            .from(d)
            .where(d.GRAPH_NAME.eq(graph), d.COORDINATE.eq(coordinate), d.ORIGIN.ne("AUTHORED"))
            .orderBy(d.DIRECTIVE_NAME, d.ORDINAL))
            .convertFrom(rows -> rows.map(Records.mapping(Added::new)))
            .as("info_added");

        // The path written at the field, as capture composed it: each element by where it was
        // written, which is what the count counts and what a defect at one of them is keyed by.
        var fe = GRAPHQL_AST_ELEMENT_ENTRY;
        var fp = GRAPHITRON_AST_FIELD_PATH_ENTRY;
        var written = select(fp.SOURCE_NAME, fp.ELEMENT_LINE, fp.ELEMENT_COLUMN)
            .from(fp)
            .join(fe).on(fe.GRAPH_NAME.eq(fp.GRAPH_NAME), fe.SOURCE_NAME.eq(fp.SOURCE_NAME),
                fe.SOURCE_LINE.eq(fp.FIELD_LINE), fe.SOURCE_COLUMN.eq(fp.FIELD_COLUMN))
            .where(fe.GRAPH_NAME.eq(graph), fe.COORDINATE.eq(coordinate));
        pathElements = selectCount().from(written.asTable("written")).asField("info_path_elements");

        // A defect at the field is reported at one of three kinds of position the author wrote
        // there: the field's own declaration, a directive applied to it, or an element of its path.
        var e = GRAPHITRON_ENTRY_DEFECT;
        var gf = GRAPHQL_FIELD;
        var position = row(e.SOURCE_NAME, e.SOURCE_LINE, e.SOURCE_COLUMN);
        defects = multiset(selectDistinct(e.CODE).from(e)
            .where(e.GRAPH_NAME.eq(graph))
            .and(position.in(select(gf.SOURCE_NAME, gf.SOURCE_LINE, gf.SOURCE_COLUMN).from(gf)
                    .where(gf.GRAPH_NAME.eq(graph), gf.TYPE_NAME.eq(typeName), gf.FIELD_NAME.eq(fieldName)))
                .or(position.in(select(d.SOURCE_NAME, d.SOURCE_LINE, d.SOURCE_COLUMN).from(d)
                    .where(d.GRAPH_NAME.eq(graph), d.COORDINATE.eq(coordinate), d.ORIGIN.eq("AUTHORED"))))
                .or(position.in(written)))
            .orderBy(e.CODE))
            .convertFrom(rows -> rows.map(Record1::value1))
            .as("info_defects");
    }

    /** The lines of {@code Type.field}, for a caller assembling them into a select of its own. */
    public static FieldInformation of(StoreHandle store, String typeName, String fieldName) {
        return new FieldInformation(store, typeName, fieldName);
    }

    /** The arms, in the order the block renders them. */
    public List<Field<?>> fields() {
        return List.of(signature, produces, reads, target, columns, targetTables, sources, nodes, methods, routines,
            added, pathElements, defects);
    }

    /** The information, read off the record the caller's statement returned. */
    public Information read(Record row) {
        String producer = first(row.get(produces));
        String reader = first(row.get(reads));
        var fetch = producer == null && reader == null ? null : new Fetch(producer, reader);
        return new Information(first(row.get(signature)), fetch, first(row.get(target)), row.get(columns),
            row.get(targetTables), row.get(sources), row.get(nodes), row.get(methods), row.get(routines),
            row.get(added), row.get(pathElements), row.get(defects));
    }

    private static <T> T first(List<T> rows) {
        return rows.isEmpty() ? null : rows.getFirst();
    }
}
