package no.sikt.graphitron.lsp.facts;

import no.sikt.graphitron.model.read.StoreHandle;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.Record1;
import org.jooq.Records;

import java.util.Comparator;
import java.util.List;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_AST_FIELD_PATH_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_CODE_REFERENCE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_DIRECTIVE_APPLICATION;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_DIRECTIVE_APPLICATION_ARG;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_ENTRY_DEFECT;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_FIELD;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_FIELD_TARGET;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_COLUMN;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_JOIN_PATH;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_PATH_NEED_TABLE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_PRODUCER;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_PRODUCER_RESULT;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_RESULT_TABLE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_TABLETYPE;
import static no.sikt.graphitron.model.Tables.GRAPHQL_IMPLEMENTS_INTERFACE;
import static no.sikt.graphitron.model.Tables.GRAPHQL_UNION_MEMBER;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_SOURCE_TABLE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_NODE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_NODE_KEYCOLUMN;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_PRODUCER;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_READER;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_AST_ROUTINE_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHQL_DIRECTIVE_APPLICATION;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_TYPE;
import static no.sikt.graphitron.model.Tables.GRAPHQL_AST_ELEMENT_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHQL_FIELD;
import static no.sikt.graphitron.model.Tables.SQL_COLUMN;
import static org.jooq.impl.DSL.coalesce;
import static org.jooq.impl.DSL.exists;
import static org.jooq.impl.DSL.multiset;
import static org.jooq.impl.DSL.notExists;
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

    /** A table the field's read departs from. */
    public record Source(String table) {}

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

        // The field's column at each of its occurrences, on the table each one's path lands on.
        var o = GRAPHITRON_OCCURRENCE;
        var c = GRAPHITRON_OCCURRENCE_COLUMN;
        var sc = SQL_COLUMN;
        columns = multiset(selectDistinct(c.TABLE_NAME, c.COLUMN_NAME, sc.SQL_TYPE, sc.NULLABLE, c.MATCHED_BY,
                sc.DESCRIPTION)
            .from(o)
            .join(c).on(c.GRAPH_NAME.eq(o.GRAPH_NAME), c.COORDINATE.eq(o.COORDINATE))
            .join(sc).on(sc.SOURCE_NAME.eq(c.TABLE_SOURCE_NAME), sc.TABLE_SCHEMA.eq(c.TABLE_SCHEMA),
                sc.TABLE_NAME.eq(c.TABLE_NAME), sc.COLUMN_NAME.eq(c.COLUMN_NAME))
            .where(o.GRAPH_NAME.eq(graph), o.ELEMENT_COORDINATE.eq(typeName + "." + fieldName),
                o.KIND.eq("FIELD"))
            .orderBy(c.TABLE_NAME, c.COLUMN_NAME))
            .convertFrom(rows -> rows.map(Records.mapping(Column::new)))
            .as("info_columns");

        // Where each occurrence lands for each object type it reaches: its join path's table where
        // one was walked, else the need's own landing; and for a root, which has no source to start
        // a path on, where its producer lifts its records.
        var pn = GRAPHITRON_PATH_NEED_TABLE;
        var jp = GRAPHITRON_JOIN_PATH;
        var jt = GRAPHITRON_TYPE.as("info_landed_type");
        var rp = GRAPHITRON_OCCURRENCE_PRODUCER;
        var pr2 = GRAPHITRON_OCCURRENCE_PRODUCER_RESULT;
        var rt = GRAPHITRON_RESULT_TABLE;
        var um = GRAPHQL_UNION_MEMBER;
        var ii = GRAPHQL_IMPLEMENTS_INTERFACE;
        var mt = GRAPHITRON_TABLETYPE.as("info_member_table");
        var ct = GRAPHITRON_TABLETYPE.as("info_container_table");
        Field<String> landedTable = coalesce(jp.TARGET_TABLE, pn.LANDING_TABLE);
        targetTables = multiset(selectDistinct(pn.TARGET_TYPE, coalesce(jp.TARGET_SCHEMA, pn.LANDING_SCHEMA),
                landedTable).from(o)
            .join(pn).on(pn.GRAPH_NAME.eq(o.GRAPH_NAME), pn.COORDINATE.eq(o.COORDINATE))
            .leftJoin(jp).on(jp.GRAPH_NAME.eq(pn.GRAPH_NAME), jp.COORDINATE.eq(pn.COORDINATE),
                jp.TARGET_TYPE.eq(pn.TARGET_TYPE))
            .join(jt).on(jt.GRAPH_NAME.eq(pn.GRAPH_NAME), jt.TYPE_NAME.eq(pn.TARGET_TYPE),
                jt.KIND.notIn("SCALAR", "ENUM"))
            .where(o.GRAPH_NAME.eq(graph), o.ELEMENT_COORDINATE.eq(coordinate), o.KIND.eq("FIELD"),
                landedTable.isNotNull())
            .union(selectDistinct(t.TARGET_TYPE_NAME, pr2.TABLE_SCHEMA, pr2.TABLE_NAME).from(o)
                .join(rp).on(rp.GRAPH_NAME.eq(o.GRAPH_NAME), rp.COORDINATE.eq(o.COORDINATE), rp.START.eq("ROOT"))
                .join(pr2).on(pr2.GRAPH_NAME.eq(rp.GRAPH_NAME), pr2.COORDINATE.eq(rp.COORDINATE))
                .join(t).on(t.GRAPH_NAME.eq(o.GRAPH_NAME), t.TYPE_NAME.eq(typeName),
                    t.FIELD_NAME.eq(fieldName))
                .where(o.GRAPH_NAME.eq(graph), o.ELEMENT_COORDINATE.eq(coordinate), o.KIND.eq("FIELD"),
                    pr2.TABLE_NAME.isNotNull()))
            // A root producing a connection or records of no one table: its target's rows' tables.
            .union(selectDistinct(t.TARGET_TYPE_NAME, rt.TABLE_SCHEMA, rt.TABLE_NAME).from(o)
                .join(rp).on(rp.GRAPH_NAME.eq(o.GRAPH_NAME), rp.COORDINATE.eq(o.COORDINATE), rp.START.eq("ROOT"))
                .join(t).on(t.GRAPH_NAME.eq(o.GRAPH_NAME), t.TYPE_NAME.eq(typeName),
                    t.FIELD_NAME.eq(fieldName))
                .join(rt).on(rt.GRAPH_NAME.eq(t.GRAPH_NAME), rt.TYPE_NAME.eq(t.TARGET_TYPE_NAME))
                .where(o.GRAPH_NAME.eq(graph), o.ELEMENT_COORDINATE.eq(coordinate), o.KIND.eq("FIELD")))
            // A root returning a union or an interface that binds no table: each member that binds one.
            .union(selectDistinct(mt.TYPE_NAME, mt.TABLE_SCHEMA, mt.TABLE_NAME).from(o)
                .join(rp).on(rp.GRAPH_NAME.eq(o.GRAPH_NAME), rp.COORDINATE.eq(o.COORDINATE), rp.START.eq("ROOT"))
                .join(pr2).on(pr2.GRAPH_NAME.eq(rp.GRAPH_NAME), pr2.COORDINATE.eq(rp.COORDINATE),
                    pr2.TABLE_NAME.isNull())
                .join(t).on(t.GRAPH_NAME.eq(o.GRAPH_NAME), t.TYPE_NAME.eq(typeName),
                    t.FIELD_NAME.eq(fieldName))
                .join(mt).on(mt.GRAPH_NAME.eq(t.GRAPH_NAME),
                    exists(selectOne().from(um).where(um.GRAPH_NAME.eq(mt.GRAPH_NAME),
                            um.UNION_NAME.eq(t.TARGET_TYPE_NAME), um.MEMBER_TYPE_NAME.eq(mt.TYPE_NAME)))
                        .or(exists(selectOne().from(ii).where(ii.GRAPH_NAME.eq(mt.GRAPH_NAME),
                            ii.INTERFACE_NAME.eq(t.TARGET_TYPE_NAME), ii.TYPE_NAME.eq(mt.TYPE_NAME)))))
                .where(o.GRAPH_NAME.eq(graph), o.ELEMENT_COORDINATE.eq(coordinate), o.KIND.eq("FIELD"))
                .and(notExists(selectOne().from(ct).where(ct.GRAPH_NAME.eq(t.GRAPH_NAME),
                    ct.TYPE_NAME.eq(t.TARGET_TYPE_NAME))))))
            .convertFrom(rows -> rows.map(Records.mapping(TargetTable::new)).stream()
                .sorted(Comparator.comparing(TargetTable::typeName)
                    .thenComparing(TargetTable::schema).thenComparing(TargetTable::table))
                .toList())
            .as("info_target_tables");

        // The tables the field's occurrences read from, one line per table however many reach it.
        var s = GRAPHITRON_OCCURRENCE_SOURCE_TABLE;
        sources = multiset(selectDistinct(s.TABLE_SCHEMA, s.TABLE_NAME).from(o)
            .join(s).on(s.GRAPH_NAME.eq(o.GRAPH_NAME), s.COORDINATE.eq(o.COORDINATE))
            .where(o.GRAPH_NAME.eq(graph), o.ELEMENT_COORDINATE.eq(coordinate), o.KIND.eq("FIELD"))
            .orderBy(s.TABLE_SCHEMA, s.TABLE_NAME))
            .convertFrom(rows -> rows.map(row -> new Source(row.value2())))
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

        // A code reference is named by its application: the references written on the application
        // itself, not in a path element or an @order field below it.
        var d = GRAPHITRON_DIRECTIVE_APPLICATION;
        var r = GRAPHITRON_CODE_REFERENCE;
        methods = multiset(select(d.DIRECTIVE_NAME, r.METHOD_CLASS_NAME, r.METHOD_NAME, r.METHOD_DESCRIPTOR)
            .from(d)
            .join(r).on(r.GRAPH_NAME.eq(d.GRAPH_NAME), r.COORDINATE.eq(d.COORDINATE),
                r.DIRECTIVE_NAME.eq(d.DIRECTIVE_NAME), r.ORDINAL.eq(d.ORDINAL), r.KIND.eq("ROOT"))
            .where(d.GRAPH_NAME.eq(graph), d.COORDINATE.eq(coordinate), d.ORIGIN.eq("AUTHORED"))
            .orderBy(d.DIRECTIVE_NAME, d.ORDINAL))
            .convertFrom(rows -> rows.map(Records.mapping(Method::new)))
            .as("info_methods");

        // Each @routine the author wrote that names one, in written order.
        var ra = GRAPHQL_DIRECTIVE_APPLICATION;
        var re = GRAPHITRON_AST_ROUTINE_ENTRY;
        routines = multiset(select(re.ROUTINE_REF).from(ra)
            .join(re).on(re.GRAPH_NAME.eq(ra.GRAPH_NAME), re.SOURCE_NAME.eq(ra.SOURCE_NAME),
                re.SOURCE_LINE.eq(ra.SOURCE_LINE), re.SOURCE_COLUMN.eq(ra.SOURCE_COLUMN))
            .where(ra.GRAPH_NAME.eq(graph), ra.COORDINATE.eq(coordinate), ra.DIRECTIVE_NAME.eq("routine"))
            .orderBy(ra.ORDINAL))
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
