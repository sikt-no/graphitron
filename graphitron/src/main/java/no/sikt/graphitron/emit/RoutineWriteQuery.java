package no.sikt.graphitron.emit;

import no.sikt.graphitron.model.read.StoreHandle;
import no.sikt.graphitron.model.tables.GraphitronOccurrenceProducerRoutine;
import org.jooq.Field;
import org.jooq.Records;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_FIELD;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_FIELD_TARGET;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_JOIN_PATH;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_JOIN_PATH_STEP;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_JOIN_PATH_STEP_COLUMN;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_BINDING_EXTRACTION;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_BINDING_PARAMETER;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_ARGUMENT_STEP;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_BINDING;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_CALL_PARAMETER;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_INPUT_ORDER;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_ORDER;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_PRODUCER;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_LIFT;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_NODE_ID;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_NODE_ID_SHAPE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_NODE_ID_SLOT_PARAMETER;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_NODE_ID_SLOT_PROJECTION;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_PRODUCER_LIFT;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_PRODUCER_RESULT;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_PRODUCER_ROUTINE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_SORT_KEY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_SORT_KEY_COLUMN;
import static no.sikt.graphitron.model.Tables.GRAPHQL_ROOT_OPERATION;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_PRODUCER_ROUTINE;
import static no.sikt.graphitron.model.Tables.SQL_COLUMN;
import static no.sikt.graphitron.model.Tables.SQL_NAME_MATCHED_KEY_COLUMN;
import static no.sikt.graphitron.model.Tables.SQL_ROUTINE;
import static no.sikt.graphitron.model.Tables.SQL_ROUTINE_PARAMETER;
import static no.sikt.graphitron.model.Tables.SQL_SCHEMA;
import static no.sikt.graphitron.model.Tables.SQL_TABLE;
import static org.jooq.impl.DSL.exists;
import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.multiset;
import static org.jooq.impl.DSL.row;
import static org.jooq.impl.DSL.select;
import static org.jooq.impl.DSL.selectOne;
import static org.jooq.impl.DSL.when;

/**
 * {@link RoutineWriteEmitter}'s query: every {@code @routine}-writing mutation coordinate whose
 * re-read the store states, with every catalog name its body spells. The fetcher around the body is
 * {@link FrameEmitter}'s frame, read by {@link FrameQuery} and {@link ConnectionQuery} and carried
 * beside the rows ({@link Rows#framed}).
 *
 * <p>A routine write is an occurrence on the mutation root whose producer is a {@code ROUTINE}: the
 * store states the callable it calls ({@code graphitron_occurrence_producer_routine}) and what it
 * produces ({@code graphitron_occurrence_producer_result}). The write's statement takes no step
 * onward, since it holds the write's locks, so its result is re-read in a statement of its own, and
 * the result's shape says where that re-read is stated:
 *
 * <ul>
 *   <li>a result of <b>records</b> lifted onto a table ({@link RecordsResult}) is lifted along the
 *       field's own join path, from the routine's result to that table: the write captures the key of
 *       the path's first table, the anchor, matched by name out of the routine's result, and
 *       {@link LiftQuery} states the lift from there;</li>
 *   <li>an <b>object</b> held on the routine's result ({@link ObjectResult}) is re-read by the child
 *       occurrences under the field filled with columns, each paired by name from the routine's
 *       result to the table its fill names.</li>
 * </ul>
 *
 * <p>Admission is those facts being present and nothing else: a coordinate has a row where the store
 * states its re-read, and no row where it does not. Whether the schema is valid is the build's
 * question, answered by the store's defects, which stop the build before anything is emitted; this
 * query does not read them. A closed list of shapes the store states but the emitter does not render
 * yet is named by {@link Unrendered} and never reaches the rows.
 *
 * <p>Five statements at their grains, which is the rule and not a tuning. The occurrence statement
 * reads every routine-write occurrence with its call, its join path's anchor and its re-reading
 * children, the list as a correlated {@code MULTISET}, which is what admission is decided from. The
 * anchor's captured pairing, a re-reading child's, a plural identifying write's stated order and the
 * routine's parameters with the store's fills for them are their own statements, paired back on keys
 * the store declares and narrowed to the admitted coordinates, read once here instead of once per
 * coordinate. Every catalog name is captured rather than derived
 * ({@code sql_table.class_fqn}, {@code sql_schema.tables_class_fqn} and {@code keys_class_fqn},
 * {@code sql_constraint.jooq_name}), so a row is built with no live catalog.
 *
 * <p><b>Cost.</b> The {@code MULTISET}s correlate stored rows read on their key, the occurrences
 * directly under the field once per routine-write occurrence on the mutation root, and a fill's path
 * steps once per routine parameter. That population is the graph's routine-writing mutation fields, which is small by
 * construction.
 */
public final class RoutineWriteQuery {

    private RoutineWriteQuery() {}

    /**
     * A table as the generated model names it: its SQL name, its constant's field name on the
     * schema's {@code Tables} class, its generated class and that {@code Tables} class.
     */
    public record Table(String sqlName, String javaFieldName, String tableClassName,
                        String constantsClassName) {
        public Table {
            Objects.requireNonNull(sqlName, "sqlName");
            Objects.requireNonNull(javaFieldName, "javaFieldName");
            Objects.requireNonNull(tableClassName, "tableClassName");
            Objects.requireNonNull(constantsClassName, "constantsClassName");
        }
    }

    /**
     * A column: its SQL name, its generated field name, and its bound Java type as
     * {@code Field.getType().getName()} reports it, which is the post-converter type.
     */
    public record Column(String sqlName, String javaName, String bindingType) {
        public Column {
            Objects.requireNonNull(javaName, "javaName");
            Objects.requireNonNull(bindingType, "bindingType");
        }
    }

    /** One cell of a key pairing: the departing side's column and the arriving side's. */
    public record Pair(Column source, Column target) {}

    /**
     * One key of the order a plural identifying write's capture reads its rows in: a column of the
     * routine's own result, and whether it sorts descending.
     */
    public record OrderKey(Column column, boolean descending) {
        public OrderKey {
            Objects.requireNonNull(column, "column");
        }
    }

    /**
     * One IN parameter of the routine, in declaration order, and what the store fills it with.
     * {@code javaTypeName} is the parameter's binding type as captured, in {@code Class.getName()}'s
     * spelling ({@code [Ljava.lang.Integer;} for an array). {@code pathNames} is where the value is
     * read from the request, the names from the argument down to the argument or input field the fill
     * lands on, and {@code lists} the positions on it whose value is a list; a parameter nothing
     * fills has no names. {@code nodeId} says the landing is a node id and {@code listToArray} that a
     * list is copied into the array the parameter takes, each as the binding's stored extraction
     * states it, and {@code decode} how
     * it is decoded into the one key column the parameter takes, where the store states a
     * {@code KEY_VALUE} shape at its slot. {@code component} is the component of a generated scalar
     * the fill's pair names past its landing, read through the pair's landing, and {@code scalar}
     * what the call decodes that scalar's value as.
     */
    public record Argument(String parameterName, String javaTypeName, List<String> pathNames, Set<Integer> lists,
                           boolean nodeId, boolean listToArray, Optional<KeyValue> decode, Optional<String> component,
                           Optional<GeneratedScalarQuery.Decode> scalar) {
        public Argument {
            Objects.requireNonNull(parameterName, "parameterName");
            Objects.requireNonNull(javaTypeName, "javaTypeName");
            pathNames = List.copyOf(pathNames);
            lists = Set.copyOf(lists);
            Objects.requireNonNull(decode, "decode");
            Objects.requireNonNull(component, "component");
            Objects.requireNonNull(scalar, "scalar");
        }
    }

    /**
     * A node id decoded into its node's key and one column of it taken: the node id occurrence, the
     * node table it is decoded as ({@link NodeIdDecodeQuery#resolve}'s {@code bound}), and the
     * generated name of the key column the parameter takes.
     */
    public record KeyValue(String coordinate, String bound, String columnJavaName) {
        public KeyValue {
            Objects.requireNonNull(coordinate, "coordinate");
            Objects.requireNonNull(bound, "bound");
            Objects.requireNonNull(columnJavaName, "columnJavaName");
        }
    }

    /** The routine call: the generated {@code Routines} class and method, and its result table. */
    public record Call(String routinesClassName, String methodName, Table resultTable,
                       List<Argument> arguments) {
        public Call {
            arguments = List.copyOf(arguments);
        }
    }

    /**
     * One admitted routine-write coordinate, by the shape of what its producer produces.
     */
    public sealed interface Row permits RecordsResult, ObjectResult {

        /** The mutation root type declaring the field. */
        String typeName();

        /** The routine-writing field. */
        String fieldName();

        /** The SDL type the field returns, unwrapped. */
        String returnTypeName();

        /** Whether the field itself returns a list. */
        boolean listReturn();

        /** The routine the write calls. */
        Call call();
    }

    /**
     * A producer whose result is records lifted onto {@code liftTable}, lifted along the field's
     * join path from the routine's result to that table. The write captures the key of the path's
     * first table, {@code anchor}; the lift reads on from there, as {@link LiftQuery} states it.
     *
     * @param capturedPairs the routine result's column of each name beside the anchor's primary-key
     *                      column of that name, as the path's first step pairs them
     * @param statedOrder   for a plural identifying write, a list argument answered one item per
     *                      element, the default order stated on the routine's result, which the
     *                      capture reads its rows in; empty for any other write, whose capture reads
     *                      them in the order the routine delivered them
     */
    public record RecordsResult(String typeName, String fieldName, String returnTypeName,
                                boolean listReturn, Call call, Table liftTable, Table anchor,
                                List<Pair> capturedPairs, List<OrderKey> statedOrder)
            implements Row {

        public RecordsResult {
            Objects.requireNonNull(liftTable, "liftTable");
            Objects.requireNonNull(anchor, "anchor");
            capturedPairs = List.copyOf(capturedPairs);
            statedOrder = List.copyOf(statedOrder);
            if (capturedPairs.isEmpty()) {
                throw new IllegalStateException(
                    "the routine write at " + typeName + "." + fieldName + " produces records of "
                    + liftTable.sqlName() + " and captures no key of " + anchor.sqlName() + ", the"
                    + " first table of its path; with none its lift has nothing to key on");
            }
        }
    }

    /**
     * One child occurrence re-reading an object result: the field, the table its fill names, and
     * the pairing that captures that table's key out of the routine's result.
     *
     * @param listReturn    whether the re-reading field returns a list, which is how many keys the
     *                      write captures
     * @param capturedPairs the routine result's column of each name beside the table's primary-key
     *                      column of that name, in key order
     */
    public record ReRead(String fieldName, Table table, boolean listReturn, List<Pair> capturedPairs) {

        public ReRead {
            Objects.requireNonNull(table, "table");
            capturedPairs = List.copyOf(capturedPairs);
            if (capturedPairs.isEmpty()) {
                throw new IllegalStateException(
                    "the re-reading field " + fieldName + " captures no key of " + table.sqlName()
                    + "; with none its re-read has nothing to correlate on");
            }
        }
    }

    /**
     * A producer whose result is an object held on the routine's result, re-read by the fields
     * under it. The emitter renders an object re-read by one field; more is
     * {@link Unrendered#SEVERAL_RE_READING_FIELDS} and never reaches a row.
     */
    public record ObjectResult(String typeName, String fieldName, String returnTypeName,
                               boolean listReturn, Call call, List<ReRead> reReads) implements Row {

        public ObjectResult {
            reReads = List.copyOf(reReads);
            if (reReads.size() != 1) {
                throw new IllegalStateException(
                    "the routine write at " + typeName + "." + fieldName + " returns an object"
                    + " re-read by " + reReads.size() + " fields; the emitter captures the keys of"
                    + " exactly one");
            }
        }

        /** The one field re-reading the object. */
        public ReRead reRead() {
            return reReads.getFirst();
        }
    }

    /**
     * Shapes the store states a re-read for that the emitter does not render yet. A coordinate in
     * one of these has no row; {@link #unrendered} names them so a test can pin the list closed.
     */
    enum Unrendered {
        /**
         * A records result lifted onto a table along a path with no step out of the routine's result:
         * there is no anchor to key the lift on.
         */
        LIFT_WITHOUT_STEP,
        /** An object result with more than one field re-reading it by key. */
        SEVERAL_RE_READING_FIELDS,
        /**
         * An object result with a field under it that binds a node id, an {@code ID} encoded from
         * the keys. An argument binding a node id is an input to the call and no such field.
         */
        ID_ENCODED_FROM_KEYS,
        /**
         * An object result re-read by a non-null single field: a re-read finding no row would null
         * the whole result. Where the result type has an errors field the store also states a
         * defect; where it has none, this is what keeps the shape out.
         */
        NON_NULL_SINGLE_RE_READ
    }

    /**
     * Every admitted coordinate, in coordinate order, keyed by the coordinate: a second row for one
     * coordinate fails here rather than emitting two methods of one name. Beside them, by the same
     * coordinate, each one's frame from {@link FrameQuery}, in a graph whose connection key has a
     * part its connection from {@link ConnectionQuery}, and for a records result its lift from
     * {@link LiftQuery}: the fetcher is {@link FrameEmitter}'s frame around the body
     * {@link RoutineWriteEmitter} renders, the frame reads the first two, and the body hands its
     * captured keys to the lift. Beside them too, the graph's types whose projection reads the
     * session, from {@link ProjectionSessionQuery}, which a lift onto one hands the connection.
     */
    public record Rows(List<Row> rows, Map<String, FrameQuery.Row> frames,
                       Map<String, ConnectionQuery.Row> connections, Map<String, LiftQuery.Row> lifts,
                       Optional<NodeIdDecodeQuery> decodes, Set<String> sessionProjections) {

        public Rows {
            rows = List.copyOf(rows);
            frames = Map.copyOf(frames);
            connections = Map.copyOf(connections);
            lifts = Map.copyOf(lifts);
            Objects.requireNonNull(decodes, "decodes");
            sessionProjections = Set.copyOf(sessionProjections);
            var seen = new java.util.HashSet<String>();
            for (var r : rows) {
                if (!seen.add(coordinate(r))) {
                    throw new IllegalArgumentException(
                        "the routine-write rows are keyed by coordinate; " + coordinate(r)
                        + " appears twice");
                }
            }
        }

        /** The rows alone, with no frame, connection, lift or decode read beside them. */
        public Rows(List<Row> rows) {
            this(rows, Map.of(), Map.of(), Map.of(), Optional.empty(), Set.of());
        }

        /** No rows: a graph that writes through no routine, or a run with no store. */
        public static Rows empty() {
            return new Rows(List.of());
        }

        /**
         * These rows with each one's frame out of {@code graphFrames}, connection out of
         * {@code graphConnections} and lift out of {@code graphLifts}, the rest dropped, and the
         * graph's node id decodes, which an argument's {@link KeyValue} is resolved against, and the
         * graph's types whose projection reads the session, {@code graphSessionProjections}. Every row has
         * a frame: a routine write is a root occurrence, which {@link FrameQuery} frames by
         * construction, so a row with none is drift between the two queries. Every records result has
         * a lift, both this query and {@link LiftQuery} reading {@code graphitron_occurrence_lift}, so
         * one with none is a defect in one of them.
         */
        public Rows framed(List<FrameQuery.Row> graphFrames, Map<String, ConnectionQuery.Row> graphConnections,
                           List<LiftQuery.Row> graphLifts, NodeIdDecodeQuery graphDecodes,
                           Set<String> graphSessionProjections) {
            var framed = new LinkedHashMap<String, FrameQuery.Row>();
            graphFrames.forEach(f -> framed.put(f.coordinate(), f));
            var lifted = new LinkedHashMap<String, LiftQuery.Row>();
            graphLifts.forEach(l -> lifted.put(l.coordinate(), l));
            var ownFrames = new LinkedHashMap<String, FrameQuery.Row>();
            var ownConnections = new LinkedHashMap<String, ConnectionQuery.Row>();
            var ownLifts = new LinkedHashMap<String, LiftQuery.Row>();
            for (var r : rows) {
                String coordinate = coordinate(r);
                var frame = framed.get(coordinate);
                if (frame == null) {
                    throw new IllegalStateException("Graphitron generator bug (routine-write frame): "
                        + coordinate + " has a routine-write row and no frame; the two queries have drifted");
                }
                ownFrames.put(coordinate, frame);
                var connection = graphConnections.get(coordinate);
                if (connection != null) {
                    ownConnections.put(coordinate, connection);
                }
                if (r instanceof RecordsResult) {
                    var lift = lifted.get(coordinate);
                    if (lift == null) {
                        throw new IllegalStateException("Graphitron generator bug (routine-write lift): "
                            + coordinate + " has a records row and no lift; the two queries have drifted");
                    }
                    ownLifts.put(coordinate, lift);
                }
            }
            return new Rows(rows, ownFrames, ownConnections, ownLifts, Optional.of(graphDecodes),
                graphSessionProjections);
        }

        /** The row for one coordinate, empty when the coordinate writes through no routine. */
        public Optional<Row> rowFor(String typeName, String fieldName) {
            return rows.stream()
                .filter(r -> r.typeName().equals(typeName) && r.fieldName().equals(fieldName))
                .findFirst();
        }

        /** {@code row}'s frame, empty where these rows were read with none beside them. */
        public Optional<FrameQuery.Row> frameFor(Row row) {
            return Optional.ofNullable(frames.get(coordinate(row)));
        }

        /**
         * The decode of {@code argument}'s node id into its node's own record, whose key column the
         * parameter takes. A {@code KEY_VALUE} shape the store states at the slot always resolves, the
         * shape being stated only where the id binds one node over a table with a generated record,
         * so a refusal here is the store and this reader disagreeing.
         */
        public NodeIdDecodeQuery.Decode decodeFor(Row row, Argument argument) {
            var keyValue = argument.decode().orElseThrow(() -> new IllegalArgumentException(
                "the argument " + argument.parameterName() + " of " + coordinate(row) + " decodes nothing"));
            var graphDecodes = decodes.orElseThrow(() -> new IllegalStateException(
                "Graphitron generator bug (routine-write decode): " + coordinate(row) + " decodes a node id"
                + " and its rows were read with no decodes beside them"));
            return switch (graphDecodes.resolve(keyValue.coordinate(), keyValue.bound(), Optional.empty())) {
                case NodeIdDecodeQuery.Resolution.Resolved resolved -> resolved.decode();
                case NodeIdDecodeQuery.Resolution.Refused refused -> throw new IllegalStateException(
                    "Graphitron generator bug (routine-write decode): the store states a KEY_VALUE shape for "
                    + argument.parameterName() + " of " + coordinate(row) + ", and its decode is refused: "
                    + refused.reason());
            };
        }

        /** Whether {@code typeName}'s projection reads the session, so a lift onto it hands over the connection. */
        public boolean projectsWithSession(String typeName) {
            return sessionProjections.contains(typeName);
        }

        /** {@code row}'s lift, empty for an object result or where these rows were read with none. */
        public Optional<LiftQuery.Row> liftFor(Row row) {
            return Optional.ofNullable(lifts.get(coordinate(row)));
        }

        /**
         * {@code row}'s connection, empty where the store states none: in a graph whose connection
         * key has no part, the request's own.
         */
        public Optional<ConnectionQuery.Row> connectionFor(Row row) {
            return Optional.ofNullable(connections.get(coordinate(row)));
        }

        /** A root occurrence's coordinate: its type and field. */
        private static String coordinate(Row row) {
            return row.typeName() + "." + row.fieldName();
        }
    }

    /**
     * Reads every routine-write coordinate whose re-read the store states. A coordinate whose
     * re-read it does not state has no row, and that is the whole of membership.
     */
    public static Rows read(StoreHandle store) {
        var occurrences = occurrences(store);
        var records = occurrences.stream().filter(RoutineWriteQuery::admittedAsRecords).toList();
        var objects = occurrences.stream().filter(RoutineWriteQuery::admittedAsObject).toList();
        var anchorPairs = anchorPairs(store, records);
        var statedOrders = statedOrders(store, records);
        var reReadPairs = reReadPairs(store, objects);
        var admitted = new ArrayList<Occurrence>(records);
        admitted.addAll(objects);
        var arguments = arguments(store, admitted);
        var rows = new ArrayList<Row>();
        for (var o : occurrences) {
            var coordinate = o.typeName() + "." + o.fieldName();
            var call = new Call(o.call().routinesClassName(), o.call().methodName(), o.call().resultTable(),
                arguments.getOrDefault(coordinate, List.of()));
            if (records.contains(o)) {
                rows.add(new RecordsResult(o.typeName(), o.fieldName(), o.returnTypeName(),
                    o.listReturn(), call, o.liftTable(), o.anchor(),
                    anchorPairs.getOrDefault(o.typeName() + "." + o.fieldName(), List.of()),
                    statedOrders.getOrDefault(o.typeName() + "." + o.fieldName(), List.of())));
            } else if (objects.contains(o)) {
                rows.add(new ObjectResult(o.typeName(), o.fieldName(), o.returnTypeName(),
                    o.listReturn(), call,
                    o.reReads().stream()
                        .map(r -> new ReRead(r.fieldName(), r.table(), r.listReturn(),
                            reReadPairs.getOrDefault(r.coordinate(), List.of())))
                        .toList()));
            }
        }
        return new Rows(rows);
    }

    /**
     * Every routine-write coordinate whose re-read the store states in a shape the emitter does not
     * render yet, keyed by its {@code Type.field} coordinate, in coordinate order.
     */
    static Map<String, Unrendered> unrendered(StoreHandle store) {
        var found = new LinkedHashMap<String, Unrendered>();
        for (var o : occurrences(store)) {
            unrenderedCase(o).ifPresent(c -> found.put(o.typeName() + "." + o.fieldName(), c));
        }
        return found;
    }

    // -------------------------------------------------------------------------------------
    // Admission: the shape of the result, and whether its re-read is stated.
    // -------------------------------------------------------------------------------------

    /**
     * One routine-write occurrence on the mutation root, as the store states it: the shape of its
     * producer's result and the table records are lifted onto, whether the field's join path lands
     * on that table or on the routine's own result, the table its path's first step reaches, whether
     * every step of the path states the column pairs it joins on, the children that re-read by their
     * fill's key, and whether a child binds a node id.
     */
    private record Occurrence(String typeName, String fieldName, String returnTypeName,
                              boolean listReturn, String shape, Call call, Table liftTable,
                              boolean lifted, boolean landsOnRoutineResult, Table anchor,
                              List<RawReRead> reReads, boolean nodeIdChild) {

        /** Records of one table: the shape lifted along the field's own join path. */
        boolean records() {
            return ("SINGLE_RECORD".equals(shape) || "RECORD_LIST".equals(shape)) && liftTable != null;
        }

        /** An object held where the routine's result is: the shape its children re-read. */
        boolean object() {
            return "OBJECT".equals(shape) && landsOnRoutineResult;
        }
    }

    /**
     * A records result the store states lifted ({@code graphitron_occurrence_lift}): its path lands on
     * the lift table, takes a first step out of the routine's result and joins every step by column
     * pairs, which {@link LiftQuery} reads the same row for.
     */
    private static boolean admittedAsRecords(Occurrence o) {
        return o.records() && o.lifted();
    }

    /**
     * An object result re-read by one child whose key is matched by name out of the routine's
     * result, in none of the {@link Unrendered} shapes.
     */
    private static boolean admittedAsObject(Occurrence o) {
        return o.object() && unrenderedCase(o).isEmpty() && o.reReads().size() == 1
            && o.reReads().getFirst().keyMatched();
    }

    private static Optional<Unrendered> unrenderedCase(Occurrence o) {
        if (o.records()) {
            return o.anchor() == null ? Optional.of(Unrendered.LIFT_WITHOUT_STEP) : Optional.empty();
        }
        if (!o.object()) {
            return Optional.empty();
        }
        if (o.nodeIdChild()) {
            return Optional.of(Unrendered.ID_ENCODED_FROM_KEYS);
        }
        if (o.reReads().size() > 1) {
            return Optional.of(Unrendered.SEVERAL_RE_READING_FIELDS);
        }
        if (o.reReads().size() == 1 && o.reReads().getFirst().nonNullSingle()) {
            return Optional.of(Unrendered.NON_NULL_SINGLE_RE_READ);
        }
        return Optional.empty();
    }

    // -------------------------------------------------------------------------------------
    // Statement one: the occurrence grain.
    // -------------------------------------------------------------------------------------

    /**
     * Every occurrence on the mutation root whose producer calls a routine, with the facts its
     * admission reads, its call, its path's anchor and its re-reading children.
     *
     * <p>The producer's routine, its result, the call surface the generated model publishes, the
     * routine's result table and its constants class are inner joins: an occurrence missing one
     * states no call to render. The lift table and the anchor are left joins because only a records
     * result has them. The call's arguments are their own statement's, {@link #arguments}, read
     * for the admitted coordinates.
     */
    private static List<Occurrence> occurrences(StoreHandle store) {
        var p = GRAPHITRON_OCCURRENCE_PRODUCER;
        var o = GRAPHITRON_OCCURRENCE;
        var f = GRAPHITRON_FIELD;
        var ro = GRAPHQL_ROOT_OPERATION;
        var ft = GRAPHITRON_FIELD_TARGET;
        var pr = GRAPHITRON_OCCURRENCE_PRODUCER_ROUTINE;
        var res = GRAPHITRON_OCCURRENCE_PRODUCER_RESULT;
        var called = GRAPHITRON_PRODUCER_ROUTINE;
        var sr = SQL_ROUTINE;
        var rt = SQL_TABLE.as("routine_result");
        var rs = SQL_SCHEMA.as("routine_schema");
        var lt = SQL_TABLE.as("lift_table");
        var ls = SQL_SCHEMA.as("lift_schema");
        var jp = GRAPHITRON_JOIN_PATH;
        var first = GRAPHITRON_JOIN_PATH_STEP.as("anchor_step");
        var at = SQL_TABLE.as("anchor_table");
        var anchorSchema = SQL_SCHEMA.as("anchor_schema");
        var child = GRAPHITRON_OCCURRENCE.as("encoding_child");
        var b = GRAPHITRON_OCCURRENCE_BINDING;
        var lift = GRAPHITRON_OCCURRENCE_LIFT;
        Field<Boolean> lifted = field(exists(selectOne().from(lift)
            .where(lift.GRAPH_NAME.eq(p.GRAPH_NAME), lift.COORDINATE.eq(p.COORDINATE))));
        Field<Boolean> landsOnRoutineResult = field(exists(selectOne().from(jp)
            .where(jp.GRAPH_NAME.eq(p.GRAPH_NAME), jp.COORDINATE.eq(p.COORDINATE),
                jp.TARGET_TYPE.eq(ft.TARGET_TYPE_NAME), jp.TARGET_SOURCE_NAME.eq(pr.ROUTINE_SOURCE_NAME),
                jp.TARGET_SCHEMA.eq(pr.TABLE_SCHEMA), jp.TARGET_TABLE.eq(pr.ROUTINE_NAME))));
        Field<Boolean> nodeIdChild = field(exists(selectOne().from(child)
            .join(b).on(b.GRAPH_NAME.eq(child.GRAPH_NAME), b.COORDINATE.eq(child.COORDINATE),
                b.KIND.eq("NODE_ID"))
            .where(child.GRAPH_NAME.eq(p.GRAPH_NAME), child.PARENT_COORDINATE.eq(p.COORDINATE),
                child.KIND.eq("FIELD"))));
        var reReads = reReads(p.GRAPH_NAME, p.COORDINATE, pr);
        return store.dsl()
            .select(f.TYPE_NAME, f.FIELD_NAME, ft.TARGET_TYPE_NAME, f.IS_LIST, res.SHAPE,
                sr.ROUTINES_CLASS_FQN, sr.ROUTINES_METHOD_NAME,
                rt.TABLE_NAME, rt.JOOQ_NAME, rt.CLASS_FQN, rs.TABLES_CLASS_FQN,
                lt.TABLE_NAME, lt.JOOQ_NAME, lt.CLASS_FQN, ls.TABLES_CLASS_FQN,
                lifted, landsOnRoutineResult,
                at.TABLE_NAME, at.JOOQ_NAME, at.CLASS_FQN, anchorSchema.TABLES_CLASS_FQN,
                reReads, nodeIdChild)
            .from(p)
            .join(o).on(o.GRAPH_NAME.eq(p.GRAPH_NAME), o.COORDINATE.eq(p.COORDINATE))
            .join(f).on(f.GRAPH_NAME.eq(o.GRAPH_NAME), f.COORDINATE.eq(o.ELEMENT_COORDINATE))
            .join(ro).on(ro.GRAPH_NAME.eq(f.GRAPH_NAME), ro.TYPE_NAME.eq(f.TYPE_NAME),
                ro.OPERATION.eq("MUTATION"))
            .join(ft).on(ft.GRAPH_NAME.eq(f.GRAPH_NAME), ft.TYPE_NAME.eq(f.TYPE_NAME),
                ft.FIELD_NAME.eq(f.FIELD_NAME))
            .join(pr).on(pr.GRAPH_NAME.eq(p.GRAPH_NAME), pr.COORDINATE.eq(p.COORDINATE))
            .join(res).on(res.GRAPH_NAME.eq(p.GRAPH_NAME), res.COORDINATE.eq(p.COORDINATE))
            .join(called).on(called.GRAPH_NAME.eq(f.GRAPH_NAME), called.TYPE_NAME.eq(f.TYPE_NAME),
                called.FIELD_NAME.eq(f.FIELD_NAME))
            .join(sr).on(sr.SOURCE_NAME.eq(pr.ROUTINE_SOURCE_NAME),
                sr.TABLE_SCHEMA.eq(pr.TABLE_SCHEMA), sr.ROUTINE_NAME.eq(pr.ROUTINE_NAME),
                sr.ROUTINES_CLASS_FQN.isNotNull())
            .join(rt).on(rt.SOURCE_NAME.eq(sr.SOURCE_NAME), rt.TABLE_SCHEMA.eq(sr.TABLE_SCHEMA),
                rt.TABLE_NAME.eq(sr.ROUTINE_NAME))
            .join(rs).on(rs.SOURCE_NAME.eq(rt.SOURCE_NAME), rs.TABLE_SCHEMA.eq(rt.TABLE_SCHEMA))
            .leftJoin(lt).on(lt.SOURCE_NAME.eq(res.TABLE_SOURCE_NAME),
                lt.TABLE_SCHEMA.eq(res.TABLE_SCHEMA), lt.TABLE_NAME.eq(res.TABLE_NAME))
            .leftJoin(ls).on(ls.SOURCE_NAME.eq(lt.SOURCE_NAME), ls.TABLE_SCHEMA.eq(lt.TABLE_SCHEMA))
            // The path's first step out of the routine's result, and the table it reaches.
            .leftJoin(first).on(first.GRAPH_NAME.eq(p.GRAPH_NAME), first.COORDINATE.eq(p.COORDINATE),
                first.TARGET_TYPE.eq(ft.TARGET_TYPE_NAME), first.POSITION.eq(0))
            .leftJoin(at).on(at.SOURCE_NAME.eq(first.TO_SOURCE_NAME), at.TABLE_SCHEMA.eq(first.TO_SCHEMA),
                at.TABLE_NAME.eq(first.TO_TABLE))
            .leftJoin(anchorSchema).on(anchorSchema.SOURCE_NAME.eq(at.SOURCE_NAME),
                anchorSchema.TABLE_SCHEMA.eq(at.TABLE_SCHEMA))
            .where(p.GRAPH_NAME.eq(store.graphName()), p.KIND.eq("ROUTINE"), p.START.eq("ROOT"))
            .orderBy(f.TYPE_NAME, f.FIELD_NAME)
            .fetch(r -> new Occurrence(r.get(f.TYPE_NAME), r.get(f.FIELD_NAME),
                r.get(ft.TARGET_TYPE_NAME), Boolean.TRUE.equals(r.get(f.IS_LIST)), r.get(res.SHAPE),
                new Call(r.get(sr.ROUTINES_CLASS_FQN), r.get(sr.ROUTINES_METHOD_NAME),
                    new Table(r.get(rt.TABLE_NAME), r.get(rt.JOOQ_NAME), r.get(rt.CLASS_FQN),
                        r.get(rs.TABLES_CLASS_FQN)),
                    List.of()),
                r.get(lt.TABLE_NAME) == null ? null : new Table(r.get(lt.TABLE_NAME),
                    r.get(lt.JOOQ_NAME), r.get(lt.CLASS_FQN), r.get(ls.TABLES_CLASS_FQN)),
                Boolean.TRUE.equals(r.get(lifted)), Boolean.TRUE.equals(r.get(landsOnRoutineResult)),
                r.get(at.TABLE_NAME) == null ? null : new Table(r.get(at.TABLE_NAME), r.get(at.JOOQ_NAME),
                    r.get(at.CLASS_FQN), r.get(anchorSchema.TABLES_CLASS_FQN)),
                r.get(reReads),
                Boolean.TRUE.equals(r.get(nodeIdChild))));
    }

    // -------------------------------------------------------------------------------------
    // Statement five: the routine's parameters and what the store fills each with.
    // -------------------------------------------------------------------------------------

    /** One step of a fill's path: the name and whether the value there is a list. */
    private record Step(String name, boolean list) {}

    /**
     * Each admitted coordinate's routine parameters, keyed by its {@code Type.field} coordinate, in
     * declaration order: one row per parameter of the routine its producer calls, with the
     * {@code ROUTINE} fill the store states for it ({@code graphitron_occurrence_call_parameter}), the
     * path from the argument down to the fill's landing ({@code graphitron_occurrence_argument_step}),
     * and, where the landing is a node id, the slot's {@code KEY_VALUE} shape and the key column a
     * pair projects into it, and how its value reaches it, the binding's stored extraction. The fill
     * is the store's, a pair's landing or the argument named as the
     * parameter, so a path is never read off what the author wrote; a parameter with no fill has a
     * path of no names, which the build refuses before anything is emitted.
     *
     * <p>Driven from the routine's parameters rather than from the fills, so a parameter with no fill
     * is a row and not an absence. The path is a correlated {@code MULTISET} on the landing's own key.
     * A node id at a routine parameter lands on one node table's key column or nowhere, so the shape
     * and the projection are at most one row per parameter, joined at the slot and the bound. A
     * generated scalar's component is the binding's extraction's, and what the call decodes that
     * scalar as is
     * {@link GeneratedScalarQuery#decodeAt} on the landing, a correlated {@code MULTISET} in the
     * same row, so a graph with generated scalars costs this statement rows, not statements.
     */
    private static Map<String, List<Argument>> arguments(StoreHandle store, List<Occurrence> admitted) {
        var pr = GRAPHITRON_OCCURRENCE_PRODUCER_ROUTINE;
        var p = SQL_ROUTINE_PARAMETER;
        var c = GRAPHITRON_OCCURRENCE_CALL_PARAMETER;
        var st = GRAPHITRON_OCCURRENCE_ARGUMENT_STEP;
        var sp = GRAPHITRON_OCCURRENCE_NODE_ID_SLOT_PARAMETER;
        var sh = GRAPHITRON_OCCURRENCE_NODE_ID_SHAPE;
        var pj = GRAPHITRON_OCCURRENCE_NODE_ID_SLOT_PROJECTION;
        var kp = GRAPHITRON_OCCURRENCE_NODE_ID;
        var col = SQL_COLUMN;
        var b = GRAPHITRON_OCCURRENCE_BINDING_PARAMETER;
        var e = GRAPHITRON_OCCURRENCE_BINDING_EXTRACTION;
        var coordinates = admitted.stream().map(o -> o.typeName() + "." + o.fieldName()).toList();
        var scalar = GeneratedScalarQuery.decodeAt(c.GRAPH_NAME, c.LANDING_COORDINATE);
        var path = multiset(select(st.NAME, st.IS_LIST)
                .from(st)
                .where(st.GRAPH_NAME.eq(c.GRAPH_NAME), st.COORDINATE.eq(c.LANDING_COORDINATE))
                .orderBy(st.DEPTH))
            .convertFrom(r -> r.map(Records.mapping(Step::new)));
        var byKey = new LinkedHashMap<String, List<Argument>>();
        store.dsl()
            .select(pr.COORDINATE, p.POSITION, p.JOOQ_NAME, p.BINDING_TYPE, path, sp.COORDINATE,
                sh.DECODE_SHAPE, sh.BOUND, col.JOOQ_NAME,
                when(e.EXTRACTION.eq("SCALAR_COMPONENT"), e.COMPONENT), scalar, e.EXTRACTION)
            .from(pr)
            .join(p).on(p.SOURCE_NAME.eq(pr.ROUTINE_SOURCE_NAME), p.TABLE_SCHEMA.eq(pr.TABLE_SCHEMA),
                p.ROUTINE_NAME.eq(pr.ROUTINE_NAME))
            .leftJoin(c).on(c.GRAPH_NAME.eq(pr.GRAPH_NAME), c.COORDINATE.eq(pr.COORDINATE),
                c.CALL_KIND.eq("ROUTINE"), c.POSITION.eq(p.POSITION), c.FILL.eq("ARGUMENT"))
            // How the value reaches the parameter, as the store states it for the binding.
            .leftJoin(b).on(b.GRAPH_NAME.eq(c.GRAPH_NAME), b.CALL_COORDINATE.eq(c.COORDINATE),
                b.CALL.eq(c.CALL), b.POSITION.eq(c.POSITION))
            .leftJoin(e).on(e.GRAPH_NAME.eq(b.GRAPH_NAME), e.COORDINATE.eq(b.COORDINATE), e.BOUND.eq(b.BOUND))
            .leftJoin(sp).on(sp.GRAPH_NAME.eq(c.GRAPH_NAME), sp.CALL_COORDINATE.eq(c.COORDINATE),
                sp.CALL.eq(c.CALL), sp.POSITION.eq(c.POSITION))
            .leftJoin(sh).on(sh.GRAPH_NAME.eq(sp.GRAPH_NAME), sh.COORDINATE.eq(sp.COORDINATE),
                sh.SLOT.eq(sp.SLOT), sh.DECODE_SHAPE.eq("KEY_VALUE"))
            .leftJoin(pj).on(pj.GRAPH_NAME.eq(sp.GRAPH_NAME), pj.CALL_COORDINATE.eq(sp.CALL_COORDINATE),
                pj.CALL.eq(sp.CALL), pj.POSITION.eq(sp.POSITION), pj.BOUND.eq(sh.BOUND))
            .leftJoin(kp).on(kp.GRAPH_NAME.eq(pj.GRAPH_NAME), kp.COORDINATE.eq(pj.COORDINATE),
                kp.BOUND.eq(pj.BOUND), kp.POSITION.eq(pj.KEY_POSITION))
            .leftJoin(col).on(col.SOURCE_NAME.eq(kp.TABLE_SOURCE_NAME), col.TABLE_SCHEMA.eq(kp.TABLE_SCHEMA),
                col.TABLE_NAME.eq(kp.TABLE_NAME), col.COLUMN_NAME.eq(kp.COLUMN_NAME))
            .where(pr.GRAPH_NAME.eq(store.graphName()), pr.COORDINATE.in(coordinates))
            .orderBy(pr.COORDINATE, p.POSITION)
            .forEach(r -> {
                var arguments = byKey.computeIfAbsent(r.value1(), key -> new ArrayList<>());
                if (arguments.size() != r.value2()) {
                    throw new IllegalStateException("Graphitron generator bug (routine-write arguments): "
                        + r.value1() + " reads parameter " + r.value3() + " at position " + r.value2()
                        + " after " + arguments.size() + " others; a parameter's node id lands at most once");
                }
                var steps = r.value5();
                var lists = new HashSet<Integer>();
                for (int i = 0; i < steps.size(); i++) {
                    if (steps.get(i).list()) {
                        lists.add(i);
                    }
                }
                Optional<KeyValue> decode = r.value7() == null || r.value9() == null ? Optional.empty()
                    : Optional.of(new KeyValue(r.value6(), r.value8(), r.value9()));
                Optional<String> named = Optional.ofNullable(r.value10());
                arguments.add(new Argument(r.value3(), r.value4(), steps.stream().map(Step::name).toList(),
                    lists, "NODE_ID_RECORD".equals(r.value12()), "LIST_TO_ARRAY".equals(r.value12()), decode, named,
                    named.flatMap(n -> r.value11())));
            });
        return byKey;
    }

    /**
     * A child occurrence filled with keys, as read: its coordinate, which the pairing statement
     * is keyed by, its field, the table its fill names, its list-ness, whether it is a non-null
     * single, and whether the key is matched by name out of the routine's result.
     */
    private record RawReRead(String coordinate, String fieldName, Table table, boolean listReturn,
                             boolean nonNullSingle, boolean keyMatched) {}

    /**
     * The occurrences directly under the field that are filled with columns: each re-reads
     * the table the fill names, by the keys the write captured. The key is matched out of the
     * function's own table, the routine the producer calls.
     */
    private static Field<List<RawReRead>> reReads(Field<String> graph, Field<String> occurrence,
                                                  GraphitronOccurrenceProducerRoutine pr) {
        var co = GRAPHITRON_OCCURRENCE.as("re_reading");
        var kr = GRAPHITRON_OCCURRENCE_PRODUCER_LIFT;
        var cf = GRAPHITRON_FIELD.as("re_reading_field");
        var kt = SQL_TABLE.as("re_read_table");
        var ks = SQL_SCHEMA.as("re_read_schema");
        var nm = SQL_NAME_MATCHED_KEY_COLUMN;
        return multiset(
            select(co.COORDINATE, co.NAME, kt.TABLE_NAME, kt.JOOQ_NAME, kt.CLASS_FQN,
                ks.TABLES_CLASS_FQN, cf.IS_LIST,
                field(cf.IS_LIST.isFalse().and(cf.NON_NULL.isTrue())),
                field(exists(selectOne().from(nm).where(nm.SOURCE_NAME.eq(pr.ROUTINE_SOURCE_NAME),
                    nm.TABLE_SCHEMA.eq(pr.TABLE_SCHEMA), nm.TABLE_NAME.eq(pr.ROUTINE_NAME),
                    nm.TO_SOURCE_NAME.eq(kr.KEY_SOURCE_NAME), nm.TO_SCHEMA.eq(kr.KEY_SCHEMA),
                    nm.TO_TABLE.eq(kr.KEY_TABLE)))))
                .from(co)
                .join(kr).on(kr.GRAPH_NAME.eq(co.GRAPH_NAME), kr.COORDINATE.eq(co.COORDINATE),
                    kr.READ.eq("COLUMNS"))
                .join(cf).on(cf.GRAPH_NAME.eq(co.GRAPH_NAME), cf.COORDINATE.eq(co.ELEMENT_COORDINATE))
                .join(kt).on(kt.SOURCE_NAME.eq(kr.KEY_SOURCE_NAME),
                    kt.TABLE_SCHEMA.eq(kr.KEY_SCHEMA), kt.TABLE_NAME.eq(kr.KEY_TABLE))
                .join(ks).on(ks.SOURCE_NAME.eq(kt.SOURCE_NAME), ks.TABLE_SCHEMA.eq(kt.TABLE_SCHEMA))
                .where(co.GRAPH_NAME.eq(graph), co.PARENT_COORDINATE.eq(occurrence),
                    co.KIND.eq("FIELD"))
                .orderBy(co.COORDINATE))
            .convertFrom(r -> r.map(row -> new RawReRead(row.value1(), row.value2(),
                new Table(row.value3(), row.value4(), row.value5(), row.value6()),
                Boolean.TRUE.equals(row.value7()), Boolean.TRUE.equals(row.value8()),
                Boolean.TRUE.equals(row.value9()))));
    }

    // -------------------------------------------------------------------------------------
    // Statement two: a records result's captured pairing.
    // -------------------------------------------------------------------------------------

    /**
     * Each admitted records result's captured key pairing, keyed by its {@code Type.field}
     * coordinate: the column pairs its join path's first step states, the routine result's column
     * beside the anchor's primary-key column its name matched, in the step's order.
     */
    private static Map<String, List<Pair>> anchorPairs(StoreHandle store, List<Occurrence> admitted) {
        var sc = GRAPHITRON_JOIN_PATH_STEP_COLUMN;
        var src = SQL_COLUMN.as("source_column");
        var tgt = SQL_COLUMN.as("target_column");
        var needs = admitted.stream()
            .map(o -> row(o.typeName() + "." + o.fieldName(), o.returnTypeName()))
            .toList();
        var byKey = new LinkedHashMap<String, List<Pair>>();
        store.dsl()
            .select(sc.COORDINATE,
                src.COLUMN_NAME, src.JOOQ_NAME, src.BINDING_TYPE,
                tgt.COLUMN_NAME, tgt.JOOQ_NAME, tgt.BINDING_TYPE)
            .from(sc)
            .join(src).on(src.SOURCE_NAME.eq(sc.FROM_SOURCE_NAME),
                src.TABLE_SCHEMA.eq(sc.FROM_SCHEMA), src.TABLE_NAME.eq(sc.FROM_TABLE),
                src.COLUMN_NAME.eq(sc.FROM_COLUMN))
            .join(tgt).on(tgt.SOURCE_NAME.eq(sc.TO_SOURCE_NAME),
                tgt.TABLE_SCHEMA.eq(sc.TO_SCHEMA), tgt.TABLE_NAME.eq(sc.TO_TABLE),
                tgt.COLUMN_NAME.eq(sc.TO_COLUMN))
            .where(sc.GRAPH_NAME.eq(store.graphName()), sc.POSITION.eq(0),
                row(sc.COORDINATE, sc.TARGET_TYPE).in(needs))
            .orderBy(sc.COORDINATE, sc.ORDINAL)
            .forEach(r -> byKey.computeIfAbsent(r.value1(), key -> new ArrayList<>())
                .add(pair(r.value2(), r.value3(), r.value4(), r.value5(), r.value6(), r.value7())));
        return byKey;
    }

    // -------------------------------------------------------------------------------------
    // Statement three: a plural identifying write's stated order.
    // -------------------------------------------------------------------------------------

    /**
     * Each admitted records result that is a plural identifying write, keyed by its
     * {@code Type.field} coordinate: the keys of the default order stated on its routine's result, in
     * order. A write is plural identifying where {@code graphitron_occurrence_input_order} holds a
     * {@code WRITE_SHAPE} row for it; its default order's keys are read where
     * {@code graphitron_occurrence_sort_key_column} resolves them on the routine's function table. A
     * write with no such row, or one stating no order there, has no entry, and its capture keeps the
     * order the routine delivered.
     */
    private static Map<String, List<OrderKey>> statedOrders(StoreHandle store, List<Occurrence> admitted) {
        var io = GRAPHITRON_OCCURRENCE_INPUT_ORDER;
        var pr = GRAPHITRON_OCCURRENCE_PRODUCER_ROUTINE;
        var r = GRAPHITRON_OCCURRENCE_ORDER;
        var k = GRAPHITRON_OCCURRENCE_SORT_KEY;
        var kc = GRAPHITRON_OCCURRENCE_SORT_KEY_COLUMN;
        var c = SQL_COLUMN;
        var coordinates = admitted.stream().map(o -> o.typeName() + "." + o.fieldName()).toList();
        var byKey = new LinkedHashMap<String, List<OrderKey>>();
        store.dsl()
            .select(io.COORDINATE, c.COLUMN_NAME, c.JOOQ_NAME, c.BINDING_TYPE, k.DIRECTION)
            .from(io)
            .join(pr).on(pr.GRAPH_NAME.eq(io.GRAPH_NAME), pr.COORDINATE.eq(io.COORDINATE))
            .join(r).on(r.GRAPH_NAME.eq(io.GRAPH_NAME), r.COORDINATE.eq(io.COORDINATE), r.KIND.eq("DEFAULT"))
            .join(k).on(k.GRAPH_NAME.eq(r.GRAPH_NAME), k.COORDINATE.eq(r.COORDINATE), k.BOUND.eq(r.BOUND),
                k.CHOICE.eq(r.CHOICE))
            .join(kc).on(kc.GRAPH_NAME.eq(k.GRAPH_NAME), kc.COORDINATE.eq(k.COORDINATE), kc.BOUND.eq(k.BOUND),
                kc.CHOICE.eq(k.CHOICE), kc.POSITION.eq(k.POSITION),
                kc.TABLE_SOURCE_NAME.eq(pr.ROUTINE_SOURCE_NAME), kc.TABLE_SCHEMA.eq(pr.TABLE_SCHEMA),
                kc.TABLE_NAME.eq(pr.ROUTINE_NAME))
            .join(c).on(c.SOURCE_NAME.eq(kc.TABLE_SOURCE_NAME), c.TABLE_SCHEMA.eq(kc.TABLE_SCHEMA),
                c.TABLE_NAME.eq(kc.TABLE_NAME), c.COLUMN_NAME.eq(kc.COLUMN_NAME))
            .where(io.GRAPH_NAME.eq(store.graphName()), io.DECLARED_BY.eq("WRITE_SHAPE"),
                io.COORDINATE.in(coordinates))
            .orderBy(io.COORDINATE, k.POSITION)
            .forEach(row -> byKey.computeIfAbsent(row.value1(), key -> new ArrayList<>())
                .add(new OrderKey(new Column(row.value2(), row.value3(), row.value4()),
                    "DESC".equals(row.value5()))));
        return byKey;
    }

    // -------------------------------------------------------------------------------------
    // Statement four: an object result's captured pairing.
    // -------------------------------------------------------------------------------------

    /**
     * Each admitted re-reading child's captured key pairing, keyed by the child's coordinate: the
     * routine result's column of each name beside the primary-key column of that name on the table
     * the child's fill names, in key order. The relation holds only total pairings, since a
     * capture missing one key column filters on a partial key and re-reads a row the write did not
     * commit. The routine result is the function's own table, the routine the parent's producer
     * calls.
     */
    private static Map<String, List<Pair>> reReadPairs(StoreHandle store, List<Occurrence> admitted) {
        var co = GRAPHITRON_OCCURRENCE.as("re_reading");
        var kr = GRAPHITRON_OCCURRENCE_PRODUCER_LIFT;
        var pr = GRAPHITRON_OCCURRENCE_PRODUCER_ROUTINE;
        var nm = SQL_NAME_MATCHED_KEY_COLUMN;
        var src = SQL_COLUMN.as("source_column");
        var tgt = SQL_COLUMN.as("target_column");
        var children = admitted.stream()
            .flatMap(o -> o.reReads().stream().map(RawReRead::coordinate))
            .toList();
        var byKey = new LinkedHashMap<String, List<Pair>>();
        store.dsl()
            .select(kr.COORDINATE,
                src.COLUMN_NAME, src.JOOQ_NAME, src.BINDING_TYPE,
                tgt.COLUMN_NAME, tgt.JOOQ_NAME, tgt.BINDING_TYPE)
            .from(kr)
            .join(co).on(co.GRAPH_NAME.eq(kr.GRAPH_NAME), co.COORDINATE.eq(kr.COORDINATE))
            .join(pr).on(pr.GRAPH_NAME.eq(co.GRAPH_NAME), pr.COORDINATE.eq(co.PARENT_COORDINATE))
            .join(nm).on(nm.SOURCE_NAME.eq(pr.ROUTINE_SOURCE_NAME),
                nm.TABLE_SCHEMA.eq(pr.TABLE_SCHEMA), nm.TABLE_NAME.eq(pr.ROUTINE_NAME),
                nm.TO_SOURCE_NAME.eq(kr.KEY_SOURCE_NAME), nm.TO_SCHEMA.eq(kr.KEY_SCHEMA),
                nm.TO_TABLE.eq(kr.KEY_TABLE))
            .join(src).on(src.SOURCE_NAME.eq(pr.ROUTINE_SOURCE_NAME),
                src.TABLE_SCHEMA.eq(pr.TABLE_SCHEMA), src.TABLE_NAME.eq(pr.ROUTINE_NAME),
                src.COLUMN_NAME.eq(nm.COLUMN_NAME))
            .join(tgt).on(tgt.SOURCE_NAME.eq(kr.KEY_SOURCE_NAME),
                tgt.TABLE_SCHEMA.eq(kr.KEY_SCHEMA), tgt.TABLE_NAME.eq(kr.KEY_TABLE),
                tgt.COLUMN_NAME.eq(nm.TO_COLUMN))
            .where(kr.GRAPH_NAME.eq(store.graphName()), kr.READ.eq("COLUMNS"),
                kr.COORDINATE.in(children))
            .orderBy(kr.COORDINATE, nm.POSITION)
            .forEach(r -> byKey
                .computeIfAbsent(r.value1(), key -> new ArrayList<>())
                .add(pair(r.value2(), r.value3(), r.value4(), r.value5(), r.value6(), r.value7())));
        return byKey;
    }

    private static Pair pair(String sourceSql, String sourceJava, String sourceType,
                             String targetSql, String targetJava, String targetType) {
        return new Pair(new Column(sourceSql, sourceJava, sourceType),
            new Column(targetSql, targetJava, targetType));
    }
}
