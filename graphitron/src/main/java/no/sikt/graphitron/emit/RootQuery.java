package no.sikt.graphitron.emit;

import no.sikt.graphitron.model.grammar.ConnectionDefaults;
import no.sikt.graphitron.model.jooq.ColumnRef;
import no.sikt.graphitron.model.jooq.ForeignKeyRef;
import no.sikt.graphitron.model.jooq.TableRef;
import no.sikt.graphitron.model.read.StoreHandle;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_ARGUMENT;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_CONNECTION;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_CONNECTION_CARRIER;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_DIRECTIVE_APPLICATION;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_EDGE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_ENUM_VALUE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_FIELD;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_JOIN_PATH_STEP;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_JOIN_PATH_STEP_COLUMN;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_JOIN_PATH_STEP_INFERRED_KEY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_JOIN_PATH_STEP_KEY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_INTERFACE_SHARED_FIELD;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_FIELD_CROSS_COLUMN;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_FIELD_TARGET;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_COLUMN;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_CONDITION_SIGNATURE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_IDENTIFYING_KEY_COLUMN;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_LOOKUP_KEY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_NODE_ID;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_ORDER;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_PARTICIPANT;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_PRODUCER;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_PRODUCER_CONNECTION;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_PRODUCER_NODE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_PRODUCER_RESULT;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_SORT_KEY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_SORT_KEY_COLUMN;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_SORT_KEY_COMPLETION;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_ORDER_INPUT;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_TABLETYPE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_TENANT_COLUMN;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_TYPE;
import static no.sikt.graphitron.model.Tables.SQL_COLUMN;
import static no.sikt.graphitron.model.Tables.SQL_CONSTRAINT;
import static no.sikt.graphitron.model.Tables.SQL_CONSTRAINT_COLUMN;
import static no.sikt.graphitron.model.Tables.SQL_PRIMARY_KEY;
import static no.sikt.graphitron.model.Tables.SQL_SCHEMA;
import static no.sikt.graphitron.model.Tables.SQL_TABLE;
import static no.sikt.graphitron.model.Tables.STORE_GRAPH_MAX_ALLOWED_PAGE_SIZE;
import static no.sikt.graphitron.model.Tables.STORE_GRAPH_TENANT_COLUMN;
import static org.jooq.impl.DSL.coalesce;
import static org.jooq.impl.DSL.exists;
import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.selectOne;

/**
 * Each root {@code SELECT} or {@code LOOKUP} producer, and whether the store states its fetcher whole:
 * read off its occurrence, the table its records are rows of, the type whose projection selects them,
 * its default order and the condition method its table has.
 *
 * <p>A node fetcher is a {@link NodeFetcher}, its root method only handing the request to the node
 * dispatcher.
 *
 * <p>Roots flip piece by piece, so a producer the store does not state whole yet is {@link NotYet}, its
 * reason the stored fact that holds it back, and its fetcher stays the walk's. The census in
 * {@code EmissionCensusTest} pins each reason, and a reason goes with the node that states its shape.
 */
public final class RootQuery {

    private RootQuery() {}

    /** Why a root's fetcher is not emitted from the store yet. */
    public enum Reason {
        /** A {@code @lookupKey} key is a field of an input object the argument takes. */
        LOOKUP_INPUT_FIELD,
        /** A {@code @lookupKey} key is a node id, decoded to its node's key columns. */
        LOOKUP_NODE_ID,
        /** A {@code @lookupKey} key's value is converted on its way to the column: an {@code ID} or an enum. */
        LOOKUP_KEY_CONVERTED,
        /** Its rows may be several types', told apart by the table each is a row of, and it is a lookup over them. */
        POLYMORPHIC,
        /** Its rows are several tables', and a node-id argument names one of them by the id's type. */
        NODE_ID_DISPATCH,
        /**
         * Its rows are told apart by a discriminator, and a participant on a detail table of its own reads
         * a field the joined read does not project: a node id, or an inherited reference it would alias
         * apart from another interface's read of the same type.
         */
        JOINED_DETAIL,
        /** It builds a connection that surfaces facets. */
        FACETED,
        /** It builds a connection the store states no order for, which paging needs. */
        NO_ORDER,
        /** Its table carries the tenant column. */
        TENANTED,
        /** It is read through a {@code @pivot}. */
        PIVOT,
        /** A key of its order is not a column of its table. */
        SORT_KEY_NOT_A_COLUMN,
        /** It produces no records of one table-bound type. */
        NOT_ONE_TABLE
    }

    /** One root producer's verdict, keyed by its occurrence. */
    public sealed interface Result {
        String coordinate();
    }

    /**
     * A node fetcher, a root returning the {@code Node} interface by the id or ids it decodes: its
     * field, whether it answers a list, and whether the build is multi-tenant, which decides whether
     * it answers through the entity loader or in the tenant's envelope. What it dispatches to is the
     * node dispatcher's, generated from the store's served nodes.
     */
    public record NodeFetcher(String coordinate, String parentType, String fieldName, boolean plural,
                              boolean multiTenant) implements Result {
        public NodeFetcher {
            Objects.requireNonNull(coordinate, "coordinate");
            Objects.requireNonNull(parentType, "parentType");
            Objects.requireNonNull(fieldName, "fieldName");
        }
    }

    /** A root the store holds back, and why. */
    public record NotYet(String coordinate, Reason reason) implements Result {
        public NotYet {
            Objects.requireNonNull(coordinate, "coordinate");
            Objects.requireNonNull(reason, "reason");
        }
    }

    /**
     * A root the store states whole: its occurrence and field, the type whose projection selects its
     * rows and their table, whether it reads a list, its order's keys in sequence, the row key
     * completing them, the condition method its table has, {@code null} where it has none, the keys a
     * lookup narrows its rows by in key order, none for a plain read, and the {@code @orderBy} choice a
     * client picks its order by, {@code null} where it picks none, and for a connection the page it
     * serves where the client asks for none, {@code null} for a list or a record. A lookup's list
     * follows its keys rather than an order; a list with a choice reads the default order where the
     * client picks none.
     */
    public record Emitted(String coordinate, String parentType, String fieldName, String returnType,
                          TableRef table, boolean list, List<Key> order, Signature condition,
                          List<LookupKey> keys, Choice choice, Integer pageSize, Discriminated discriminated)
            implements Result {
        public Emitted {
            Objects.requireNonNull(coordinate, "coordinate");
            Objects.requireNonNull(parentType, "parentType");
            Objects.requireNonNull(fieldName, "fieldName");
            Objects.requireNonNull(returnType, "returnType");
            Objects.requireNonNull(table, "table");
            order = List.copyOf(order);
            keys = List.copyOf(keys);
        }
    }

    /**
     * A root whose rows are several tables', each type's rows those of its own table: its occurrence
     * and field, whether it reads a list, and each participant with its table and the condition method
     * that table has, {@code null} where it has none, how its rows are ordered, {@code null} where
     * they come in each table's key order, then by type, and for a connection the page it serves where
     * the client asks for none, {@code null} for a list or a record.
     */
    public record MultiTable(String coordinate, String parentType, String fieldName, boolean list,
                             List<Branch> branches, Ordered ordered, Integer pageSize) implements Result {
        public MultiTable {
            Objects.requireNonNull(coordinate, "coordinate");
            Objects.requireNonNull(parentType, "parentType");
            Objects.requireNonNull(fieldName, "fieldName");
            branches = List.copyOf(branches);
        }
    }

    /**
     * How a root over several tables orders its rows: the {@code @orderBy} argument a client picks an
     * order by, {@code null} where it has none, the order read where the client picks none, and each
     * order the argument offers, in its sort enum's order.
     */
    public record Ordered(OrderArgument argument, Order base, List<Order> named) {
        public Ordered {
            Objects.requireNonNull(base, "base");
            named = List.copyOf(named);
        }
    }

    /**
     * An {@code @orderBy} argument of a root over several tables: its name, its input type, whether it
     * is required and whether it takes a list of orders, and the input's sort and direction fields.
     */
    public record OrderArgument(String name, String inputType, boolean nonNull, boolean list, String sortField,
                                String directionField) {}

    /**
     * One order of a root over several tables: the sort enum value naming it, {@code null} for the order
     * read where the client picks none, and either the direction it reads every table's own key in, or
     * its keys by position, each taking one column per participant type.
     */
    public record Order(String name, String keyDirection, List<Position> positions) {
        public Order {
            positions = List.copyOf(positions);
        }
    }

    /** One position of an order over several tables: each participant type's key, and the position's direction and collation. */
    public record Position(Map<String, Key> keyByType, String direction, String collation) {
        public Position {
            keyByType = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(keyByType));
        }
    }

    /** One participant of a root over several tables: its type, its table and that table's condition method. */
    public record Branch(String typeName, TableRef table, Signature condition) {
        public Branch {
            Objects.requireNonNull(typeName, "typeName");
            Objects.requireNonNull(table, "table");
        }
    }

    /**
     * The participants of a root whose rows are told apart by a discriminator on the one table they
     * share: the discriminating column, each participant with its value and the fields it reads one
     * step off that table, and the fields its interface's participants answer under one alias.
     */
    public record Discriminated(ColumnRef column, List<Participant> participants, List<Joined> joined,
                                List<BaseTerm> baseSlice, List<String> sharedFields) {
        public Discriminated {
            Objects.requireNonNull(column, "column");
            participants = List.copyOf(participants);
            joined = List.copyOf(joined);
            baseSlice = List.copyOf(baseSlice);
            sharedFields = List.copyOf(sharedFields);
        }
    }

    /**
     * A participant on a detail table of its own, joined to the shared one by its key: its type and
     * value, the detail table, the step from it back to the shared table with the foreign key it rides,
     * and the detail columns only it projects, with their fields.
     */
    public record Joined(String typeName, String value, TableRef detail, List<Pair> toBase, ForeignKeyRef key,
                         Map<String, ColumnRef> details) {
        public Joined {
            toBase = List.copyOf(toBase);
            details = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(details));
        }
    }

    /** One term the shared table projects for the joined participants, once under one alias. */
    public sealed interface BaseTerm {
        /** An inherited field read off the shared table, under its field name. */
        record Inherited(String fieldName, ColumnRef baseColumn) implements BaseTerm {}

        /** A key column the detail table shares with the shared table, under the detail column's name. */
        record SharedKey(ColumnRef baseColumn, String alias) implements BaseTerm {}
    }

    /** One participant of a discriminated root: its type, its discriminator value and its cross columns. */
    public record Participant(String typeName, String value, List<Cross> crossColumns) {
        public Participant {
            crossColumns = List.copyOf(crossColumns);
        }
    }

    /**
     * A participant's scalar field read one step off the shared table: the field, the column read, the
     * table the step reaches, the step's column pairs, each from the shared table to the reached one,
     * and the foreign key the step rides, written or inferred.
     */
    public record Cross(String fieldName, ColumnRef column, TableRef target, List<Pair> pairs, ForeignKeyRef key) {
        public Cross {
            pairs = List.copyOf(pairs);
        }
    }

    /** One column pair of a step: the column on the table the step leaves and the one on the table it reaches. */
    public record Pair(ColumnRef from, ColumnRef to) {}

    /** One key of a lookup: the argument the caller hands it in, the column it matches, and whether it is a list. */
    public record LookupKey(String argument, ColumnRef column, boolean list) {}

    /**
     * An {@code @orderBy} argument: its name, its input type, whether it is required and whether it
     * takes a list of orders, the input's sort and direction fields, and each value the sort field
     * takes with the keys it sorts by, in the enum's order. A choice taken one at a time carries the
     * row key completing it; a list the client composes is completed once composed, by the table's row
     * key where the composition names none of the keys identifying a row, both empty where the table
     * has none.
     */
    public record Choice(String argument, String inputType, boolean nonNull, boolean list, String sortField,
                         String directionField, Map<String, List<Key>> orders, List<ColumnRef> rowKey,
                         List<List<ColumnRef>> identifyingKeys) {
        public Choice {
            orders = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(orders));
            rowKey = List.copyOf(rowKey);
            identifyingKeys = identifyingKeys.stream().map(List::copyOf).toList();
        }
    }

    /** One key of an order: the column, whether it holds nulls, its direction and its collation. */
    public record Key(ColumnRef column, boolean nullable, String direction, String collation) {}

    /** What the table's condition method takes beside the table and the arguments. */
    public record Signature(boolean takesContext, boolean takesSession) {}

    private record Producer(String coordinate, String kind, String shape, String parentType, String fieldName,
                            String returnType, String source, String schema, String table) {}

    /** The verdict of every root {@code SELECT} and {@code LOOKUP} producer of the graph, by occurrence. */
    public static Map<String, Result> read(StoreHandle store) {
        var p = GRAPHITRON_OCCURRENCE_PRODUCER;
        var r = GRAPHITRON_OCCURRENCE_PRODUCER_RESULT;
        var o = GRAPHITRON_OCCURRENCE;
        var f = GRAPHITRON_FIELD;
        var ft = GRAPHITRON_FIELD_TARGET;
        var tt = GRAPHITRON_TABLETYPE;
        var x = GRAPHITRON_OCCURRENCE_PARTICIPANT;
        var cn = GRAPHITRON_OCCURRENCE_PRODUCER_CONNECTION;
        var tc = GRAPHITRON_TENANT_COLUMN;
        var da = GRAPHITRON_DIRECTIVE_APPLICATION;
        var od = GRAPHITRON_OCCURRENCE_ORDER;
        var sk = GRAPHITRON_OCCURRENCE_SORT_KEY;
        var gc = GRAPHITRON_CONNECTION;
        var ge = GRAPHITRON_EDGE;
        var te = GRAPHITRON_TABLETYPE.as("te");
        var cc = GRAPHITRON_CONNECTION_CARRIER;
        var pn = GRAPHITRON_OCCURRENCE_PRODUCER_NODE;
        var graph = store.graphName();
        // A connection's records are its edge's items, whose table is their type's, and not the
        // producer's result, which is the connection.
        var source = coalesce(te.TABLE_SOURCE_NAME, r.TABLE_SOURCE_NAME);
        var schema = coalesce(te.TABLE_SCHEMA, r.TABLE_SCHEMA);
        var name = coalesce(te.TABLE_NAME, r.TABLE_NAME);
        var producers = store.dsl()
            .select(p.COORDINATE, p.KIND, r.SHAPE, f.TYPE_NAME, f.FIELD_NAME, ft.TARGET_TYPE_NAME,
                source, schema, name,
                field(exists(selectOne().from(x).where(x.GRAPH_NAME.eq(p.GRAPH_NAME), x.COORDINATE.eq(p.COORDINATE)))),
                cn.FACETED,
                field(exists(selectOne().from(tc).where(tc.GRAPH_NAME.eq(p.GRAPH_NAME), tc.SOURCE_NAME.eq(source),
                    tc.TABLE_SCHEMA.eq(schema), tc.TABLE_NAME.eq(name)))),
                field(exists(selectOne().from(da).where(da.GRAPH_NAME.eq(f.GRAPH_NAME), da.COORDINATE.eq(f.COORDINATE),
                    da.DIRECTIVE_NAME.eq("pivot")))),
                field(exists(selectOne().from(od).where(od.GRAPH_NAME.eq(p.GRAPH_NAME), od.COORDINATE.eq(p.COORDINATE),
                    od.KIND.ne("DEFAULT")))),
                field(exists(selectOne().from(sk).where(sk.GRAPH_NAME.eq(p.GRAPH_NAME), sk.COORDINATE.eq(p.COORDINATE),
                    sk.KIND.ne("COLUMN")))),
                field(exists(selectOne().from(tt).where(tt.GRAPH_NAME.eq(ft.GRAPH_NAME),
                    tt.TYPE_NAME.eq(ft.TARGET_TYPE_NAME), tt.TABLE_SOURCE_NAME.eq(r.TABLE_SOURCE_NAME),
                    tt.TABLE_SCHEMA.eq(r.TABLE_SCHEMA), tt.TABLE_NAME.eq(r.TABLE_NAME)))),
                ge.ITEM_TYPE_NAME, cc.DEFAULT_FIRST_VALUE,
                field(exists(selectOne().from(pn).where(pn.GRAPH_NAME.eq(p.GRAPH_NAME), pn.COORDINATE.eq(p.COORDINATE)))))
            .from(p)
            .join(o).on(o.GRAPH_NAME.eq(p.GRAPH_NAME), o.COORDINATE.eq(p.COORDINATE))
            .join(f).on(f.GRAPH_NAME.eq(o.GRAPH_NAME), f.COORDINATE.eq(o.ELEMENT_COORDINATE))
            .leftJoin(ft).on(ft.GRAPH_NAME.eq(f.GRAPH_NAME), ft.TYPE_NAME.eq(f.TYPE_NAME), ft.FIELD_NAME.eq(f.FIELD_NAME))
            .leftJoin(r).on(r.GRAPH_NAME.eq(p.GRAPH_NAME), r.COORDINATE.eq(p.COORDINATE))
            .leftJoin(cn).on(cn.GRAPH_NAME.eq(p.GRAPH_NAME), cn.COORDINATE.eq(p.COORDINATE))
            .leftJoin(gc).on(gc.GRAPH_NAME.eq(cn.GRAPH_NAME), gc.TYPE_NAME.eq(cn.CONNECTION_TYPE_NAME))
            .leftJoin(ge).on(ge.GRAPH_NAME.eq(gc.GRAPH_NAME), ge.TYPE_NAME.eq(gc.EDGE_TYPE_NAME))
            .leftJoin(te).on(te.GRAPH_NAME.eq(ge.GRAPH_NAME), te.TYPE_NAME.eq(ge.ITEM_TYPE_NAME))
            .leftJoin(cc).on(cc.GRAPH_NAME.eq(f.GRAPH_NAME), cc.COORDINATE.eq(f.COORDINATE))
            .where(p.GRAPH_NAME.eq(graph), p.START.eq("ROOT"), p.KIND.in("SELECT", "LOOKUP"))
            .orderBy(p.COORDINATE)
            .fetch();

        var orders = orders(store);
        var signatures = signatures(store);
        var lookups = lookups(store);
        var choices = choices(store);
        var maxPageSize = maxPageSize(store);
        var discriminated = discriminated(store);
        boolean multiTenant = store.dsl().fetchExists(STORE_GRAPH_TENANT_COLUMN,
            STORE_GRAPH_TENANT_COLUMN.GRAPH_NAME.eq(graph));
        var out = new LinkedHashMap<String, Result>();
        for (var row : producers) {
            if (Boolean.TRUE.equals(row.value19())) {
                out.put(row.value1(), new NodeFetcher(row.value1(), row.value4(), row.value5(),
                    "RECORD_LIST".equals(row.value3()), multiTenant));
                continue;
            }
            boolean connection = row.value11() != null;
            var at = new Producer(row.value1(), row.value2(), row.value3(), row.value4(), row.value5(),
                connection ? row.value17() : row.value6(), row.value7(), row.value8(), row.value9());
            Reason reason = null;
            var told = discriminated.get(at.coordinate());
            if (Boolean.TRUE.equals(row.value10()) && told == null) {
                Integer pageSize = !connection ? null
                    : row.value18() != null ? row.value18() : ConnectionDefaults.defaultPageSize(maxPageSize);
                out.put(at.coordinate(), multiTable(store, at, Boolean.TRUE.equals(row.value11()), pageSize,
                    signatures));
                continue;
            }
            if (told != null && told.reason() != null) reason = told.reason();
            // A discriminated lookup joins its keys' rows on the shared table, a source of its own.
            else if (told != null && "LOOKUP".equals(at.kind())) reason = Reason.POLYMORPHIC;
            else if (Boolean.TRUE.equals(row.value11())) reason = Reason.FACETED;
            else if (at.table() == null || at.returnType() == null
                || !(connection || Boolean.TRUE.equals(row.value16()))
                || !(connection || "RECORD_LIST".equals(at.shape()) || "SINGLE_RECORD".equals(at.shape()))) {
                reason = Reason.NOT_ONE_TABLE;
            }
            else if (Boolean.TRUE.equals(row.value12())) reason = Reason.TENANTED;
            else if (Boolean.TRUE.equals(row.value13())) reason = Reason.PIVOT;
            else if ("LOOKUP".equals(at.kind())) reason = lookups.getOrDefault(at.coordinate(), new Keys(List.of(),
                Reason.NOT_ONE_TABLE)).reason();
            else if (Boolean.TRUE.equals(row.value15())) reason = Reason.SORT_KEY_NOT_A_COLUMN;
            else if (connection && !orders.containsKey(at.coordinate()) && !choices.containsKey(at.coordinate())) {
                reason = Reason.NO_ORDER;
            }
            if (reason != null) {
                out.put(at.coordinate(), new NotYet(at.coordinate(), reason));
                continue;
            }
            var table = tableRef(store, at.source(), at.schema(), at.table());
            if (table == null) {
                out.put(at.coordinate(), new NotYet(at.coordinate(), Reason.NOT_ONE_TABLE));
                continue;
            }
            boolean list = connection || "RECORD_LIST".equals(at.shape());
            boolean lookup = "LOOKUP".equals(at.kind());
            Integer pageSize = !connection ? null
                : row.value18() != null ? row.value18() : ConnectionDefaults.defaultPageSize(maxPageSize);
            out.put(at.coordinate(), new Emitted(at.coordinate(), at.parentType(), at.fieldName(), at.returnType(),
                table, list, list && !lookup ? orders.getOrDefault(at.coordinate(), List.of()) : List.of(),
                signatures.get(List.of(at.coordinate(), at.schema() + "." + at.table())),
                lookup ? lookups.get(at.coordinate()).keys() : List.of(), choices.get(at.coordinate()), pageSize,
                told == null ? null : told.discriminated()));
        }
        return out;
    }

    /**
     * A root over several tables, each participant's rows those of its own table: stated whole where it
     * is a list, a record or a connection without facets, no node-id argument names a participant by the
     * id's type, and no participant's table carries the tenant column. {@code pageSize} is a
     * connection's page where the client asks for none, {@code null} for a list or a record.
     */
    private static Result multiTable(StoreHandle store, Producer at, boolean faceted, Integer pageSize,
                                     Map<List<String>, Signature> signatures) {
        var graph = store.graphName();
        String coordinate = at.coordinate();
        boolean connection = pageSize != null;
        if ("LOOKUP".equals(at.kind())
                || !(connection || "RECORD_LIST".equals(at.shape()) || "SINGLE_RECORD".equals(at.shape()))) {
            return new NotYet(coordinate, Reason.POLYMORPHIC);
        }
        if (faceted) {
            return new NotYet(coordinate, Reason.FACETED);
        }
        var x = GRAPHITRON_OCCURRENCE_PARTICIPANT;
        var participants = store.dsl()
            .select(x.PARTICIPANT_TYPE, x.TABLE_SOURCE_NAME, x.TABLE_SCHEMA, x.TABLE_NAME)
            .from(x)
            .where(x.GRAPH_NAME.eq(graph), x.COORDINATE.eq(coordinate), x.RECOGNIZED_BY.eq("TABLE"))
            .orderBy(x.PARTICIPANT_TYPE)
            .fetch();
        var sk = GRAPHITRON_OCCURRENCE_SORT_KEY;
        if (store.dsl().fetchExists(sk, sk.GRAPH_NAME.eq(graph), sk.COORDINATE.eq(coordinate), sk.KIND.ne("COLUMN"))) {
            return new NotYet(coordinate, Reason.SORT_KEY_NOT_A_COLUMN);
        }
        var ni = GRAPHITRON_OCCURRENCE_NODE_ID;
        var oa = GRAPHITRON_OCCURRENCE;
        if (store.dsl().fetchExists(selectOne().from(ni)
                .join(oa).on(oa.GRAPH_NAME.eq(ni.GRAPH_NAME), oa.COORDINATE.eq(ni.COORDINATE))
                .where(ni.GRAPH_NAME.eq(graph), oa.FIELD_COORDINATE.eq(coordinate),
                    oa.ARGUMENT_COORDINATE.eq(oa.COORDINATE),
                    ni.NODE_TYPE_NAME.in(participants.map(r -> r.value1()))))) {
            return new NotYet(coordinate, Reason.NODE_ID_DISPATCH);
        }
        var tc = GRAPHITRON_TENANT_COLUMN;
        var branches = new ArrayList<Branch>();
        var byBound = new LinkedHashMap<String, Branch>();
        for (var participant : participants) {
            if (store.dsl().fetchExists(tc, tc.GRAPH_NAME.eq(graph), tc.SOURCE_NAME.eq(participant.value2()),
                    tc.TABLE_SCHEMA.eq(participant.value3()), tc.TABLE_NAME.eq(participant.value4()))) {
                return new NotYet(coordinate, Reason.TENANTED);
            }
            var table = tableRef(store, participant.value2(), participant.value3(), participant.value4());
            if (table == null) {
                return new NotYet(coordinate, Reason.NOT_ONE_TABLE);
            }
            String bound = participant.value3() + "." + participant.value4();
            var branch = new Branch(participant.value1(), table, signatures.get(List.of(coordinate, bound)));
            branches.add(branch);
            byBound.put(bound, branch);
        }
        if (branches.isEmpty()) {
            return new NotYet(coordinate, Reason.POLYMORPHIC);
        }
        return new MultiTable(coordinate, at.parentType(), at.fieldName(),
            connection || "RECORD_LIST".equals(at.shape()), branches, ordered(store, coordinate, byBound, connection),
            pageSize);
    }

    /**
     * How a root over several tables orders its rows, read off its orders per participant table:
     * {@code null} where its one order is each table's own key ascending, no argument chooses another,
     * and it is no connection, whose cursor encodes the order it reads. An order whose keys are each table's own primary key, in one direction, reads the keys;
     * any other its keys by position, which line up across the tables, {@code ORDER_POSITIONS_DISAGREE}
     * refusing an order whose do not.
     */
    private static Ordered ordered(StoreHandle store, String coordinate, Map<String, Branch> typeByBound,
                                   boolean connection) {
        var graph = store.graphName();
        var od = GRAPHITRON_OCCURRENCE_ORDER;
        var sk = GRAPHITRON_OCCURRENCE_SORT_KEY;
        var kc = GRAPHITRON_OCCURRENCE_SORT_KEY_COLUMN;
        var ev = GRAPHITRON_ENUM_VALUE;
        var c = SQL_COLUMN;
        var rows = store.dsl()
            .select(od.CHOICE, od.KIND, ev.VALUE_NAME, od.BOUND, sk.POSITION, sk.DIRECTION, sk.COLLATION,
                c.COLUMN_NAME, c.JOOQ_NAME, c.BINDING_TYPE, c.DATABASE_TYPE, c.NULLABLE)
            .from(od)
            .leftJoin(ev).on(ev.GRAPH_NAME.eq(od.GRAPH_NAME), ev.COORDINATE.eq(od.CHOICE), od.KIND.eq("CHOICE"))
            .leftJoin(sk).on(sk.GRAPH_NAME.eq(od.GRAPH_NAME), sk.COORDINATE.eq(od.COORDINATE), sk.BOUND.eq(od.BOUND),
                sk.CHOICE.eq(od.CHOICE))
            .leftJoin(kc).on(kc.GRAPH_NAME.eq(sk.GRAPH_NAME), kc.COORDINATE.eq(sk.COORDINATE), kc.BOUND.eq(sk.BOUND),
                kc.CHOICE.eq(sk.CHOICE), kc.POSITION.eq(sk.POSITION))
            .leftJoin(c).on(c.SOURCE_NAME.eq(kc.TABLE_SOURCE_NAME), c.TABLE_SCHEMA.eq(kc.TABLE_SCHEMA),
                c.TABLE_NAME.eq(kc.TABLE_NAME), c.COLUMN_NAME.eq(kc.COLUMN_NAME))
            .where(od.GRAPH_NAME.eq(graph), od.COORDINATE.eq(coordinate))
            .orderBy(od.KIND.desc(), ev.ORDINAL, od.BOUND, sk.POSITION)
            .fetch(r -> new SortRow(r.value1(), r.value2(), r.value3(), r.value4(),
                r.value8() == null ? null : new Key(new ColumnRef(r.value8(), r.value9(), r.value10(), r.value11()),
                    Boolean.TRUE.equals(r.value12()), r.value6(), r.value7())));
        // Each order, by its choice, in the enum's order after the default, its keys by bound.
        var byChoice = new LinkedHashMap<String, LinkedHashMap<String, List<Key>>>();
        var kindOf = new LinkedHashMap<String, SortRow>();
        for (var row : rows) {
            kindOf.putIfAbsent(row.choice(), row);
            var byBound = byChoice.computeIfAbsent(row.choice(), k -> new LinkedHashMap<>());
            var keys = byBound.computeIfAbsent(row.bound(), k -> new ArrayList<>());
            if (row.key() != null) {
                keys.add(row.key());
            }
        }
        Order base = null;
        var named = new ArrayList<Order>();
        for (var entry : byChoice.entrySet()) {
            var head = kindOf.get(entry.getKey());
            if ("CHOICE".equals(head.kind())) {
                named.add(order(coordinate, head.valueName(), entry.getValue(), typeByBound));
            } else {
                base = order(coordinate, null, entry.getValue(), typeByBound);
            }
        }
        if (base == null) {
            base = new Order(null, "ASC", List.of());
        }
        var argument = orderArgument(store, coordinate);
        if (argument == null && "ASC".equals(base.keyDirection()) && !connection) {
            return null;
        }
        return new Ordered(argument, base, argument == null ? List.of() : named);
    }

    /** One stored order row of a root over several tables: its choice, kind and enum value, its table, and one key. */
    private record SortRow(String choice, String kind, String valueName, String bound, Key key) {}

    /** One order over several tables from its keys by bound: on every table's own key, or by position. */
    private static Order order(String coordinate, String name, Map<String, List<Key>> byBound,
                               Map<String, Branch> typeByBound) {
        String keyDirection = null;
        boolean onKeys = true;
        for (var entry : byBound.entrySet()) {
            var keys = entry.getValue();
            var primaryKey = typeByBound.get(entry.getKey()).table().primaryKeyColumns();
            if (keys.size() != primaryKey.size()) {
                onKeys = false;
            }
            for (int i = 0; onKeys && i < keys.size(); i++) {
                var key = keys.get(i);
                if (!key.column().sqlName().equalsIgnoreCase(primaryKey.get(i).sqlName())
                        || keyDirection != null && !keyDirection.equals(key.direction())) {
                    onKeys = false;
                }
                keyDirection = key.direction();
            }
        }
        if (onKeys) {
            return new Order(name, keyDirection == null ? "ASC" : keyDirection, List.of());
        }
        int arity = byBound.values().iterator().next().size();
        if (byBound.values().stream().anyMatch(keys -> keys.size() != arity)) {
            throw new IllegalStateException("Graphitron generator bug (root order across participants): order "
                + (name == null ? "default" : name) + " of " + coordinate + " has keys differing in number"
                + " across its tables, which ORDER_POSITIONS_DISAGREE refuses before generation");
        }
        var positions = new ArrayList<Position>();
        for (int i = 0; i < arity; i++) {
            var keyByType = new LinkedHashMap<String, Key>();
            Key first = null;
            for (var entry : byBound.entrySet()) {
                var key = entry.getValue().get(i);
                keyByType.put(typeByBound.get(entry.getKey()).typeName(), key);
                if (first == null) {
                    first = key;
                }
            }
            positions.add(new Position(keyByType, first.direction(), first.collation()));
        }
        return new Order(name, null, positions);
    }

    /** The {@code @orderBy} argument a root over several tables offers its orders by, {@code null} where it has none. */
    private static OrderArgument orderArgument(StoreHandle store, String coordinate) {
        var od = GRAPHITRON_OCCURRENCE_ORDER;
        var ao = GRAPHITRON_OCCURRENCE.as("ao");
        var ga = GRAPHITRON_ARGUMENT;
        var oi = GRAPHITRON_ORDER_INPUT;
        return store.dsl()
            .selectDistinct(ao.NAME, ga.NAMED_TYPE, ga.NON_NULL, ga.LIST_DEPTH, oi.SORT_FIELD_NAME,
                oi.DIRECTION_FIELD_NAME)
            .from(od)
            .join(ao).on(ao.GRAPH_NAME.eq(od.GRAPH_NAME), ao.COORDINATE.eq(od.ARGUMENT_COORDINATE))
            .join(ga).on(ga.GRAPH_NAME.eq(ao.GRAPH_NAME), ga.COORDINATE.eq(ao.ELEMENT_COORDINATE))
            .join(oi).on(oi.GRAPH_NAME.eq(ga.GRAPH_NAME), oi.TYPE_NAME.eq(ga.NAMED_TYPE))
            .where(od.GRAPH_NAME.eq(store.graphName()), od.COORDINATE.eq(coordinate), od.KIND.eq("CHOICE"))
            .limit(1)
            .fetchOne(r -> new OrderArgument(r.value1(), r.value2(), Boolean.TRUE.equals(r.value3()), r.value4() > 0,
                r.value5(), r.value6()));
    }

    /**
     * Each root list's {@code @orderBy} argument taking one order: the argument and its order input's
     * fields, and each choice's keys in sequence, then the row-key columns the store completes it with.
     * An argument taking a list of orders is none of these: its completion follows what the client
     * lists, at run time.
     */
    private static Map<String, Choice> choices(StoreHandle store) {
        var od = GRAPHITRON_OCCURRENCE_ORDER;
        var p = GRAPHITRON_OCCURRENCE_PRODUCER;
        var ao = GRAPHITRON_OCCURRENCE.as("ao");
        var ga = GRAPHITRON_ARGUMENT;
        var oi = GRAPHITRON_ORDER_INPUT;
        var ev = GRAPHITRON_ENUM_VALUE;
        var sk = GRAPHITRON_OCCURRENCE_SORT_KEY;
        var kc = GRAPHITRON_OCCURRENCE_SORT_KEY_COLUMN;
        var sc = GRAPHITRON_OCCURRENCE_SORT_KEY_COMPLETION;
        var c = SQL_COLUMN;
        record Head(String argument, String inputType, boolean nonNull, boolean list, String sortField,
                    String directionField) {}
        var heads = new LinkedHashMap<String, Head>();
        var keys = new LinkedHashMap<String, Map<String, List<Key>>>();
        // The argument, read whether or not its choices sort by columns: a choice with a path key
        // still takes one order, and the sort-key check holds it back.
        store.dsl()
            .selectDistinct(od.COORDINATE, ao.NAME, ga.NAMED_TYPE, ga.NON_NULL, ga.LIST_DEPTH, oi.SORT_FIELD_NAME,
                oi.DIRECTION_FIELD_NAME)
            .from(od)
            .join(p).on(p.GRAPH_NAME.eq(od.GRAPH_NAME), p.COORDINATE.eq(od.COORDINATE), p.START.eq("ROOT"))
            .join(ao).on(ao.GRAPH_NAME.eq(od.GRAPH_NAME), ao.COORDINATE.eq(od.ARGUMENT_COORDINATE))
            .join(ga).on(ga.GRAPH_NAME.eq(ao.GRAPH_NAME), ga.COORDINATE.eq(ao.ELEMENT_COORDINATE))
            .join(oi).on(oi.GRAPH_NAME.eq(ga.GRAPH_NAME), oi.TYPE_NAME.eq(ga.NAMED_TYPE))
            .where(od.GRAPH_NAME.eq(store.graphName()), od.KIND.eq("CHOICE"))
            .orderBy(od.COORDINATE)
            .forEach(row -> heads.putIfAbsent(row.value1(), new Head(row.value2(), row.value3(),
                Boolean.TRUE.equals(row.value4()), row.value5() > 0, row.value6(), row.value7())));
        store.dsl()
            .select(od.COORDINATE, ev.VALUE_NAME, c.COLUMN_NAME, c.JOOQ_NAME, c.BINDING_TYPE, c.DATABASE_TYPE,
                c.NULLABLE, sk.DIRECTION, sk.COLLATION)
            .from(od)
            .join(p).on(p.GRAPH_NAME.eq(od.GRAPH_NAME), p.COORDINATE.eq(od.COORDINATE), p.START.eq("ROOT"))
            .join(ev).on(ev.GRAPH_NAME.eq(od.GRAPH_NAME), ev.COORDINATE.eq(od.CHOICE))
            .join(sk).on(sk.GRAPH_NAME.eq(od.GRAPH_NAME), sk.COORDINATE.eq(od.COORDINATE), sk.BOUND.eq(od.BOUND),
                sk.CHOICE.eq(od.CHOICE))
            .join(kc).on(kc.GRAPH_NAME.eq(sk.GRAPH_NAME), kc.COORDINATE.eq(sk.COORDINATE), kc.BOUND.eq(sk.BOUND),
                kc.CHOICE.eq(sk.CHOICE), kc.POSITION.eq(sk.POSITION))
            .join(c).on(c.SOURCE_NAME.eq(kc.TABLE_SOURCE_NAME), c.TABLE_SCHEMA.eq(kc.TABLE_SCHEMA),
                c.TABLE_NAME.eq(kc.TABLE_NAME), c.COLUMN_NAME.eq(kc.COLUMN_NAME))
            .where(od.GRAPH_NAME.eq(store.graphName()), od.KIND.eq("CHOICE"))
            .orderBy(od.COORDINATE, ev.ORDINAL, sk.POSITION)
            .forEach(row -> keys.computeIfAbsent(row.value1(), k -> new LinkedHashMap<>())
                .computeIfAbsent(row.value2(), k -> new ArrayList<>())
                .add(new Key(new ColumnRef(row.value3(), row.value4(), row.value5(), row.value6()),
                    Boolean.TRUE.equals(row.value7()), row.value8(), row.value9())));
        store.dsl()
            .select(sc.COORDINATE, ev.VALUE_NAME, c.COLUMN_NAME, c.JOOQ_NAME, c.BINDING_TYPE, c.DATABASE_TYPE,
                sc.DIRECTION)
            .from(sc)
            .join(od).on(od.GRAPH_NAME.eq(sc.GRAPH_NAME), od.COORDINATE.eq(sc.COORDINATE), od.BOUND.eq(sc.BOUND),
                od.CHOICE.eq(sc.CHOICE))
            .join(ev).on(ev.GRAPH_NAME.eq(od.GRAPH_NAME), ev.COORDINATE.eq(od.CHOICE))
            .join(c).on(c.SOURCE_NAME.eq(sc.TABLE_SOURCE_NAME), c.TABLE_SCHEMA.eq(sc.TABLE_SCHEMA),
                c.TABLE_NAME.eq(sc.TABLE_NAME), c.COLUMN_NAME.eq(sc.COLUMN_NAME))
            .where(sc.GRAPH_NAME.eq(store.graphName()), od.KIND.eq("CHOICE"))
            .orderBy(sc.COORDINATE, ev.ORDINAL, sc.POSITION)
            .forEach(row -> {
                var byValue = keys.get(row.value1());
                if (byValue != null && byValue.containsKey(row.value2())) {
                    byValue.get(row.value2()).add(new Key(new ColumnRef(row.value3(), row.value4(), row.value5(),
                        row.value6()), false, row.value7(), null));
                }
            });
        // The keys identifying a row of the table, by rank: the smallest the row key completing a
        // composed list, every one a key whose presence in full leaves it complete as composed.
        var ik = GRAPHITRON_OCCURRENCE_IDENTIFYING_KEY_COLUMN;
        var identifying = new LinkedHashMap<String, LinkedHashMap<Integer, List<ColumnRef>>>();
        store.dsl()
            .select(ik.COORDINATE, ik.RANK, c.COLUMN_NAME, c.JOOQ_NAME, c.BINDING_TYPE, c.DATABASE_TYPE)
            .from(ik)
            .join(p).on(p.GRAPH_NAME.eq(ik.GRAPH_NAME), p.COORDINATE.eq(ik.COORDINATE), p.START.eq("ROOT"))
            .join(c).on(c.SOURCE_NAME.eq(ik.TABLE_SOURCE_NAME), c.TABLE_SCHEMA.eq(ik.TABLE_SCHEMA),
                c.TABLE_NAME.eq(ik.TABLE_NAME), c.COLUMN_NAME.eq(ik.COLUMN_NAME))
            .where(ik.GRAPH_NAME.eq(store.graphName()))
            .orderBy(ik.COORDINATE, ik.RANK, ik.POSITION)
            .forEach(row -> identifying.computeIfAbsent(row.value1(), k -> new LinkedHashMap<>())
                .computeIfAbsent(row.value2(), k -> new ArrayList<>())
                .add(new ColumnRef(row.value3(), row.value4(), row.value5(), row.value6())));
        var out = new LinkedHashMap<String, Choice>();
        heads.forEach((coordinate, head) -> {
            var ranked = List.copyOf(identifying.getOrDefault(coordinate, new LinkedHashMap<>()).values());
            out.put(coordinate, new Choice(head.argument(), head.inputType(), head.nonNull(), head.list(),
                head.sortField(), head.directionField(), keys.getOrDefault(coordinate, Map.of()),
                ranked.isEmpty() ? List.of() : ranked.getFirst(), ranked));
        });
        return out;
    }

    /** A discriminated root's participants, or the reason the store does not state them whole yet. */
    private record Told(Discriminated discriminated, Reason reason) {}

    /**
     * Each root whose participants are told apart by a discriminator: where every participant is a row
     * of the one table they share, the discriminating column, each participant's value and cross
     * columns, and the shared fields of the interface the root answers; where one reads a detail table
     * of its own, that reason. A root whose participants are told apart by table is none of these.
     */
    private static Map<String, Told> discriminated(StoreHandle store) {
        var x = GRAPHITRON_OCCURRENCE_PARTICIPANT;
        var p = GRAPHITRON_OCCURRENCE_PRODUCER;
        var o = GRAPHITRON_OCCURRENCE;
        var f = GRAPHITRON_FIELD;
        var ft = GRAPHITRON_FIELD_TARGET;
        var c = SQL_COLUMN;
        var graph = store.graphName();
        record Stated(String coordinate, String type, String recognizedBy, String source, String schema, String table,
                      String base, String column, String value, String target, String baseSource, String baseSchema) {}
        var byCoordinate = new LinkedHashMap<String, List<Stated>>();
        store.dsl()
            .select(x.COORDINATE, x.PARTICIPANT_TYPE, x.RECOGNIZED_BY, x.TABLE_SOURCE_NAME, x.TABLE_SCHEMA,
                x.TABLE_NAME, x.BASE_NAME, x.DISCRIMINATOR_COLUMN, x.DISCRIMINATOR_VALUE, ft.TARGET_TYPE_NAME,
                x.BASE_SOURCE_NAME, x.BASE_SCHEMA)
            .from(x)
            .join(p).on(p.GRAPH_NAME.eq(x.GRAPH_NAME), p.COORDINATE.eq(x.COORDINATE), p.START.eq("ROOT"))
            .join(o).on(o.GRAPH_NAME.eq(p.GRAPH_NAME), o.COORDINATE.eq(p.COORDINATE))
            .join(f).on(f.GRAPH_NAME.eq(o.GRAPH_NAME), f.COORDINATE.eq(o.ELEMENT_COORDINATE))
            .join(ft).on(ft.GRAPH_NAME.eq(f.GRAPH_NAME), ft.TYPE_NAME.eq(f.TYPE_NAME), ft.FIELD_NAME.eq(f.FIELD_NAME))
            .where(x.GRAPH_NAME.eq(graph))
            .orderBy(x.COORDINATE, x.PARTICIPANT_TYPE)
            .forEach(r -> byCoordinate.computeIfAbsent(r.value1(), k -> new ArrayList<>()).add(new Stated(r.value1(),
                r.value2(), r.value3(), r.value4(), r.value5(), r.value6(), r.value7(), r.value8(), r.value9(),
                r.value10(), r.value11(), r.value12())));
        var sf = GRAPHITRON_INTERFACE_SHARED_FIELD;
        var shared = new LinkedHashMap<String, List<String>>();
        store.dsl().select(sf.INTERFACE_TYPE, sf.FIELD_NAME).from(sf).where(sf.GRAPH_NAME.eq(graph))
            .orderBy(sf.INTERFACE_TYPE, sf.FIELD_NAME)
            .forEach(r -> shared.computeIfAbsent(r.value1(), k -> new ArrayList<>()).add(r.value2()));
        // The types some discriminated read takes as participants on the shared table: one of them on a
        // detail table elsewhere would alias its inherited references two ways.
        var singleTableParticipants = new java.util.HashSet<String>();
        byCoordinate.values().forEach(stated -> stated.stream()
            .filter(st -> "DISCRIMINATOR".equals(st.recognizedBy()) && st.table().equals(st.base()))
            .forEach(st -> singleTableParticipants.add(st.type())));
        var out = new LinkedHashMap<String, Told>();
        byCoordinate.forEach((coordinate, stated) -> {
            if (stated.stream().noneMatch(s -> "DISCRIMINATOR".equals(s.recognizedBy()))) {
                return;
            }
            if (stated.stream().anyMatch(s -> !"DISCRIMINATOR".equals(s.recognizedBy()))) {
                out.put(coordinate, new Told(null, Reason.POLYMORPHIC));
                return;
            }
            var first = stated.getFirst();
            var column = store.dsl().select(c.COLUMN_NAME, c.JOOQ_NAME, c.BINDING_TYPE, c.DATABASE_TYPE)
                .from(c)
                .where(c.SOURCE_NAME.eq(first.baseSource()), c.TABLE_SCHEMA.eq(first.baseSchema()),
                    c.TABLE_NAME.eq(first.base()), c.COLUMN_NAME.equalIgnoreCase(first.column()))
                .fetchOne(r -> new ColumnRef(r.value1(), r.value2(), r.value3(), r.value4()));
            var participants = new ArrayList<Participant>();
            var joined = new ArrayList<Joined>();
            var baseSlice = new ArrayList<BaseTerm>();
            var seen = new java.util.HashSet<String>();
            Reason reason = column == null ? Reason.NOT_ONE_TABLE : null;
            for (var s : stated) {
                if (reason != null) {
                    break;
                }
                if (s.table().equals(s.base())) {
                    var crosses = crossColumns(store, s.type());
                    if (crosses == null) {
                        reason = Reason.NOT_ONE_TABLE;
                    } else {
                        participants.add(new Participant(s.type(), s.value(), crosses));
                    }
                } else {
                    var detail = tableRef(store, s.source(), s.schema(), s.table());
                    var join = detail == null ? null : joined(store, s.type(), s.value(), detail, s.base(),
                        singleTableParticipants, baseSlice, seen);
                    if (join == null) {
                        reason = Reason.JOINED_DETAIL;
                    } else {
                        joined.add(join);
                    }
                }
            }
            out.put(coordinate, reason == null
                ? new Told(new Discriminated(column, participants, joined, baseSlice,
                    shared.getOrDefault(first.target(), List.of())), null)
                : new Told(null, reason));
        });
        return out;
    }

    /**
     * A participant on a detail table of its own, or {@code null} where the joined read does not state it
     * whole. Its step back to the shared table is the one key path of a field of its own reaching that
     * table in one step, the inherited field that names the join. Each field reading a column is placed
     * by the table the column is on: one on the shared table is an inherited field the shared table
     * projects under the field's name, one of the step's own columns a key it projects under the
     * detail column's name, any other a detail column only this participant projects. The shared
     * table's terms are added to {@code baseSlice} once per alias, the first participant's winning.
     */
    private static Joined joined(StoreHandle store, String type, String value, TableRef detail, String base,
                                 java.util.Set<String> singleTableParticipants, List<BaseTerm> baseSlice,
                                 java.util.Set<String> seen) {
        var f = GRAPHITRON_FIELD;
        var oc = GRAPHITRON_OCCURRENCE_COLUMN;
        var ni = GRAPHITRON_OCCURRENCE_NODE_ID;
        var sp = GRAPHITRON_JOIN_PATH_STEP;
        var c = SQL_COLUMN;
        var graph = store.graphName();
        String toBase = null;
        List<Pair> pairs = null;
        for (var field : store.dsl().select(f.FIELD_NAME).from(f)
                .where(f.GRAPH_NAME.eq(graph), f.TYPE_NAME.eq(type)).orderBy(f.ORDINAL).fetch(f.FIELD_NAME)) {
            String coordinate = type + "." + field;
            var steps = store.dsl().select(sp.POSITION, sp.FROM_TABLE, sp.TO_TABLE).from(sp)
                .where(sp.GRAPH_NAME.eq(graph), sp.COORDINATE.eq(coordinate)).fetch();
            if (steps.size() == 1 && steps.getFirst().value1() == 0 && detail.tableName().equals(steps.getFirst().value2())
                    && base.equals(steps.getFirst().value3())) {
                toBase = coordinate;
                pairs = stepPairs(store, coordinate);
                break;
            }
        }
        if (toBase == null || pairs.isEmpty()) {
            return null;
        }
        var key = foreignKey(store, toBase);
        if (key == null) {
            return null;
        }
        var details = new LinkedHashMap<String, ColumnRef>();
        for (var field : store.dsl().select(f.FIELD_NAME).from(f)
                .where(f.GRAPH_NAME.eq(graph), f.TYPE_NAME.eq(type)).orderBy(f.ORDINAL).fetch(f.FIELD_NAME)) {
            String coordinate = type + "." + field;
            if (store.dsl().fetchExists(ni, ni.GRAPH_NAME.eq(graph), ni.COORDINATE.eq(coordinate))) {
                return null;
            }
            var read = store.dsl().select(oc.TABLE_NAME, c.COLUMN_NAME, c.JOOQ_NAME, c.BINDING_TYPE, c.DATABASE_TYPE)
                .from(oc)
                .join(c).on(c.SOURCE_NAME.eq(oc.TABLE_SOURCE_NAME), c.TABLE_SCHEMA.eq(oc.TABLE_SCHEMA),
                    c.TABLE_NAME.eq(oc.TABLE_NAME), c.COLUMN_NAME.eq(oc.COLUMN_NAME))
                .where(oc.GRAPH_NAME.eq(graph), oc.COORDINATE.eq(coordinate))
                .fetch();
            if (read.size() != 1) {
                continue;
            }
            var row = read.getFirst();
            var column = new ColumnRef(row.value2(), row.value3(), row.value4(), row.value5());
            if (base.equals(row.value1())) {
                if (singleTableParticipants.contains(type)) {
                    return null;
                }
                if (seen.add(field)) {
                    baseSlice.add(new BaseTerm.Inherited(field, column));
                }
            } else if (detail.tableName().equals(row.value1())) {
                var shared = pairs.stream()
                    .filter(pair -> pair.from().sqlName().equalsIgnoreCase(column.sqlName()))
                    .findFirst();
                if (shared.isPresent()) {
                    if (seen.add(column.sqlName())) {
                        baseSlice.add(new BaseTerm.SharedKey(shared.get().to(), column.sqlName()));
                    }
                } else {
                    details.put(field, column);
                }
            }
        }
        return new Joined(type, value, detail, pairs, key, details);
    }

    /** The column pairs of the one step of {@code coordinate}'s path, each from the table it leaves to the one it reaches. */
    private static List<Pair> stepPairs(StoreHandle store, String coordinate) {
        var sc = GRAPHITRON_JOIN_PATH_STEP_COLUMN;
        var fc = SQL_COLUMN.as("fc");
        var tc = SQL_COLUMN.as("tc");
        return store.dsl()
            .select(fc.COLUMN_NAME, fc.JOOQ_NAME, fc.BINDING_TYPE, fc.DATABASE_TYPE,
                tc.COLUMN_NAME, tc.JOOQ_NAME, tc.BINDING_TYPE, tc.DATABASE_TYPE)
            .from(sc)
            .join(fc).on(fc.SOURCE_NAME.eq(sc.FROM_SOURCE_NAME), fc.TABLE_SCHEMA.eq(sc.FROM_SCHEMA),
                fc.TABLE_NAME.eq(sc.FROM_TABLE), fc.COLUMN_NAME.eq(sc.FROM_COLUMN))
            .join(tc).on(tc.SOURCE_NAME.eq(sc.TO_SOURCE_NAME), tc.TABLE_SCHEMA.eq(sc.TO_SCHEMA),
                tc.TABLE_NAME.eq(sc.TO_TABLE), tc.COLUMN_NAME.eq(sc.TO_COLUMN))
            .where(sc.GRAPH_NAME.eq(store.graphName()), sc.COORDINATE.eq(coordinate), sc.POSITION.eq(0))
            .orderBy(sc.ORDINAL)
            .fetch(r -> new Pair(new ColumnRef(r.value1(), r.value2(), r.value3(), r.value4()),
                new ColumnRef(r.value5(), r.value6(), r.value7(), r.value8())));
    }

    /**
     * A participant type's cross columns, graphitron_field_cross_column's, each with its step's column
     * pairs, the foreign key it rides (graphitron_join_path_step_key where written,
     * graphitron_join_path_step_inferred_key where inferred) and the column it reads past it;
     * {@code null} where one has no column bound past its step or rides no foreign key.
     */
    private static List<Cross> crossColumns(StoreHandle store, String type) {
        var cc = GRAPHITRON_FIELD_CROSS_COLUMN;
        var sc = GRAPHITRON_JOIN_PATH_STEP_COLUMN;
        var oc = GRAPHITRON_OCCURRENCE_COLUMN;
        var fc = SQL_COLUMN.as("fc");
        var tc = SQL_COLUMN.as("tc");
        var c = SQL_COLUMN;
        var graph = store.graphName();
        var out = new ArrayList<Cross>();
        for (var field : store.dsl().select(cc.FIELD_NAME).from(cc)
                .where(cc.GRAPH_NAME.eq(graph), cc.TYPE_NAME.eq(type)).orderBy(cc.FIELD_NAME).fetch(cc.FIELD_NAME)) {
            String coordinate = type + "." + field;
            var steps = store.dsl()
                .select(sc.TO_SOURCE_NAME, sc.TO_SCHEMA, sc.TO_TABLE,
                    fc.COLUMN_NAME, fc.JOOQ_NAME, fc.BINDING_TYPE, fc.DATABASE_TYPE,
                    tc.COLUMN_NAME, tc.JOOQ_NAME, tc.BINDING_TYPE, tc.DATABASE_TYPE)
                .from(sc)
                .join(fc).on(fc.SOURCE_NAME.eq(sc.FROM_SOURCE_NAME), fc.TABLE_SCHEMA.eq(sc.FROM_SCHEMA),
                    fc.TABLE_NAME.eq(sc.FROM_TABLE), fc.COLUMN_NAME.eq(sc.FROM_COLUMN))
                .join(tc).on(tc.SOURCE_NAME.eq(sc.TO_SOURCE_NAME), tc.TABLE_SCHEMA.eq(sc.TO_SCHEMA),
                    tc.TABLE_NAME.eq(sc.TO_TABLE), tc.COLUMN_NAME.eq(sc.TO_COLUMN))
                .where(sc.GRAPH_NAME.eq(graph), sc.COORDINATE.eq(coordinate), sc.POSITION.eq(0))
                .orderBy(sc.ORDINAL)
                .fetch();
            if (steps.isEmpty()) {
                return null;
            }
            var head = steps.getFirst();
            var column = store.dsl().select(c.COLUMN_NAME, c.JOOQ_NAME, c.BINDING_TYPE, c.DATABASE_TYPE)
                .from(oc)
                .join(c).on(c.SOURCE_NAME.eq(oc.TABLE_SOURCE_NAME), c.TABLE_SCHEMA.eq(oc.TABLE_SCHEMA),
                    c.TABLE_NAME.eq(oc.TABLE_NAME), c.COLUMN_NAME.eq(oc.COLUMN_NAME))
                .where(oc.GRAPH_NAME.eq(graph), oc.COORDINATE.eq(coordinate), oc.TABLE_SOURCE_NAME.eq(head.value1()),
                    oc.TABLE_SCHEMA.eq(head.value2()), oc.TABLE_NAME.eq(head.value3()))
                .fetchOne(r -> new ColumnRef(r.value1(), r.value2(), r.value3(), r.value4()));
            var target = tableRef(store, head.value1(), head.value2(), head.value3());
            var key = foreignKey(store, coordinate);
            if (column == null || target == null || key == null) {
                return null;
            }
            var pairs = steps.stream().map(r -> new Pair(
                new ColumnRef(r.value4(), r.value5(), r.value6(), r.value7()),
                new ColumnRef(r.value8(), r.value9(), r.value10(), r.value11()))).toList();
            out.add(new Cross(field, column, target, pairs, key));
        }
        return out;
    }

    /**
     * The foreign key the first step of {@code coordinate}'s path rides, written or inferred, as the
     * catalog names it: its SQL name, the schema's Keys class and its constant there.
     */
    private static ForeignKeyRef foreignKey(StoreHandle store, String coordinate) {
        var wk = GRAPHITRON_JOIN_PATH_STEP_KEY;
        var ik = GRAPHITRON_JOIN_PATH_STEP_INFERRED_KEY;
        var k = SQL_CONSTRAINT;
        var s = SQL_SCHEMA;
        var graph = store.graphName();
        var written = store.dsl()
            .select(wk.CONSTRAINT_SOURCE_NAME, wk.CONSTRAINT_SCHEMA, wk.CONSTRAINT_TABLE, wk.CONSTRAINT_NAME)
            .from(wk).where(wk.GRAPH_NAME.eq(graph), wk.COORDINATE.eq(coordinate), wk.POSITION.eq(0))
            .unionAll(store.dsl()
                .select(ik.CONSTRAINT_SOURCE_NAME, ik.CONSTRAINT_SCHEMA, ik.CONSTRAINT_TABLE, ik.CONSTRAINT_NAME)
                .from(ik).where(ik.GRAPH_NAME.eq(graph), ik.COORDINATE.eq(coordinate), ik.POSITION.eq(0)))
            .fetch();
        if (written.size() != 1) {
            return null;
        }
        var fk = written.getFirst();
        return store.dsl()
            .select(k.CONSTRAINT_NAME, s.KEYS_CLASS_FQN, k.JOOQ_NAME)
            .from(k)
            .join(s).on(s.SOURCE_NAME.eq(k.SOURCE_NAME), s.TABLE_SCHEMA.eq(k.TABLE_SCHEMA))
            .where(k.SOURCE_NAME.eq(fk.value1()), k.TABLE_SCHEMA.eq(fk.value2()), k.TABLE_NAME.eq(fk.value3()),
                k.CONSTRAINT_NAME.eq(fk.value4()), s.KEYS_CLASS_FQN.isNotNull())
            .fetchOne(r -> new ForeignKeyRef(r.value1(), r.value2(), r.value3()));
    }

    /** The largest page the graph's build lets a connection serve, where it set one. */
    private static java.util.OptionalInt maxPageSize(StoreHandle store) {
        var m = STORE_GRAPH_MAX_ALLOWED_PAGE_SIZE;
        var max = store.dsl().select(m.MAX_PAGE_SIZE).from(m).where(m.GRAPH_NAME.eq(store.graphName()))
            .fetchOne(m.MAX_PAGE_SIZE);
        return max == null ? java.util.OptionalInt.empty() : java.util.OptionalInt.of(max);
    }

    /** A root lookup's keys in key order, or the reason the store does not state them whole yet. */
    private record Keys(List<LookupKey> keys, Reason reason) {}

    /**
     * Each root lookup's keys: a scalar {@code @lookupKey} argument binding a column of the field's own
     * table, taken as the caller hands it, is stated whole; a key read off an input object's field, a
     * node id, or a value an {@code ID} or an enum converts holds the lookup back.
     */
    private static Map<String, Keys> lookups(StoreHandle store) {
        var lk = GRAPHITRON_OCCURRENCE_LOOKUP_KEY;
        var p = GRAPHITRON_OCCURRENCE_PRODUCER;
        var ko = GRAPHITRON_OCCURRENCE.as("ko");
        var a = GRAPHITRON_ARGUMENT;
        var ty = GRAPHITRON_TYPE;
        var oc = GRAPHITRON_OCCURRENCE_COLUMN;
        var c = SQL_COLUMN;
        var keys = new LinkedHashMap<String, List<LookupKey>>();
        var held = new LinkedHashMap<String, Reason>();
        store.dsl()
            .select(lk.COORDINATE, lk.KEY_KIND, lk.FIELD_ORDINAL, field(lk.KEY_BOUND.eq(lk.BOUND)), ko.NAME, a.IS_LIST,
                a.NAMED_TYPE, ty.KIND, c.COLUMN_NAME, c.JOOQ_NAME, c.BINDING_TYPE, c.DATABASE_TYPE)
            .from(lk)
            .join(p).on(p.GRAPH_NAME.eq(lk.GRAPH_NAME), p.COORDINATE.eq(lk.COORDINATE), p.START.eq("ROOT"))
            .join(ko).on(ko.GRAPH_NAME.eq(lk.GRAPH_NAME), ko.COORDINATE.eq(lk.KEY_COORDINATE))
            .leftJoin(a).on(a.GRAPH_NAME.eq(ko.GRAPH_NAME), a.COORDINATE.eq(ko.ELEMENT_COORDINATE))
            .leftJoin(ty).on(ty.GRAPH_NAME.eq(a.GRAPH_NAME), ty.TYPE_NAME.eq(a.NAMED_TYPE))
            .leftJoin(oc).on(oc.GRAPH_NAME.eq(lk.GRAPH_NAME), oc.COORDINATE.eq(lk.KEY_COORDINATE),
                oc.BOUND.eq(lk.KEY_BOUND), lk.KEY_KIND.eq("COLUMN"))
            .leftJoin(c).on(c.SOURCE_NAME.eq(oc.TABLE_SOURCE_NAME), c.TABLE_SCHEMA.eq(oc.TABLE_SCHEMA),
                c.TABLE_NAME.eq(oc.TABLE_NAME), c.COLUMN_NAME.eq(oc.COLUMN_NAME))
            .where(lk.GRAPH_NAME.eq(store.graphName()))
            .orderBy(lk.COORDINATE, lk.ARGUMENT_ORDINAL, lk.FIELD_ORDINAL)
            .forEach(row -> {
                String coordinate = row.value1();
                Reason reason = null;
                if ("NODE_ID".equals(row.value2())) reason = Reason.LOOKUP_NODE_ID;
                else if (row.value3() != -1 || row.value6() == null) reason = Reason.LOOKUP_INPUT_FIELD;
                else if (!Boolean.TRUE.equals(row.value4()) || row.value9() == null) reason = Reason.NOT_ONE_TABLE;
                else if ("ID".equals(row.value7()) || "ENUM".equals(row.value8())) reason = Reason.LOOKUP_KEY_CONVERTED;
                if (reason != null) {
                    held.putIfAbsent(coordinate, reason);
                } else {
                    keys.computeIfAbsent(coordinate, k -> new ArrayList<>()).add(new LookupKey(row.value5(),
                        new ColumnRef(row.value9(), row.value10(), row.value11(), row.value12()), row.value6()));
                }
            });
        var out = new LinkedHashMap<String, Keys>();
        keys.forEach((coordinate, list) -> out.put(coordinate, new Keys(list, null)));
        held.forEach((coordinate, reason) -> out.put(coordinate, new Keys(List.of(), reason)));
        return out;
    }

    /**
     * Each root list's default order: its written keys in sequence, then the row-key columns the store
     * completes it with. A completing column identifies the row, so it sorts no nulls apart.
     */
    private static Map<String, List<Key>> orders(StoreHandle store) {
        var od = GRAPHITRON_OCCURRENCE_ORDER;
        var sk = GRAPHITRON_OCCURRENCE_SORT_KEY;
        var kc = GRAPHITRON_OCCURRENCE_SORT_KEY_COLUMN;
        var sc = GRAPHITRON_OCCURRENCE_SORT_KEY_COMPLETION;
        var c = SQL_COLUMN;
        var p = GRAPHITRON_OCCURRENCE_PRODUCER;
        var out = new LinkedHashMap<String, List<Key>>();
        store.dsl()
            .select(od.COORDINATE, c.COLUMN_NAME, c.JOOQ_NAME, c.BINDING_TYPE, c.DATABASE_TYPE, c.NULLABLE,
                sk.DIRECTION, sk.COLLATION)
            .from(od)
            .join(p).on(p.GRAPH_NAME.eq(od.GRAPH_NAME), p.COORDINATE.eq(od.COORDINATE), p.START.eq("ROOT"))
            .join(sk).on(sk.GRAPH_NAME.eq(od.GRAPH_NAME), sk.COORDINATE.eq(od.COORDINATE), sk.BOUND.eq(od.BOUND),
                sk.CHOICE.eq(od.CHOICE))
            .join(kc).on(kc.GRAPH_NAME.eq(sk.GRAPH_NAME), kc.COORDINATE.eq(sk.COORDINATE), kc.BOUND.eq(sk.BOUND),
                kc.CHOICE.eq(sk.CHOICE), kc.POSITION.eq(sk.POSITION))
            .join(c).on(c.SOURCE_NAME.eq(kc.TABLE_SOURCE_NAME), c.TABLE_SCHEMA.eq(kc.TABLE_SCHEMA),
                c.TABLE_NAME.eq(kc.TABLE_NAME), c.COLUMN_NAME.eq(kc.COLUMN_NAME))
            .where(od.GRAPH_NAME.eq(store.graphName()), od.KIND.eq("DEFAULT"))
            .orderBy(od.COORDINATE, sk.POSITION)
            .forEach(row -> out.computeIfAbsent(row.value1(), k -> new ArrayList<>()).add(new Key(
                new ColumnRef(row.value2(), row.value3(), row.value4(), row.value5()),
                Boolean.TRUE.equals(row.value6()), row.value7(), row.value8())));
        store.dsl()
            .select(sc.COORDINATE, c.COLUMN_NAME, c.JOOQ_NAME, c.BINDING_TYPE, c.DATABASE_TYPE, sc.DIRECTION)
            .from(sc)
            .join(od).on(od.GRAPH_NAME.eq(sc.GRAPH_NAME), od.COORDINATE.eq(sc.COORDINATE), od.BOUND.eq(sc.BOUND),
                od.CHOICE.eq(sc.CHOICE))
            .join(p).on(p.GRAPH_NAME.eq(od.GRAPH_NAME), p.COORDINATE.eq(od.COORDINATE), p.START.eq("ROOT"))
            .join(c).on(c.SOURCE_NAME.eq(sc.TABLE_SOURCE_NAME), c.TABLE_SCHEMA.eq(sc.TABLE_SCHEMA),
                c.TABLE_NAME.eq(sc.TABLE_NAME), c.COLUMN_NAME.eq(sc.COLUMN_NAME))
            .where(sc.GRAPH_NAME.eq(store.graphName()), od.KIND.eq("DEFAULT"))
            .orderBy(sc.COORDINATE, sc.POSITION)
            .forEach(row -> out.computeIfAbsent(row.value1(), k -> new ArrayList<>()).add(new Key(
                new ColumnRef(row.value2(), row.value3(), row.value4(), row.value5()), false, row.value6(), null)));
        return out;
    }

    /** Each root's condition method, by occurrence and bound table. */
    private static Map<List<String>, Signature> signatures(StoreHandle store) {
        var s = GRAPHITRON_OCCURRENCE_CONDITION_SIGNATURE;
        var p = GRAPHITRON_OCCURRENCE_PRODUCER;
        var out = new LinkedHashMap<List<String>, Signature>();
        store.dsl()
            .select(s.COORDINATE, s.BOUND, s.TAKES_CONTEXT, s.TAKES_SESSION)
            .from(s)
            .join(p).on(p.GRAPH_NAME.eq(s.GRAPH_NAME), p.COORDINATE.eq(s.COORDINATE), p.START.eq("ROOT"))
            .where(s.GRAPH_NAME.eq(store.graphName()))
            .forEach(row -> out.put(List.of(row.value1(), row.value2()), new Signature(row.value3(), row.value4())));
        return out;
    }

    /**
     * The catalog table as a {@link TableRef}: its names and generated classes, its primary key in key
     * order and its columns in declaration order. {@code null} where its schema publishes no
     * {@code Tables} class, there being no constant to reach it through.
     */
    private static TableRef tableRef(StoreHandle store, String source, String schema, String name) {
        var t = SQL_TABLE;
        var s = SQL_SCHEMA;
        var c = SQL_COLUMN;
        var pk = SQL_PRIMARY_KEY;
        var cc = SQL_CONSTRAINT_COLUMN;
        var row = store.dsl()
            .select(t.TABLE_NAME, t.JOOQ_NAME, t.CLASS_FQN, t.RECORD_CLASS_FQN, s.TABLES_CLASS_FQN)
            .from(t)
            .join(s).on(s.SOURCE_NAME.eq(t.SOURCE_NAME), s.TABLE_SCHEMA.eq(t.TABLE_SCHEMA))
            .where(t.SOURCE_NAME.eq(source), t.TABLE_SCHEMA.eq(schema), t.TABLE_NAME.eq(name))
            .fetchOne();
        if (row == null || row.value5() == null) {
            return null;
        }
        var keys = store.dsl()
            .select(c.COLUMN_NAME, c.JOOQ_NAME, c.BINDING_TYPE, c.DATABASE_TYPE)
            .from(pk)
            .join(cc).on(cc.SOURCE_NAME.eq(pk.SOURCE_NAME), cc.TABLE_SCHEMA.eq(pk.TABLE_SCHEMA),
                cc.TABLE_NAME.eq(pk.TABLE_NAME), cc.CONSTRAINT_NAME.eq(pk.CONSTRAINT_NAME))
            .join(c).on(c.SOURCE_NAME.eq(cc.SOURCE_NAME), c.TABLE_SCHEMA.eq(cc.TABLE_SCHEMA),
                c.TABLE_NAME.eq(cc.TABLE_NAME), c.COLUMN_NAME.eq(cc.COLUMN_NAME))
            .where(pk.SOURCE_NAME.eq(source), pk.TABLE_SCHEMA.eq(schema), pk.TABLE_NAME.eq(name))
            .orderBy(cc.POSITION)
            .fetch(k -> new ColumnRef(k.value1(), k.value2(), k.value3(), k.value4()));
        var columns = store.dsl()
            .select(c.COLUMN_NAME, c.JOOQ_NAME, c.BINDING_TYPE, c.DATABASE_TYPE)
            .from(c)
            .where(c.SOURCE_NAME.eq(source), c.TABLE_SCHEMA.eq(schema), c.TABLE_NAME.eq(name))
            .orderBy(c.ORDINAL)
            .fetch(k -> new ColumnRef(k.value1(), k.value2(), k.value3(), k.value4()));
        return new TableRef(row.value1(), row.value2(), row.value3(), row.value4(), row.value5(), keys, columns);
    }
}
