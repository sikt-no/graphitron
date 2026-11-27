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
import static no.sikt.graphitron.model.Tables.GRAPHITRON_TABLETYPE_SESSION;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_CONNECTION_CARRIER;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_CONNECTION_FACET;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_DIRECTIVE_APPLICATION;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_EDGE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_ENUM_VALUE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_FACET_BINDING;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_FIELD;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_FIELD_CROSS_COLUMN;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_FIELD_TARGET;
import static no.sikt.graphitron.model.Tables.GRAPHQL_ROOT_OPERATION;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_INTERFACE_SHARED_FIELD;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_JOIN_PATH_STEP;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_JOIN_PATH_STEP_COLUMN;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_JOIN_PATH_STEP_INFERRED_KEY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_JOIN_PATH_STEP_KEY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_JOIN_PATH_STEP_METHOD;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_JOIN_PATH_STEP_PRIMARY_KEY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_NODE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_ARGUMENT_STEP;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_BINDING_EXTRACTION;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_BINDING_PARAMETER;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_BINDING_TABLE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_CALL_PARAMETER;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_COLUMN;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_COLUMN_CONVERSION;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_CONDITION_SIGNATURE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_CONNECTION;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_LOOKUP_KEY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_NODE_ID;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_NODE_ID_CARRIED;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_ORDER;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_PARTICIPANT;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_PRODUCER;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_PRODUCER_CONNECTION;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_PRODUCER_FACET;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_PRODUCER_NODE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_PRODUCER_RESULT;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_PRODUCER_ROUTINE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_READ_SCOPE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_SORT_KEY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_SORT_KEY_COLUMN;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_SORT_KEY_COMPLETION;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_SORT_KEY_HOP;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_ORDER_INPUT;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_PATH_NEED_TABLE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_TABLETYPE;
import static no.sikt.graphitron.model.Tables.SQL_COLUMN;
import static no.sikt.graphitron.model.Tables.SQL_CONSTRAINT;
import static no.sikt.graphitron.model.Tables.SQL_CONSTRAINT_COLUMN;
import static no.sikt.graphitron.model.Tables.SQL_PRIMARY_KEY;
import static no.sikt.graphitron.model.Tables.SQL_REFERENTIAL_CONSTRAINT_COLUMN;
import static no.sikt.graphitron.model.Tables.SQL_ROUTINE;
import static no.sikt.graphitron.model.Tables.SQL_ROUTINE_PARAMETER;
import static no.sikt.graphitron.model.Tables.SQL_SCHEMA;
import static no.sikt.graphitron.model.Tables.SQL_TABLE;
import static no.sikt.graphitron.model.Tables.STORE_GRAPH_MAX_ALLOWED_PAGE_SIZE;
import static no.sikt.graphitron.model.Tables.STORE_GRAPH_TENANT_COLUMN;
import static org.jooq.impl.DSL.coalesce;
import static org.jooq.impl.DSL.exists;
import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.notExists;
import static org.jooq.impl.DSL.select;
import static org.jooq.impl.DSL.selectOne;

/**
 * Each root {@code SELECT}, {@code LOOKUP} or query root's {@code ROUTINE} producer, and whether the store states its fetcher whole:
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
        /** Its rows may be several types', told apart by the table each is a row of, and it is a lookup over them. */
        POLYMORPHIC,
        /**
         * Its rows are told apart by a discriminator, and a participant on a detail table of its own reads
         * a field the joined read does not project: a node id, or an inherited reference it would alias
         * apart from another interface's read of the same type.
         */
        JOINED_DETAIL,
        /** It builds a connection the store states no order for, which paging needs. */
        NO_ORDER,
        /**
         * A table it reads carries the tenant column and no one route reaches it: its read scope is
         * across a tenant's tables and global ones, or it reads over several tables a tenant's is among.
         */
        TENANTED,
        /**
         * It reads a tenant's rows marked {@code @tenantFanOut}, every tenant the request may see, a
         * route the store does not state yet: it has no stored connection.
         */
        TENANT_FAN_OUT,
        /** It is read through a {@code @pivot}. */
        PIVOT,
        /** A key of its order is not a column of its table. */
        SORT_KEY_NOT_A_COLUMN,
        /** It produces no records of one table-bound type. */
        NOT_ONE_TABLE,
        /** It reads a routine's rows as a connection, or in an order a client chooses. */
        ROUTINE_SHAPE,
        /** A routine parameter it fills takes a node id's key column or a generated scalar's component. */
        ROUTINE_ARGUMENT,
        /** A step out of its routine's result joins by no columns. */
        ROUTINE_HOP
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
     * follows its keys rather than an order, so it states neither; a list with a choice reads the
     * default order where the client picks none. {@code routine} is the routine whose result its
     * rows are read from, and the steps on to {@code table}, {@code null} for a read of a table.
     * {@code connection} is the connection the store states the root runs on, {@code null} where it runs
     * on the request's own; {@code projectionTakesSession} is whether its type's projection reads the
     * session, its {@code $project} then taking the executing {@code DSLContext}.
     */
    public record Emitted(String coordinate, String parentType, String fieldName, String returnType,
                          TableRef table, boolean list, List<Key> order, Signature condition,
                          List<LookupArg> keys, Choice choice, Integer pageSize, Discriminated discriminated,
                          List<Facet> facets, Routine routine, ConnectionQuery.Outcome connection,
                          boolean projectionTakesSession)
            implements Result {
        public Emitted {
            facets = List.copyOf(facets);
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
                             List<Branch> branches, Ordered ordered, Integer pageSize, List<Dispatch> dispatches)
            implements Result {
        public MultiTable {
            Objects.requireNonNull(coordinate, "coordinate");
            Objects.requireNonNull(parentType, "parentType");
            Objects.requireNonNull(fieldName, "fieldName");
            branches = List.copyOf(branches);
            dispatches = List.copyOf(dispatches);
        }
    }

    /**
     * A node-id argument of a root over several tables whose participants decode it as different node
     * types: its name, whether it takes a list, and each participant's decode, by participant type in
     * participant order. An id none of them decodes is the client's mistake, told apart before any
     * branch reads.
     */
    public record Dispatch(String argument, boolean list, Map<String, NodeDecode> byParticipant) {
        public Dispatch {
            byParticipant = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(byParticipant));
        }
    }

    /** How one participant decodes a node id: the node type, its type id, its table and its key columns in order. */
    public record NodeDecode(String nodeType, String typeId, TableRef table, List<ColumnRef> keyColumns) {
        public NodeDecode {
            keyColumns = List.copyOf(keyColumns);
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
     * An {@code @orderBy} argument of a root over several tables, which takes one order: its name, its
     * input type, whether it is required, and the input's sort and direction fields.
     */
    public record OrderArgument(String name, String inputType, boolean nonNull, String sortField,
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

    /**
     * One argument a lookup's keys are handed in: its name and whether it takes a list, and what it
     * carries. Its conversion is how a caller's value becomes the column's, as
     * {@code graphitron_occurrence_column_conversion} states it: {@code DIRECT}, {@code CONVERTED} or
     * {@code ENUM_CONSTANT}.
     */
    public sealed interface LookupArg {
        String argument();

        boolean list();

        /** An argument whose value is one key, matching one column. */
        record Scalar(String argument, boolean list, ColumnRef column, String conversion) implements LookupArg {}

        /** An argument taking an input object, each of whose key fields matches one column. */
        record Fields(String argument, boolean list, List<FieldKey> fields) implements LookupArg {
            public Fields {
                fields = List.copyOf(fields);
            }
        }

        /** An argument whose value is a node id, decoded to its node's key columns. */
        record NodeId(String argument, boolean list, NodeDecode decode) implements LookupArg {}
    }

    /** One key field of an input object a lookup's argument takes, by its name. */
    public sealed interface FieldKey {
        String field();

        /** A field matching one column of the lookup's table: its column and its conversion. */
        record Column(String field, ColumnRef column, String conversion) implements FieldKey {}

        /**
         * A field whose value is a node id: how it decodes to its node's key columns, and per key
         * position the column of the lookup's table the decoded value lands on, the node's own key
         * column where the node is on that table and the column its foreign key carries it back to
         * where it is not.
         */
        record NodeId(String field, NodeDecode decode, List<ColumnRef> targetColumns) implements FieldKey {
            public NodeId {
                targetColumns = List.copyOf(targetColumns);
            }
        }
    }

    /**
     * One facet a connection surfaces, in the order it surfaces them: its name, the filter argument
     * whose input field it is, the type its values are and whether one may be null, the type its
     * buckets are, whether it reaches its column through {@code @reference}, the column its counts
     * group by, and the steps from the connection's table to the one that column is on, none where it
     * is the connection's own.
     */
    public record Facet(String name, String filterArgument, String valueType, boolean valueNullable, String bucketType,
                        boolean referenceBound, ColumnRef column, List<FacetStep> steps) {
        public Facet {
            steps = List.copyOf(steps);
        }
    }

    /**
     * One step a facet's path takes: the table it leaves and the one it reaches, the foreign key it
     * rides or the primary key it joins a row to itself on, {@code null} for a step a condition method
     * joins alone, its column pairs, and the condition method it joins by or narrows its join with,
     * {@code null} where it has none.
     */
    public record FacetStep(TableRef origin, TableRef target, ForeignKeyRef foreignKey, String primaryKey,
                            List<Pair> pairs, JoinMethod method) {
        public FacetStep {
            pairs = List.copyOf(pairs);
        }
    }

    /** A condition method a step joins by, taking the table it leaves and the one it reaches. */
    public record JoinMethod(String className, String methodName) {}

    /**
     * An {@code @orderBy} argument, which takes one order: its name, its input type, whether it is
     * required, the input's sort and direction fields, and each value the sort field takes with the keys
     * it sorts by, in the enum's order, each followed by the row key completing it.
     */
    public record Choice(String argument, String inputType, boolean nonNull, String sortField,
                         String directionField, Map<String, List<Key>> orders) {
        public Choice {
            orders = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(orders));
        }
    }

    /** One key of an order: the column, whether it holds nulls, its direction and its collation. */
    public record Key(ColumnRef column, boolean nullable, String direction, String collation, Derived derived) {
        /** A key reading a column of the list's own table. */
        public Key(ColumnRef column, boolean nullable, String direction, String collation) {
            this(column, nullable, direction, collation, null);
        }
    }

    /**
     * What a key reads where it reads no column of the list's own table: a column down to-one hops,
     * {@code column} being the one the last hop lands on.
     */
    public sealed interface Derived {
        /** The hops a path key reads its column through, from the list's row, in hop order. */
        record Path(List<Hop> hops) implements Derived {
            public Path {
                hops = List.copyOf(hops);
            }
        }
    }

    /**
     * One hop of a path key: the table it leaves and the one it reaches, the foreign key it rides, and
     * its column pairs, each from the side it leaves to the side it reaches.
     */
    public record Hop(TableRef origin, TableRef target, ForeignKeyRef key, List<Pair> pairs) {
        public Hop {
            pairs = List.copyOf(pairs);
        }
    }

    /** What the table's condition method takes beside the table and the arguments. */
    public record Signature(boolean takesContext, boolean takesSession) {}

    /**
     * The table-valued routine a root read calls: its {@code Routines} class and method, the table its
     * result is, the value each parameter takes in declaration order, and the steps from the result on
     * to the table the rows are read off, none where the rows are the result's own.
     */
    public record Routine(String routinesClass, String method, TableRef result, List<RoutineArgument> arguments,
                          List<RoutineHop> hops) {
        public Routine {
            Objects.requireNonNull(routinesClass, "routinesClass");
            Objects.requireNonNull(method, "method");
            Objects.requireNonNull(result, "result");
            arguments = List.copyOf(arguments);
            hops = List.copyOf(hops);
        }
    }

    /**
     * One routine parameter as its argument fills it: the parameter's name and the class it takes as
     * the catalog spells it, the argument and input field names its value sits at, and how the value
     * reaches it, as {@code graphitron_occurrence_binding_extraction} states: {@code AS_SENT},
     * {@code ENUM_CONSTANT} or {@code LIST_TO_ARRAY}.
     */
    public record RoutineArgument(String parameter, String bindingType, List<String> path, String extraction) {
        public RoutineArgument {
            Objects.requireNonNull(parameter, "parameter");
            Objects.requireNonNull(bindingType, "bindingType");
            path = List.copyOf(path);
            Objects.requireNonNull(extraction, "extraction");
        }
    }

    /**
     * One step from a routine's result toward its rows' table: the table it reaches, the column pairs
     * it joins by from the table it leaves, and the condition methods it is filtered by, each taking
     * the table left and the one reached.
     */
    public record RoutineHop(TableRef target, List<Pair> pairs, List<JoinMethod> filters) {
        public RoutineHop {
            Objects.requireNonNull(target, "target");
            pairs = List.copyOf(pairs);
            filters = List.copyOf(filters);
        }
    }

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
        // A read whose tables include a tenant's, as the bindings state its scope.
        var rsc = GRAPHITRON_OCCURRENCE_READ_SCOPE;
        var oc = GRAPHITRON_OCCURRENCE_CONNECTION;
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
                field(select(rsc.SCOPE).from(rsc).where(rsc.GRAPH_NAME.eq(p.GRAPH_NAME),
                    rsc.COORDINATE.eq(p.COORDINATE))),
                field(exists(selectOne().from(da).where(da.GRAPH_NAME.eq(f.GRAPH_NAME), da.COORDINATE.eq(f.COORDINATE),
                    da.DIRECTIVE_NAME.eq("pivot")))),
                field(exists(selectOne().from(od).where(od.GRAPH_NAME.eq(p.GRAPH_NAME), od.COORDINATE.eq(p.COORDINATE),
                    od.KIND.ne("DEFAULT")))),
                field(exists(selectOne().from(sk).where(sk.GRAPH_NAME.eq(p.GRAPH_NAME), sk.COORDINATE.eq(p.COORDINATE),
                    sk.KIND.ne("COLUMN"),
                    // A key the store does not state whole: a path landing on no column.
                    notExists(selectOne().from(GRAPHITRON_OCCURRENCE_SORT_KEY_COLUMN).where(
                        GRAPHITRON_OCCURRENCE_SORT_KEY_COLUMN.GRAPH_NAME.eq(sk.GRAPH_NAME),
                        GRAPHITRON_OCCURRENCE_SORT_KEY_COLUMN.COORDINATE.eq(sk.COORDINATE),
                        GRAPHITRON_OCCURRENCE_SORT_KEY_COLUMN.BOUND.eq(sk.BOUND),
                        GRAPHITRON_OCCURRENCE_SORT_KEY_COLUMN.CHOICE.eq(sk.CHOICE),
                        GRAPHITRON_OCCURRENCE_SORT_KEY_COLUMN.POSITION.eq(sk.POSITION)))))),
                field(exists(selectOne().from(tt).where(tt.GRAPH_NAME.eq(ft.GRAPH_NAME),
                    tt.TYPE_NAME.eq(ft.TARGET_TYPE_NAME), tt.TABLE_SOURCE_NAME.eq(r.TABLE_SOURCE_NAME),
                    tt.TABLE_SCHEMA.eq(r.TABLE_SCHEMA), tt.TABLE_NAME.eq(r.TABLE_NAME)))),
                ge.ITEM_TYPE_NAME, cc.DEFAULT_FIRST_VALUE,
                field(exists(selectOne().from(pn).where(pn.GRAPH_NAME.eq(p.GRAPH_NAME), pn.COORDINATE.eq(p.COORDINATE)))),
                field(select(oc.OUTCOME).from(oc).where(oc.GRAPH_NAME.eq(p.GRAPH_NAME),
                    oc.COORDINATE.eq(p.COORDINATE))))
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
            .where(p.GRAPH_NAME.eq(graph), p.START.eq("ROOT"), p.KIND.in("SELECT", "LOOKUP")
                // A query root's routine reads rows; a mutation root's writes, which another emitter states.
                .or(p.KIND.eq("ROUTINE").and(exists(selectOne().from(GRAPHQL_ROOT_OPERATION).where(
                    GRAPHQL_ROOT_OPERATION.GRAPH_NAME.eq(f.GRAPH_NAME), GRAPHQL_ROOT_OPERATION.TYPE_NAME.eq(f.TYPE_NAME),
                    GRAPHQL_ROOT_OPERATION.OPERATION.eq("QUERY"))))))
            .orderBy(p.COORDINATE)
            .fetch();

        var orders = orders(store);
        var signatures = signatures(store);
        var lookups = lookups(store);
        var facets = facets(store);
        var choices = choices(store);
        var maxPageSize = maxPageSize(store);
        var discriminated = discriminated(store);
        // The types whose projection reads the session, which a root reading one hands its dsl.
        var ts = GRAPHITRON_TABLETYPE_SESSION;
        var sessionTypes = java.util.Set.copyOf(store.dsl().select(ts.TYPE_NAME).from(ts)
            .where(ts.GRAPH_NAME.eq(graph)).fetch(ts.TYPE_NAME));
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
                out.put(at.coordinate(), multiTable(store, at, pageSize, signatures));
                continue;
            }
            boolean routineRead = "ROUTINE".equals(at.kind());
            RoutineRead routine = routineRead ? routine(store, at, connection || choices.containsKey(at.coordinate()))
                : null;
            if (routine != null && routine.reason() != null) reason = routine.reason();
            else if (told != null && told.reason() != null) reason = told.reason();
            // A discriminated lookup joins its keys' rows on the shared table, a source of its own.
            else if (told != null && "LOOKUP".equals(at.kind())) reason = Reason.POLYMORPHIC;
            else if (at.table() == null || at.returnType() == null
                || !(connection || routineRead || Boolean.TRUE.equals(row.value16()))
                || !(connection || "RECORD_LIST".equals(at.shape()) || "SINGLE_RECORD".equals(at.shape()))) {
                reason = Reason.NOT_ONE_TABLE;
            }
            // A read across a tenant's tables and global ones is held back; a tenant's read the store
            // states no connection for is a fan-out, the one such read it lets through unrefused.
            else if ("ACROSS".equals(row.value12())) reason = Reason.TENANTED;
            else if ("TENANT".equals(row.value12()) && row.value20() == null) reason = Reason.TENANT_FAN_OUT;
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
            if ("TENANT".equals(row.value12()) && !"ROUTED".equals(row.value20())) {
                throw new IllegalStateException("Graphitron generator bug (root read scope): the store states "
                    + at.coordinate() + " reads a tenant's tables on a connection " + row.value20()
                    + ", which only a routed one may be");
            }
            // A routine read's rows are on the table its last step reaches, else on the routine's result.
            var table = routine != null ? routine.terminal() : tableRef(store, at.source(), at.schema(), at.table());
            var faceted = Boolean.TRUE.equals(row.value11()) ? facets.get(at.coordinate()) : List.<Facet>of();
            if (table == null || faceted == null) {
                out.put(at.coordinate(), new NotYet(at.coordinate(), Reason.NOT_ONE_TABLE));
                continue;
            }
            boolean list = connection || "RECORD_LIST".equals(at.shape());
            boolean lookup = "LOOKUP".equals(at.kind());
            Integer pageSize = !connection ? null
                : row.value18() != null ? row.value18() : ConnectionDefaults.defaultPageSize(maxPageSize);
            var bound = routine != null ? routine.terminalBound() : at.schema() + "." + at.table();
            var signature = signatures.get(List.of(at.coordinate(), bound));
            // A routine read whose filter the store states on another table than its rows' is held back
            // rather than read unfiltered.
            if (routine != null && signature == null && signatures.keySet().stream()
                    .anyMatch(k -> k.getFirst().equals(at.coordinate()))) {
                out.put(at.coordinate(), new NotYet(at.coordinate(), Reason.ROUTINE_HOP));
                continue;
            }
            out.put(at.coordinate(), new Emitted(at.coordinate(), at.parentType(), at.fieldName(), at.returnType(),
                table, list, list && !lookup ? orders.getOrDefault(at.coordinate(), List.of()) : List.of(),
                signature, lookup ? lookups.get(at.coordinate()).keys() : List.of(),
                // A lookup has no order of its own: its list follows its keys.
                lookup ? null : choices.get(at.coordinate()),
                pageSize, told == null ? null : told.discriminated(), faceted,
                routine == null ? null : routine.routine(),
                row.value20() == null ? null : ConnectionQuery.Outcome.valueOf(row.value20()),
                sessionTypes.contains(at.returnType())));
        }
        return out;
    }

    /**
     * A routine read as the store states it: the routine, the table its rows are read off and that
     * table's {@code schema.table}, or why it is held back.
     */
    private record RoutineRead(Routine routine, TableRef terminal, String terminalBound, Reason reason) {
        static RoutineRead held(Reason reason) {
            return new RoutineRead(null, null, null, reason);
        }
    }

    /**
     * The routine a query root's routine producer calls, read off the store: its call surface, what
     * fills each parameter and how, and the steps from its result on to the table the rows are read
     * off. Held back where it reads a connection or a chosen order ({@code shaped}), a parameter takes
     * a value no plain read hands over, or a step joins by no columns.
     */
    private static RoutineRead routine(StoreHandle store, Producer at, boolean shaped) {
        if (shaped) {
            return RoutineRead.held(Reason.ROUTINE_SHAPE);
        }
        var graph = store.graphName();
        var pr = GRAPHITRON_OCCURRENCE_PRODUCER_ROUTINE;
        var sr = SQL_ROUTINE;
        var call = store.dsl()
            .select(pr.ROUTINE_SOURCE_NAME, pr.TABLE_SCHEMA, pr.ROUTINE_NAME, sr.ROUTINES_CLASS_FQN,
                sr.ROUTINES_METHOD_NAME)
            .from(pr)
            .join(sr).on(sr.SOURCE_NAME.eq(pr.ROUTINE_SOURCE_NAME), sr.TABLE_SCHEMA.eq(pr.TABLE_SCHEMA),
                sr.ROUTINE_NAME.eq(pr.ROUTINE_NAME))
            .where(pr.GRAPH_NAME.eq(graph), pr.COORDINATE.eq(at.coordinate()), sr.ROUTINES_CLASS_FQN.isNotNull())
            .fetchOne();
        if (call == null) {
            return RoutineRead.held(Reason.NOT_ONE_TABLE);
        }
        var result = tableRef(store, call.value1(), call.value2(), call.value3());
        if (result == null) {
            return RoutineRead.held(Reason.NOT_ONE_TABLE);
        }
        var arguments = routineArguments(store, at.coordinate(), call.value1(), call.value2(), call.value3());
        if (arguments == null) {
            return RoutineRead.held(Reason.ROUTINE_ARGUMENT);
        }
        // The steps on from the result, as the field's path states them toward the type it returns.
        var js = GRAPHITRON_JOIN_PATH_STEP;
        var sc = GRAPHITRON_JOIN_PATH_STEP_COLUMN;
        var sm = GRAPHITRON_JOIN_PATH_STEP_METHOD;
        var fieldCoordinate = at.parentType() + "." + at.fieldName();
        var steps = store.dsl()
            .select(js.POSITION, js.TO_SOURCE_NAME, js.TO_SCHEMA, js.TO_TABLE)
            .from(js)
            .where(js.GRAPH_NAME.eq(graph), js.COORDINATE.eq(fieldCoordinate), js.TARGET_TYPE.eq(at.returnType()))
            .orderBy(js.POSITION)
            .fetch();
        var hops = new ArrayList<RoutineHop>();
        var left = result;
        String bound = call.value2() + "." + call.value3();
        for (var step : steps) {
            var target = tableRef(store, step.value2(), step.value3(), step.value4());
            if (target == null) {
                return RoutineRead.held(Reason.NOT_ONE_TABLE);
            }
            var from = left;
            var pairs = store.dsl()
                .select(sc.FROM_COLUMN, sc.TO_COLUMN)
                .from(sc)
                .where(sc.GRAPH_NAME.eq(graph), sc.COORDINATE.eq(fieldCoordinate), sc.TARGET_TYPE.eq(at.returnType()),
                    sc.POSITION.eq(step.value1()))
                .orderBy(sc.ORDINAL)
                .fetch(pair -> new Pair(columnOf(from, pair.value1()), columnOf(target, pair.value2())));
            if (pairs.isEmpty() || pairs.stream().anyMatch(pair -> pair.from() == null || pair.to() == null)) {
                return RoutineRead.held(Reason.ROUTINE_HOP);
            }
            var filters = store.dsl()
                .select(sm.METHOD_CLASS_NAME, sm.METHOD_NAME)
                .from(sm)
                .where(sm.GRAPH_NAME.eq(graph), sm.COORDINATE.eq(fieldCoordinate), sm.TARGET_TYPE.eq(at.returnType()),
                    sm.POSITION.eq(step.value1()))
                .orderBy(sm.METHOD_CLASS_NAME, sm.METHOD_NAME)
                .fetch(method -> new JoinMethod(method.value1(), method.value2()));
            hops.add(new RoutineHop(target, pairs, filters));
            left = target;
            bound = step.value3() + "." + step.value4();
        }
        return new RoutineRead(new Routine(call.value4(), call.value5(), result, arguments, hops), left, bound, null);
    }

    /** The column of {@code table} of the given SQL name, {@code null} where it has none. */
    private static ColumnRef columnOf(TableRef table, String sqlName) {
        return table.allColumns().stream().filter(c -> c.sqlName().equals(sqlName)).findFirst().orElse(null);
    }

    /**
     * Each parameter of the routine a query root's routine producer calls, in declaration order, with
     * the argument that fills it and how its value reaches it; {@code null} where a parameter is filled
     * otherwise than by an argument read as sent, as an enum's constant or as an array of a list.
     */
    private static List<RoutineArgument> routineArguments(StoreHandle store, String coordinate, String source,
                                                          String schema, String routine) {
        var graph = store.graphName();
        var sp = SQL_ROUTINE_PARAMETER;
        var cp = GRAPHITRON_OCCURRENCE_CALL_PARAMETER;
        var b = GRAPHITRON_OCCURRENCE_BINDING_PARAMETER;
        var e = GRAPHITRON_OCCURRENCE_BINDING_EXTRACTION;
        var parameters = store.dsl()
            .select(sp.POSITION, sp.JOOQ_NAME, sp.BINDING_TYPE, cp.FILL, cp.LANDING_COORDINATE, e.EXTRACTION)
            .from(sp)
            .leftJoin(cp).on(cp.GRAPH_NAME.eq(graph), cp.COORDINATE.eq(coordinate), cp.CALL_KIND.eq("ROUTINE"),
                cp.POSITION.eq(sp.POSITION))
            .leftJoin(b).on(b.GRAPH_NAME.eq(cp.GRAPH_NAME), b.CALL_COORDINATE.eq(cp.COORDINATE), b.CALL.eq(cp.CALL),
                b.POSITION.eq(cp.POSITION))
            .leftJoin(e).on(e.GRAPH_NAME.eq(b.GRAPH_NAME), e.COORDINATE.eq(b.COORDINATE), e.BOUND.eq(b.BOUND))
            .where(sp.SOURCE_NAME.eq(source), sp.TABLE_SCHEMA.eq(schema), sp.ROUTINE_NAME.eq(routine))
            .orderBy(sp.POSITION)
            .fetch();
        var st = GRAPHITRON_OCCURRENCE_ARGUMENT_STEP;
        var out = new ArrayList<RoutineArgument>();
        for (var parameter : parameters) {
            if (!"ARGUMENT".equals(parameter.value4())
                    || !List.of("AS_SENT", "ENUM_CONSTANT", "LIST_TO_ARRAY").contains(parameter.value6())) {
                return null;
            }
            var path = store.dsl()
                .select(st.NAME)
                .from(st)
                .where(st.GRAPH_NAME.eq(graph), st.COORDINATE.eq(parameter.value5()))
                .orderBy(st.DEPTH)
                .fetch(st.NAME);
            out.add(new RoutineArgument(parameter.value2(), parameter.value3(), path, parameter.value6()));
        }
        return out;
    }

    /**
     * A root over several tables, each participant's rows those of its own table: stated whole where it
     * is a list, a record or a connection, no node-id argument names a participant by the
     * id's type, and no participant's table carries the tenant column. {@code pageSize} is a
     * connection's page where the client asks for none, {@code null} for a list or a record.
     */
    private static Result multiTable(StoreHandle store, Producer at, Integer pageSize,
                                     Map<List<String>, Signature> signatures) {
        var graph = store.graphName();
        String coordinate = at.coordinate();
        boolean connection = pageSize != null;
        if ("LOOKUP".equals(at.kind())
                || !(connection || "RECORD_LIST".equals(at.shape()) || "SINGLE_RECORD".equals(at.shape()))) {
            return new NotYet(coordinate, Reason.POLYMORPHIC);
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
        var rsc = GRAPHITRON_OCCURRENCE_READ_SCOPE;
        if (store.dsl().fetchExists(rsc, rsc.GRAPH_NAME.eq(graph), rsc.COORDINATE.eq(coordinate),
                rsc.SCOPE.in("TENANT", "ACROSS"))) {
            return new NotYet(coordinate, Reason.TENANTED);
        }
        var branches = new ArrayList<Branch>();
        var byBound = new LinkedHashMap<String, Branch>();
        for (var participant : participants) {
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
        var dispatches = dispatches(store, coordinate, byBound);
        if (dispatches == null) {
            return new NotYet(coordinate, Reason.NOT_ONE_TABLE);
        }
        return new MultiTable(coordinate, at.parentType(), at.fieldName(),
            connection || "RECORD_LIST".equals(at.shape()), branches, ordered(store, coordinate, byBound, connection),
            pageSize, dispatches);
    }

    /**
     * The node-id arguments of a root over several tables that its participants decode as more than one
     * node type, in the arguments' declared order, each participant's decode read off the id's key
     * columns at its table; an argument every participant decodes as one node type is a filter alone.
     * {@code null} where a participant's decode is not stated whole: no type id, or no table to read.
     */
    private static List<Dispatch> dispatches(StoreHandle store, String coordinate, Map<String, Branch> byBound) {
        var ni = GRAPHITRON_OCCURRENCE_NODE_ID;
        var oa = GRAPHITRON_OCCURRENCE;
        var ga = GRAPHITRON_ARGUMENT;
        var gn = GRAPHITRON_NODE;
        var c = SQL_COLUMN;
        record Read(String argument, boolean list, String bound, String nodeType, String typeId, String source,
                    String schema, String table, ColumnRef column) {}
        var reads = store.dsl()
            .select(ga.ARGUMENT_NAME, ga.LIST_DEPTH, ni.BOUND, ni.NODE_TYPE_NAME, gn.TYPE_ID, ni.TABLE_SOURCE_NAME,
                ni.TABLE_SCHEMA, ni.TABLE_NAME, c.COLUMN_NAME, c.JOOQ_NAME, c.BINDING_TYPE, c.DATABASE_TYPE)
            .from(ni)
            .join(oa).on(oa.GRAPH_NAME.eq(ni.GRAPH_NAME), oa.COORDINATE.eq(ni.COORDINATE))
            .join(ga).on(ga.GRAPH_NAME.eq(oa.GRAPH_NAME), ga.COORDINATE.eq(oa.ELEMENT_COORDINATE))
            .join(c).on(c.SOURCE_NAME.eq(ni.TABLE_SOURCE_NAME), c.TABLE_SCHEMA.eq(ni.TABLE_SCHEMA),
                c.TABLE_NAME.eq(ni.TABLE_NAME), c.COLUMN_NAME.eq(ni.COLUMN_NAME))
            .leftJoin(gn).on(gn.GRAPH_NAME.eq(ni.GRAPH_NAME), gn.TYPE_NAME.eq(ni.NODE_TYPE_NAME))
            .where(ni.GRAPH_NAME.eq(store.graphName()), oa.FIELD_COORDINATE.eq(coordinate),
                oa.ARGUMENT_COORDINATE.eq(oa.COORDINATE))
            .orderBy(ga.ORDINAL, ni.BOUND, ni.POSITION)
            .fetch(r -> new Read(r.value1(), r.value2() > 0, r.value3(), r.value4(), r.value5(), r.value6(),
                r.value7(), r.value8(), new ColumnRef(r.value9(), r.value10(), r.value11(), r.value12())));
        var byArgument = new LinkedHashMap<String, List<Read>>();
        reads.forEach(read -> byArgument.computeIfAbsent(read.argument(), k -> new ArrayList<>()).add(read));
        var out = new ArrayList<Dispatch>();
        for (var argument : byArgument.values()) {
            // The participants told apart by the id's type: more than one node type among them.
            if (argument.stream().filter(read -> byBound.containsKey(read.bound())).map(Read::nodeType).distinct()
                    .count() < 2) {
                continue;
            }
            var byParticipant = new LinkedHashMap<String, NodeDecode>();
            for (var entry : byBound.entrySet()) {
                var keys = argument.stream().filter(read -> read.bound().equals(entry.getKey())).toList();
                if (keys.isEmpty()) {
                    continue;
                }
                var first = keys.getFirst();
                var table = tableRef(store, first.source(), first.schema(), first.table());
                if (first.typeId() == null || table == null) {
                    return null;
                }
                byParticipant.put(entry.getValue().typeName(), new NodeDecode(first.nodeType(), first.typeId(), table,
                    keys.stream().map(Read::column).toList()));
            }
            out.add(new Dispatch(argument.getFirst().argument(), argument.getFirst().list(), byParticipant));
        }
        return out;
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
            .selectDistinct(ao.NAME, ga.NAMED_TYPE, ga.NON_NULL, oi.SORT_FIELD_NAME, oi.DIRECTION_FIELD_NAME)
            .from(od)
            .join(ao).on(ao.GRAPH_NAME.eq(od.GRAPH_NAME), ao.COORDINATE.eq(od.ARGUMENT_COORDINATE))
            .join(ga).on(ga.GRAPH_NAME.eq(ao.GRAPH_NAME), ga.COORDINATE.eq(ao.ELEMENT_COORDINATE))
            .join(oi).on(oi.GRAPH_NAME.eq(ga.GRAPH_NAME), oi.TYPE_NAME.eq(ga.NAMED_TYPE))
            .where(od.GRAPH_NAME.eq(store.graphName()), od.COORDINATE.eq(coordinate), od.KIND.eq("CHOICE"))
            .limit(1)
            .fetchOne(r -> new OrderArgument(r.value1(), r.value2(), Boolean.TRUE.equals(r.value3()), r.value4(),
                r.value5()));
    }

    /**
     * Each root list's {@code @orderBy} argument: the argument and its order input's fields, and each
     * choice's keys in sequence, then the row-key columns the store completes it with.
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
        record Head(String argument, String inputType, boolean nonNull, String sortField, String directionField) {}
        var heads = new LinkedHashMap<String, Head>();
        var keys = new LinkedHashMap<String, Map<String, List<Key>>>();
        // The argument, read whether or not its choices sort by columns: a choice with a path key
        // still takes one order, and the sort-key check holds it back.
        store.dsl()
            .selectDistinct(od.COORDINATE, ao.NAME, ga.NAMED_TYPE, ga.NON_NULL, oi.SORT_FIELD_NAME,
                oi.DIRECTION_FIELD_NAME)
            .from(od)
            .join(p).on(p.GRAPH_NAME.eq(od.GRAPH_NAME), p.COORDINATE.eq(od.COORDINATE), p.START.eq("ROOT"))
            .join(ao).on(ao.GRAPH_NAME.eq(od.GRAPH_NAME), ao.COORDINATE.eq(od.ARGUMENT_COORDINATE))
            .join(ga).on(ga.GRAPH_NAME.eq(ao.GRAPH_NAME), ga.COORDINATE.eq(ao.ELEMENT_COORDINATE))
            .join(oi).on(oi.GRAPH_NAME.eq(ga.GRAPH_NAME), oi.TYPE_NAME.eq(ga.NAMED_TYPE))
            .where(od.GRAPH_NAME.eq(store.graphName()), od.KIND.eq("CHOICE"))
            .orderBy(od.COORDINATE)
            .forEach(row -> heads.putIfAbsent(row.value1(), new Head(row.value2(), row.value3(),
                Boolean.TRUE.equals(row.value4()), row.value5(), row.value6())));
        var hops = sortKeyHops(store);
        store.dsl()
            .select(od.COORDINATE, ev.VALUE_NAME, c.COLUMN_NAME, c.JOOQ_NAME, c.BINDING_TYPE, c.DATABASE_TYPE,
                c.NULLABLE, sk.DIRECTION, sk.COLLATION, sk.KIND, sk.CHOICE, sk.POSITION)
            .from(od)
            .join(p).on(p.GRAPH_NAME.eq(od.GRAPH_NAME), p.COORDINATE.eq(od.COORDINATE), p.START.eq("ROOT"))
            .join(ev).on(ev.GRAPH_NAME.eq(od.GRAPH_NAME), ev.COORDINATE.eq(od.CHOICE))
            .join(sk).on(sk.GRAPH_NAME.eq(od.GRAPH_NAME), sk.COORDINATE.eq(od.COORDINATE), sk.BOUND.eq(od.BOUND),
                sk.CHOICE.eq(od.CHOICE))
            .leftJoin(kc).on(kc.GRAPH_NAME.eq(sk.GRAPH_NAME), kc.COORDINATE.eq(sk.COORDINATE), kc.BOUND.eq(sk.BOUND),
                kc.CHOICE.eq(sk.CHOICE), kc.POSITION.eq(sk.POSITION))
            .leftJoin(c).on(c.SOURCE_NAME.eq(kc.TABLE_SOURCE_NAME), c.TABLE_SCHEMA.eq(kc.TABLE_SCHEMA),
                c.TABLE_NAME.eq(kc.TABLE_NAME), c.COLUMN_NAME.eq(kc.COLUMN_NAME))
            .where(od.GRAPH_NAME.eq(store.graphName()), od.KIND.eq("CHOICE"))
            .orderBy(od.COORDINATE, ev.ORDINAL, sk.POSITION)
            .forEach(row -> keys.computeIfAbsent(row.value1(), k -> new LinkedHashMap<>())
                .computeIfAbsent(row.value2(), k -> new ArrayList<>())
                .add(keyOf(row.value3() == null ? null : new ColumnRef(row.value3(), row.value4(), row.value5(),
                        row.value6()), Boolean.TRUE.equals(row.value7()), row.value8(), row.value9(), row.value10(),
                    List.of(row.value1(), row.value11(), row.value12()), hops)));
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
        var out = new LinkedHashMap<String, Choice>();
        heads.forEach((coordinate, head) -> out.put(coordinate, new Choice(head.argument(), head.inputType(),
            head.nonNull(), head.sortField(), head.directionField(), keys.getOrDefault(coordinate, Map.of()))));
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

    /**
     * A root lookup's keys in key order, none where the store does not state them whole, and the reason
     * it holds the lookup back, {@code null} where it does not.
     */
    private record Keys(List<LookupArg> keys, Reason reason) {}

    /** Each root lookup's keys the store states whole, by occurrence. */
    static Map<String, List<LookupArg>> statedLookupKeys(StoreHandle store) {
        var out = new LinkedHashMap<String, List<LookupArg>>();
        lookups(store).forEach((coordinate, keys) -> {
            if (!keys.keys().isEmpty()) out.put(coordinate, keys.keys());
        });
        return out;
    }

    /**
     * Each root lookup's keys, an argument at a time in declaration order: a scalar {@code @lookupKey}
     * argument binding a column of the field's own table, an input object whose key fields each bind
     * one or are a node id decoded to its node's key columns and landing on the field's table, or a
     * node id decoded to its node's key columns there. A lookup the store states no whole keys for
     * keeps none.
     */
    private static Map<String, Keys> lookups(StoreHandle store) {
        var lk = GRAPHITRON_OCCURRENCE_LOOKUP_KEY;
        var p = GRAPHITRON_OCCURRENCE_PRODUCER;
        var ko = GRAPHITRON_OCCURRENCE.as("ko");
        var ao = GRAPHITRON_OCCURRENCE.as("ao");
        var a = GRAPHITRON_ARGUMENT;
        var oc = GRAPHITRON_OCCURRENCE_COLUMN;
        var cv = GRAPHITRON_OCCURRENCE_COLUMN_CONVERSION;
        var c = SQL_COLUMN;
        var decodes = lookupDecodes(store);
        var keys = new LinkedHashMap<String, List<LookupArg>>();
        var held = new LinkedHashMap<String, Reason>();
        var broken = new java.util.HashSet<String>();
        store.dsl()
            .select(lk.COORDINATE, lk.KEY_KIND, lk.FIELD_ORDINAL, field(lk.KEY_BOUND.eq(lk.BOUND)), a.ARGUMENT_NAME,
                a.IS_LIST, ko.NAME, lk.KEY_COORDINATE, cv.CONVERSION, c.COLUMN_NAME, c.JOOQ_NAME, c.BINDING_TYPE,
                c.DATABASE_TYPE)
            .from(lk)
            .join(p).on(p.GRAPH_NAME.eq(lk.GRAPH_NAME), p.COORDINATE.eq(lk.COORDINATE), p.START.eq("ROOT"))
            .join(ko).on(ko.GRAPH_NAME.eq(lk.GRAPH_NAME), ko.COORDINATE.eq(lk.KEY_COORDINATE))
            .join(ao).on(ao.GRAPH_NAME.eq(ko.GRAPH_NAME), ao.COORDINATE.eq(ko.ARGUMENT_COORDINATE))
            .join(a).on(a.GRAPH_NAME.eq(ao.GRAPH_NAME), a.COORDINATE.eq(ao.ELEMENT_COORDINATE))
            .leftJoin(oc).on(oc.GRAPH_NAME.eq(lk.GRAPH_NAME), oc.COORDINATE.eq(lk.KEY_COORDINATE),
                oc.BOUND.eq(lk.KEY_BOUND), lk.KEY_KIND.eq("COLUMN"))
            .leftJoin(cv).on(cv.GRAPH_NAME.eq(oc.GRAPH_NAME), cv.COORDINATE.eq(oc.COORDINATE), cv.BOUND.eq(oc.BOUND))
            .leftJoin(c).on(c.SOURCE_NAME.eq(oc.TABLE_SOURCE_NAME), c.TABLE_SCHEMA.eq(oc.TABLE_SCHEMA),
                c.TABLE_NAME.eq(oc.TABLE_NAME), c.COLUMN_NAME.eq(oc.COLUMN_NAME))
            .where(lk.GRAPH_NAME.eq(store.graphName()))
            .orderBy(lk.COORDINATE, lk.ARGUMENT_ORDINAL, lk.FIELD_ORDINAL)
            .forEach(row -> {
                String coordinate = row.value1();
                boolean field = row.value3() != -1;
                boolean nodeId = "NODE_ID".equals(row.value2());
                // An input object's node id lands on the field's table through the columns its decode
                // states, wherever its node is; every other key binds a column of the field's own table.
                if (!(nodeId && field) && !Boolean.TRUE.equals(row.value4())) {
                    held.putIfAbsent(coordinate, Reason.NOT_ONE_TABLE);
                    broken.add(coordinate);
                    return;
                }
                var args = keys.computeIfAbsent(coordinate, k -> new ArrayList<>());
                String argument = row.value5();
                boolean list = Boolean.TRUE.equals(row.value6());
                FieldKey key;
                if (nodeId) {
                    var decode = decodes.get(List.of(coordinate, row.value8()));
                    if (decode == null) {
                        held.putIfAbsent(coordinate, Reason.NOT_ONE_TABLE);
                        broken.add(coordinate);
                        return;
                    }
                    if (!field) {
                        args.add(new LookupArg.NodeId(argument, list, decode.decode()));
                        return;
                    }
                    key = new FieldKey.NodeId(row.value7(), decode.decode(), decode.targetColumns());
                } else {
                    if (row.value10() == null || row.value9() == null) {
                        held.putIfAbsent(coordinate, Reason.NOT_ONE_TABLE);
                        broken.add(coordinate);
                        return;
                    }
                    var column = new ColumnRef(row.value10(), row.value11(), row.value12(), row.value13());
                    if (!field) {
                        args.add(new LookupArg.Scalar(argument, list, column, row.value9()));
                        return;
                    }
                    key = new FieldKey.Column(row.value7(), column, row.value9());
                }
                if (!args.isEmpty() && args.getLast() instanceof LookupArg.Fields fields
                        && fields.argument().equals(argument)) {
                    var more = new ArrayList<>(fields.fields());
                    more.add(key);
                    args.set(args.size() - 1, new LookupArg.Fields(argument, list, more));
                } else {
                    args.add(new LookupArg.Fields(argument, list, List.of(key)));
                }
            });
        var out = new LinkedHashMap<String, Keys>();
        keys.forEach((coordinate, list) -> out.put(coordinate,
            new Keys(broken.contains(coordinate) ? List.of() : List.copyOf(list), held.get(coordinate))));
        held.forEach((coordinate, reason) -> out.putIfAbsent(coordinate, new Keys(List.of(), reason)));
        return out;
    }

    /** How a root lookup's node-id key decodes, and per key position the lookup's column it lands on. */
    private record KeyDecode(NodeDecode decode, List<ColumnRef> targetColumns) {}

    /**
     * How each root lookup's node-id key decodes, by the lookup and the key's occurrence, an argument
     * or an input object's field: the node type, its type id, the table and its key columns in order,
     * and per position the column of the lookup's table the value lands on. That is the key column
     * itself where the node is on the lookup's table, and otherwise the column
     * {@code graphitron_occurrence_node_id_carried} carries it back to, which the lookup key rule
     * states for every position of such a key. A node with no type id or a table with no generated
     * class decodes to nothing here.
     */
    private static Map<List<String>, KeyDecode> lookupDecodes(StoreHandle store) {
        var lk = GRAPHITRON_OCCURRENCE_LOOKUP_KEY;
        var p = GRAPHITRON_OCCURRENCE_PRODUCER;
        var t = GRAPHITRON_OCCURRENCE_BINDING_TABLE;
        var ni = GRAPHITRON_OCCURRENCE_NODE_ID;
        var cr = GRAPHITRON_OCCURRENCE_NODE_ID_CARRIED;
        var gn = GRAPHITRON_NODE;
        var c = SQL_COLUMN;
        var cc = SQL_COLUMN.as("cc");
        record Read(String nodeType, String typeId, String source, String schema, String table, boolean own,
                    ColumnRef column, ColumnRef carried) {}
        var byKey = new LinkedHashMap<List<String>, List<Read>>();
        store.dsl()
            .select(lk.COORDINATE, lk.KEY_COORDINATE, ni.NODE_TYPE_NAME, gn.TYPE_ID, ni.TABLE_SOURCE_NAME,
                ni.TABLE_SCHEMA, ni.TABLE_NAME, field(lk.KEY_BOUND.eq(lk.BOUND)), c.COLUMN_NAME, c.JOOQ_NAME,
                c.BINDING_TYPE, c.DATABASE_TYPE, cc.COLUMN_NAME, cc.JOOQ_NAME, cc.BINDING_TYPE, cc.DATABASE_TYPE)
            .from(lk)
            .join(p).on(p.GRAPH_NAME.eq(lk.GRAPH_NAME), p.COORDINATE.eq(lk.COORDINATE), p.START.eq("ROOT"))
            .join(t).on(t.GRAPH_NAME.eq(lk.GRAPH_NAME), t.COORDINATE.eq(lk.COORDINATE), t.BOUND.eq(lk.BOUND))
            .join(ni).on(ni.GRAPH_NAME.eq(lk.GRAPH_NAME), ni.COORDINATE.eq(lk.KEY_COORDINATE),
                ni.BOUND.eq(lk.KEY_BOUND))
            .join(c).on(c.SOURCE_NAME.eq(ni.TABLE_SOURCE_NAME), c.TABLE_SCHEMA.eq(ni.TABLE_SCHEMA),
                c.TABLE_NAME.eq(ni.TABLE_NAME), c.COLUMN_NAME.eq(ni.COLUMN_NAME))
            .leftJoin(cr).on(cr.GRAPH_NAME.eq(ni.GRAPH_NAME), cr.COORDINATE.eq(ni.COORDINATE),
                cr.BOUND.eq(ni.BOUND), cr.POSITION.eq(ni.POSITION), cr.TABLE_SOURCE_NAME.eq(t.TABLE_SOURCE_NAME),
                cr.TABLE_SCHEMA.eq(t.TABLE_SCHEMA), cr.TABLE_NAME.eq(t.TABLE_NAME))
            .leftJoin(cc).on(cc.SOURCE_NAME.eq(cr.TABLE_SOURCE_NAME), cc.TABLE_SCHEMA.eq(cr.TABLE_SCHEMA),
                cc.TABLE_NAME.eq(cr.TABLE_NAME), cc.COLUMN_NAME.eq(cr.COLUMN_NAME))
            .leftJoin(gn).on(gn.GRAPH_NAME.eq(ni.GRAPH_NAME), gn.TYPE_NAME.eq(ni.NODE_TYPE_NAME))
            .where(lk.GRAPH_NAME.eq(store.graphName()), lk.KEY_KIND.eq("NODE_ID"))
            .orderBy(lk.COORDINATE, lk.KEY_COORDINATE, ni.POSITION)
            .forEach(r -> byKey.computeIfAbsent(List.of(r.value1(), r.value2()), k -> new ArrayList<>())
                .add(new Read(r.value3(), r.value4(), r.value5(), r.value6(), r.value7(),
                    Boolean.TRUE.equals(r.value8()), new ColumnRef(r.value9(), r.value10(), r.value11(), r.value12()),
                    r.value13() == null ? null : new ColumnRef(r.value13(), r.value14(), r.value15(), r.value16()))));
        var out = new LinkedHashMap<List<String>, KeyDecode>();
        byKey.forEach((key, reads) -> {
            var targets = new ArrayList<ColumnRef>();
            for (var read : reads) {
                var target = read.own() ? read.column() : read.carried();
                if (target == null) {
                    throw new IllegalStateException("Graphitron generator bug (lookup key): the store states "
                        + key.get(1) + " a key of " + key.get(0) + " on " + read.schema() + "." + read.table()
                        + " and carries key column " + read.column().sqlName() + " back to no column of the "
                        + "lookup's table, which the lookup key rule admits no key without; the two have drifted");
                }
                targets.add(target);
            }
            var first = reads.getFirst();
            var table = tableRef(store, first.source(), first.schema(), first.table());
            if (first.typeId() != null && table != null) {
                out.put(key, new KeyDecode(new NodeDecode(first.nodeType(), first.typeId(), table,
                    reads.stream().map(Read::column).toList()), targets));
            }
        });
        return out;
    }

    /**
     * Each faceted root connection's facets in the order it surfaces them, by occurrence: each facet's
     * binding, the column its filter occurrence binds where its path lands, and the steps that path
     * takes from the connection's table. A root with a facet the store states no column for is absent,
     * which holds it back. Read for every root, the walk's launcher building a root the store holds
     * back for another reason from it too.
     */
    public static Map<String, List<Facet>> facets(StoreHandle store) {
        var pf = GRAPHITRON_OCCURRENCE_PRODUCER_FACET;
        var p = GRAPHITRON_OCCURRENCE_PRODUCER;
        var fo = GRAPHITRON_OCCURRENCE.as("fo");
        var fb = GRAPHITRON_FACET_BINDING;
        var gf = GRAPHITRON_FIELD;
        var cf = GRAPHITRON_CONNECTION_FACET;
        var oc = GRAPHITRON_OCCURRENCE_COLUMN;
        var c = SQL_COLUMN;
        var steps = facetSteps(store);
        var out = new LinkedHashMap<String, List<Facet>>();
        var held = new java.util.HashSet<String>();
        store.dsl()
            .select(pf.COORDINATE, pf.FACET_NAME, pf.FILTER_ARGUMENT_NAME, pf.FILTER_COORDINATE, fb.VALUE_TYPE_NAME,
                fb.VALUE_NULLABLE, cf.BUCKET_TYPE_NAME, fb.REFERENCE_BOUND, c.COLUMN_NAME, c.JOOQ_NAME, c.BINDING_TYPE,
                c.DATABASE_TYPE)
            .from(pf)
            .join(p).on(p.GRAPH_NAME.eq(pf.GRAPH_NAME), p.COORDINATE.eq(pf.COORDINATE), p.START.eq("ROOT"))
            .join(fo).on(fo.GRAPH_NAME.eq(pf.GRAPH_NAME), fo.COORDINATE.eq(pf.COORDINATE))
            .join(fb).on(fb.GRAPH_NAME.eq(pf.GRAPH_NAME), fb.TYPE_NAME.eq(pf.FACET_TYPE_NAME),
                fb.FIELD_NAME.eq(pf.FACET_NAME))
            .join(gf).on(gf.GRAPH_NAME.eq(fo.GRAPH_NAME), gf.COORDINATE.eq(fo.ELEMENT_COORDINATE))
            .leftJoin(cf).on(cf.GRAPH_NAME.eq(gf.GRAPH_NAME), cf.TYPE_NAME.eq(gf.TYPE_NAME),
                cf.FIELD_NAME.eq(gf.FIELD_NAME), cf.FACET_TYPE_NAME.eq(pf.FACET_TYPE_NAME),
                cf.FACET_FIELD_NAME.eq(pf.FACET_NAME))
            .leftJoin(oc).on(oc.GRAPH_NAME.eq(pf.GRAPH_NAME), oc.COORDINATE.eq(pf.FILTER_COORDINATE))
            .leftJoin(c).on(c.SOURCE_NAME.eq(oc.TABLE_SOURCE_NAME), c.TABLE_SCHEMA.eq(oc.TABLE_SCHEMA),
                c.TABLE_NAME.eq(oc.TABLE_NAME), c.COLUMN_NAME.eq(oc.COLUMN_NAME))
            .where(pf.GRAPH_NAME.eq(store.graphName()))
            .orderBy(pf.COORDINATE, pf.POSITION)
            .forEach(row -> {
                String coordinate = row.value1();
                if (row.value9() == null || row.value7() == null) {
                    held.add(coordinate);
                    return;
                }
                out.computeIfAbsent(coordinate, k -> new ArrayList<>()).add(new Facet(row.value2(), row.value3(),
                    row.value5(), Boolean.TRUE.equals(row.value6()), row.value7(), Boolean.TRUE.equals(row.value8()),
                    new ColumnRef(row.value9(), row.value10(), row.value11(), row.value12()),
                    steps.getOrDefault(row.value4(), List.of())));
            });
        held.forEach(out::remove);
        return out;
    }

    /**
     * The steps each facet's filter occurrence's path takes from the table its field reads, by that
     * occurrence, in step order: the tables each leaves and reaches, the foreign key it rides, written
     * or inferred, or the primary key it joins a row to itself on, its column pairs, and its condition
     * method.
     */
    private static Map<String, List<FacetStep>> facetSteps(StoreHandle store) {
        var pf = GRAPHITRON_OCCURRENCE_PRODUCER_FACET;
        var pn = GRAPHITRON_PATH_NEED_TABLE;
        var js = GRAPHITRON_JOIN_PATH_STEP;
        var wk = GRAPHITRON_JOIN_PATH_STEP_KEY;
        var ik = GRAPHITRON_JOIN_PATH_STEP_INFERRED_KEY;
        var pk = GRAPHITRON_JOIN_PATH_STEP_PRIMARY_KEY;
        var jm = GRAPHITRON_JOIN_PATH_STEP_METHOD;
        var k = SQL_CONSTRAINT;
        var s = SQL_SCHEMA;
        var spk = SQL_PRIMARY_KEY;
        var jc = GRAPHITRON_JOIN_PATH_STEP_COLUMN;
        var fc = SQL_COLUMN.as("fc");
        var tc = SQL_COLUMN.as("tc");
        var keySource = coalesce(wk.CONSTRAINT_SOURCE_NAME, ik.CONSTRAINT_SOURCE_NAME);
        var keySchema = coalesce(wk.CONSTRAINT_SCHEMA, ik.CONSTRAINT_SCHEMA);
        var keyTable = coalesce(wk.CONSTRAINT_TABLE, ik.CONSTRAINT_TABLE);
        var keyName = coalesce(wk.CONSTRAINT_NAME, ik.CONSTRAINT_NAME);
        var out = new LinkedHashMap<String, List<FacetStep>>();
        var rows = store.dsl()
            .select(js.COORDINATE, js.TARGET_TYPE, js.POSITION, js.FROM_SOURCE_NAME, js.FROM_SCHEMA, js.FROM_TABLE,
                js.TO_SOURCE_NAME, js.TO_SCHEMA, js.TO_TABLE, keyName, k.JOOQ_NAME, s.KEYS_CLASS_FQN,
                spk.CONSTRAINT_NAME, jm.METHOD_CLASS_NAME, jm.METHOD_NAME)
            .from(pf)
            .join(pn).on(pn.GRAPH_NAME.eq(pf.GRAPH_NAME), pn.COORDINATE.eq(pf.FILTER_COORDINATE))
            .join(js).on(js.GRAPH_NAME.eq(pn.GRAPH_NAME), js.COORDINATE.eq(pn.COORDINATE),
                js.TARGET_TYPE.eq(pn.TARGET_TYPE))
            .leftJoin(wk).on(wk.GRAPH_NAME.eq(js.GRAPH_NAME), wk.COORDINATE.eq(js.COORDINATE),
                wk.TARGET_TYPE.eq(js.TARGET_TYPE), wk.POSITION.eq(js.POSITION))
            .leftJoin(ik).on(ik.GRAPH_NAME.eq(js.GRAPH_NAME), ik.COORDINATE.eq(js.COORDINATE),
                ik.TARGET_TYPE.eq(js.TARGET_TYPE), ik.POSITION.eq(js.POSITION))
            .leftJoin(k).on(k.SOURCE_NAME.eq(keySource), k.TABLE_SCHEMA.eq(keySchema), k.TABLE_NAME.eq(keyTable),
                k.CONSTRAINT_NAME.eq(keyName))
            .leftJoin(s).on(s.SOURCE_NAME.eq(k.SOURCE_NAME), s.TABLE_SCHEMA.eq(k.TABLE_SCHEMA))
            .leftJoin(pk).on(pk.GRAPH_NAME.eq(js.GRAPH_NAME), pk.COORDINATE.eq(js.COORDINATE),
                pk.TARGET_TYPE.eq(js.TARGET_TYPE), pk.POSITION.eq(js.POSITION))
            .leftJoin(spk).on(spk.SOURCE_NAME.eq(pk.TABLE_SOURCE_NAME), spk.TABLE_SCHEMA.eq(pk.TABLE_SCHEMA),
                spk.TABLE_NAME.eq(pk.TABLE_NAME))
            .leftJoin(jm).on(jm.GRAPH_NAME.eq(js.GRAPH_NAME), jm.COORDINATE.eq(js.COORDINATE),
                jm.TARGET_TYPE.eq(js.TARGET_TYPE), jm.POSITION.eq(js.POSITION))
            .where(pf.GRAPH_NAME.eq(store.graphName()))
            .orderBy(js.COORDINATE, js.TARGET_TYPE, js.POSITION)
            .fetch();
        for (var row : rows) {
            var pairs = store.dsl()
                .select(fc.COLUMN_NAME, fc.JOOQ_NAME, fc.BINDING_TYPE, fc.DATABASE_TYPE,
                    tc.COLUMN_NAME, tc.JOOQ_NAME, tc.BINDING_TYPE, tc.DATABASE_TYPE)
                .from(jc)
                .join(fc).on(fc.SOURCE_NAME.eq(jc.FROM_SOURCE_NAME), fc.TABLE_SCHEMA.eq(jc.FROM_SCHEMA),
                    fc.TABLE_NAME.eq(jc.FROM_TABLE), fc.COLUMN_NAME.eq(jc.FROM_COLUMN))
                .join(tc).on(tc.SOURCE_NAME.eq(jc.TO_SOURCE_NAME), tc.TABLE_SCHEMA.eq(jc.TO_SCHEMA),
                    tc.TABLE_NAME.eq(jc.TO_TABLE), tc.COLUMN_NAME.eq(jc.TO_COLUMN))
                .where(jc.GRAPH_NAME.eq(store.graphName()), jc.COORDINATE.eq(row.value1()),
                    jc.TARGET_TYPE.eq(row.value2()), jc.POSITION.eq(row.value3()))
                .orderBy(jc.ORDINAL)
                .fetch(r -> new Pair(new ColumnRef(r.value1(), r.value2(), r.value3(), r.value4()),
                    new ColumnRef(r.value5(), r.value6(), r.value7(), r.value8())));
            var foreignKey = row.value10() == null || row.value12() == null ? null
                : new ForeignKeyRef(row.value10(), row.value12(), row.value11());
            out.computeIfAbsent(row.value1(), x -> new ArrayList<>()).add(new FacetStep(
                tableRef(store, row.value4(), row.value5(), row.value6()),
                tableRef(store, row.value7(), row.value8(), row.value9()),
                foreignKey, row.value13(), pairs,
                row.value14() == null ? null : new JoinMethod(row.value14(), row.value15())));
        }
        return out;
    }

    /**
     * One key of a root's order: a column of the list's own table, or a column down a path, read off
     * the store's rows for the key at its occurrence, choice and position. A path key is nullable
     * whatever it reads, a parent with no related row sorting on null.
     */
    private static Key keyOf(ColumnRef column, boolean nullable, String direction, String collation, String kind,
                             List<Object> at, Map<List<Object>, List<Hop>> hops) {
        return "PATH".equals(kind)
            ? new Key(column, true, direction, collation, new Derived.Path(hops.getOrDefault(at, List.of())))
            : new Key(column, nullable, direction, collation);
    }

    /**
     * Each root's path keys' hops, by occurrence, choice and position, in hop order: the table each
     * leaves and the one it reaches, the foreign key it rides as the catalog names it, and its column
     * pairs from the side it leaves to the side it reaches.
     */
    private static Map<List<Object>, List<Hop>> sortKeyHops(StoreHandle store) {
        var h = GRAPHITRON_OCCURRENCE_SORT_KEY_HOP;
        var p = GRAPHITRON_OCCURRENCE_PRODUCER;
        var k = SQL_CONSTRAINT;
        var s = SQL_SCHEMA;
        var rcc = SQL_REFERENTIAL_CONSTRAINT_COLUMN;
        var hc = SQL_CONSTRAINT_COLUMN.as("hc");
        var rfc = SQL_CONSTRAINT_COLUMN.as("rfc");
        var hcol = SQL_COLUMN.as("hcol");
        var rcol = SQL_COLUMN.as("rcol");
        var out = new LinkedHashMap<List<Object>, List<Hop>>();
        var rows = store.dsl()
            .select(h.COORDINATE, h.CHOICE, h.POSITION, h.DIRECTION, h.FROM_SOURCE_NAME, h.FROM_SCHEMA, h.FROM_TABLE,
                h.TO_SOURCE_NAME, h.TO_SCHEMA, h.TO_TABLE, h.CONSTRAINT_SOURCE_NAME, h.CONSTRAINT_SCHEMA,
                h.CONSTRAINT_TABLE, h.CONSTRAINT_NAME, k.JOOQ_NAME, s.KEYS_CLASS_FQN)
            .from(h)
            .join(p).on(p.GRAPH_NAME.eq(h.GRAPH_NAME), p.COORDINATE.eq(h.COORDINATE), p.START.eq("ROOT"))
            .join(k).on(k.SOURCE_NAME.eq(h.CONSTRAINT_SOURCE_NAME), k.TABLE_SCHEMA.eq(h.CONSTRAINT_SCHEMA),
                k.TABLE_NAME.eq(h.CONSTRAINT_TABLE), k.CONSTRAINT_NAME.eq(h.CONSTRAINT_NAME))
            .join(s).on(s.SOURCE_NAME.eq(k.SOURCE_NAME), s.TABLE_SCHEMA.eq(k.TABLE_SCHEMA))
            .where(h.GRAPH_NAME.eq(store.graphName()))
            .orderBy(h.COORDINATE, h.CHOICE, h.POSITION, h.HOP)
            .fetch();
        for (var row : rows) {
            boolean forward = "FORWARD".equals(row.value4());
            // The key's columns in its order, the holding table's beside the referenced one's.
            var pairs = store.dsl()
                .select(hcol.COLUMN_NAME, hcol.JOOQ_NAME, hcol.BINDING_TYPE, hcol.DATABASE_TYPE,
                    rcol.COLUMN_NAME, rcol.JOOQ_NAME, rcol.BINDING_TYPE, rcol.DATABASE_TYPE)
                .from(rcc)
                .join(hc).on(hc.SOURCE_NAME.eq(rcc.SOURCE_NAME), hc.TABLE_SCHEMA.eq(rcc.TABLE_SCHEMA),
                    hc.TABLE_NAME.eq(rcc.TABLE_NAME), hc.CONSTRAINT_NAME.eq(rcc.CONSTRAINT_NAME),
                    hc.POSITION.eq(rcc.POSITION))
                .join(rfc).on(rfc.SOURCE_NAME.eq(rcc.REFERENCED_SOURCE_NAME), rfc.TABLE_SCHEMA.eq(rcc.REFERENCED_SCHEMA),
                    rfc.TABLE_NAME.eq(rcc.REFERENCED_TABLE), rfc.CONSTRAINT_NAME.eq(rcc.REFERENCED_CONSTRAINT_NAME),
                    rfc.POSITION.eq(rcc.REFERENCED_POSITION))
                .join(hcol).on(hcol.SOURCE_NAME.eq(hc.SOURCE_NAME), hcol.TABLE_SCHEMA.eq(hc.TABLE_SCHEMA),
                    hcol.TABLE_NAME.eq(hc.TABLE_NAME), hcol.COLUMN_NAME.eq(hc.COLUMN_NAME))
                .join(rcol).on(rcol.SOURCE_NAME.eq(rfc.SOURCE_NAME), rcol.TABLE_SCHEMA.eq(rfc.TABLE_SCHEMA),
                    rcol.TABLE_NAME.eq(rfc.TABLE_NAME), rcol.COLUMN_NAME.eq(rfc.COLUMN_NAME))
                .where(rcc.SOURCE_NAME.eq(row.value11()), rcc.TABLE_SCHEMA.eq(row.value12()),
                    rcc.TABLE_NAME.eq(row.value13()), rcc.CONSTRAINT_NAME.eq(row.value14()))
                .orderBy(rcc.POSITION)
                .fetch(r -> {
                    var holding = new ColumnRef(r.value1(), r.value2(), r.value3(), r.value4());
                    var referenced = new ColumnRef(r.value5(), r.value6(), r.value7(), r.value8());
                    return forward ? new Pair(holding, referenced) : new Pair(referenced, holding);
                });
            var hop = new Hop(tableRef(store, row.value5(), row.value6(), row.value7()),
                tableRef(store, row.value8(), row.value9(), row.value10()),
                new ForeignKeyRef(row.value14(), row.value16(), row.value15()), pairs);
            out.computeIfAbsent(List.of(row.value1(), row.value2(), row.value3()), x -> new ArrayList<>()).add(hop);
        }
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
        var hops = sortKeyHops(store);
        store.dsl()
            .select(od.COORDINATE, c.COLUMN_NAME, c.JOOQ_NAME, c.BINDING_TYPE, c.DATABASE_TYPE, c.NULLABLE,
                sk.DIRECTION, sk.COLLATION, sk.KIND, sk.CHOICE, sk.POSITION)
            .from(od)
            .join(p).on(p.GRAPH_NAME.eq(od.GRAPH_NAME), p.COORDINATE.eq(od.COORDINATE), p.START.eq("ROOT"))
            .join(sk).on(sk.GRAPH_NAME.eq(od.GRAPH_NAME), sk.COORDINATE.eq(od.COORDINATE), sk.BOUND.eq(od.BOUND),
                sk.CHOICE.eq(od.CHOICE))
            .leftJoin(kc).on(kc.GRAPH_NAME.eq(sk.GRAPH_NAME), kc.COORDINATE.eq(sk.COORDINATE), kc.BOUND.eq(sk.BOUND),
                kc.CHOICE.eq(sk.CHOICE), kc.POSITION.eq(sk.POSITION))
            .leftJoin(c).on(c.SOURCE_NAME.eq(kc.TABLE_SOURCE_NAME), c.TABLE_SCHEMA.eq(kc.TABLE_SCHEMA),
                c.TABLE_NAME.eq(kc.TABLE_NAME), c.COLUMN_NAME.eq(kc.COLUMN_NAME))
            .where(od.GRAPH_NAME.eq(store.graphName()), od.KIND.eq("DEFAULT"))
            .orderBy(od.COORDINATE, sk.POSITION)
            .forEach(row -> out.computeIfAbsent(row.value1(), k -> new ArrayList<>()).add(keyOf(
                row.value2() == null ? null : new ColumnRef(row.value2(), row.value3(), row.value4(), row.value5()),
                Boolean.TRUE.equals(row.value6()), row.value7(), row.value8(), row.value9(),
                List.of(row.value1(), row.value10(), row.value11()), hops)));
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
