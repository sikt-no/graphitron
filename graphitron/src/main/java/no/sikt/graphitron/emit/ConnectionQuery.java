package no.sikt.graphitron.emit;

import no.sikt.graphitron.model.read.StoreHandle;
import org.jooq.Records;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_ARGUMENT_STEP;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_CONNECTION;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_CONNECTION_PART;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_NODE_ID;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_PRODUCER;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_PRODUCER_EMPTY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_PRODUCER_RESULT;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_TENANT_SLOT;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_TENANT_SLOT_FILTERING;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_TENANT_COLUMN;
import static no.sikt.graphitron.model.Tables.SQL_COLUMN;
import static no.sikt.graphitron.model.Tables.SQL_SCHEMA;
import static no.sikt.graphitron.model.Tables.SQL_TABLE;
import static no.sikt.graphitron.model.Tables.STORE_GRAPH_TENANT_COLUMN;
import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.min;
import static org.jooq.impl.DSL.multiset;
import static org.jooq.impl.DSL.notExists;
import static org.jooq.impl.DSL.select;
import static org.jooq.impl.DSL.selectOne;

/**
 * The connection each producer of a database-per-tenant graph runs on, as the store states it: its outcome, its tenant part's answer to a call naming no tenant, the values the tenant is read
 * from and how each is read, and the producer's empty answer; one statement, the slots nested on their
 * call.
 */
public final class ConnectionQuery {

    private ConnectionQuery() {}

    /** Which connection a producer runs on, as {@code graphitron_occurrence_connection} spells it. */
    public enum Outcome { ROUTED, INHERIT, UNSCOPED }

    /** What a routed call naming no tenant gets, as its tenant part states it. */
    public enum WhenEmpty { UNREACHABLE, EMPTY, REFUSE }

    /** The collection a producer answers empty, as {@code graphitron_occurrence_producer_empty} spells it. */
    public enum EmptyKind { LIST, SET, RESULT }

    /**
     * The node a slot's value is an id of, by its type name: the generated encoder's strict decode of
     * it is named for that type.
     */
    public record Node(String typeName) {
        public Node {
            Objects.requireNonNull(typeName, "typeName");
        }
    }

    /**
     * One value the tenant is read from: the names from its argument down, and where it is a node id,
     * the node and the tenant column's position in its key.
     */
    public record Slot(List<String> names, Optional<Integer> keyPosition, Optional<Node> node) {
        public Slot {
            names = List.copyOf(names);
            Objects.requireNonNull(keyPosition, "keyPosition");
            Objects.requireNonNull(node, "node");
        }
    }

    /** The empty answer a producer returns without a connection: its kind, and a result's table. */
    public record Empty(EmptyKind kind, Optional<String> tablesClass, Optional<String> tableField) {}

    /** One call's connection. */
    public record Row(String coordinate, Outcome outcome, Optional<WhenEmpty> whenEmpty, List<Slot> slots,
                      Optional<Empty> empty) {
        public Row {
            Objects.requireNonNull(coordinate, "coordinate");
            Objects.requireNonNull(outcome, "outcome");
            slots = List.copyOf(slots);
        }
    }

    private record RawSlot(List<String> names, Integer keyPosition, String typeName) {}

    /**
     * That the walk's tenancy agrees with the store's: the walk routes on its tenant scopes,
     * {@code walkTenanted}, and the store states each producer's connection off its tenant column, so a
     * build where one has a tenant column and the other none would read one connection in the store's
     * emitters and another in the walk's. Checked once where the connection key's shape is decided; gone
     * with the walk's tenant scopes.
     */
    public static void requireTenancyAgrees(StoreHandle store, boolean walkTenanted) {
        var column = STORE_GRAPH_TENANT_COLUMN;
        var stored = store.dsl().fetchExists(column, column.GRAPH_NAME.eq(store.graphName()));
        if (walkTenanted != stored) {
            throw new IllegalStateException("Graphitron generator bug (tenancy): the walk "
                + (walkTenanted ? "routes on a tenant column" : "has no tenant column") + " and the store "
                + (stored ? "states one" : "states none") + " for graph '" + store.graphName() + "'");
        }
    }

    /**
     * The connections the graph's {@code @service} producers run on, by the schema coordinate the walk's
     * tenant fold keys its edges by, one outcome per occurrence: the store owns those fields whole, and
     * the fold reads what each hands its subtree off this.
     */
    public static Map<String, Set<Outcome>> serviceOutcomes(StoreHandle store) {
        var c = GRAPHITRON_OCCURRENCE_CONNECTION;
        var p = GRAPHITRON_OCCURRENCE_PRODUCER;
        var o = GRAPHITRON_OCCURRENCE;
        return Map.copyOf(store.dsl().selectDistinct(o.ELEMENT_COORDINATE, c.OUTCOME)
            .from(c)
            .join(p).on(p.GRAPH_NAME.eq(c.GRAPH_NAME), p.COORDINATE.eq(c.COORDINATE))
            .join(o).on(o.GRAPH_NAME.eq(c.GRAPH_NAME), o.COORDINATE.eq(c.COORDINATE))
            .where(c.GRAPH_NAME.eq(store.graphName()), p.KIND.eq("SERVICE"))
            .fetch()
            .stream()
            .collect(Collectors.groupingBy(rec -> rec.value1(),
                Collectors.mapping(rec -> Outcome.valueOf(rec.value2()), Collectors.toUnmodifiableSet()))));
    }

    /** The tenant column's Java type, where the graph's tenant columns agree on one. */
    public static Optional<String> tenantType(StoreHandle store) {
        var tc = GRAPHITRON_TENANT_COLUMN;
        var c = SQL_COLUMN;
        var types = store.dsl().selectDistinct(c.BINDING_TYPE).from(tc)
            .join(c).on(c.SOURCE_NAME.eq(tc.SOURCE_NAME), c.TABLE_SCHEMA.eq(tc.TABLE_SCHEMA),
                c.TABLE_NAME.eq(tc.TABLE_NAME), c.COLUMN_NAME.eq(tc.COLUMN_NAME))
            .where(tc.GRAPH_NAME.eq(store.graphName()))
            .fetch(c.BINDING_TYPE);
        return types.size() == 1 ? Optional.of(types.getFirst()) : Optional.empty();
    }

    /** The graph's producers' connections, by the producer's occurrence. */
    public static Map<String, Row> read(StoreHandle store) {
        var c = GRAPHITRON_OCCURRENCE_CONNECTION;
        var p = GRAPHITRON_OCCURRENCE_CONNECTION_PART;
        var s = GRAPHITRON_OCCURRENCE_TENANT_SLOT;
        var st = GRAPHITRON_OCCURRENCE_ARGUMENT_STEP;
        var n = GRAPHITRON_OCCURRENCE_NODE_ID;
        var e = GRAPHITRON_OCCURRENCE_PRODUCER_EMPTY;
        var r = GRAPHITRON_OCCURRENCE_PRODUCER_RESULT;
        var rt = SQL_TABLE.as("rt");
        var rs = SQL_SCHEMA.as("rs");
        // A slot past a node id reads the node whose key holds the tenant column at its position.
        var nodeType = field(select(min(n.NODE_TYPE_NAME)).from(n)
            .where(n.GRAPH_NAME.eq(s.GRAPH_NAME), n.COORDINATE.eq(s.COORDINATE), n.POSITION.eq(s.KEY_POSITION)));
        var names = multiset(select(st.NAME).from(st)
                .where(st.GRAPH_NAME.eq(s.GRAPH_NAME), st.COORDINATE.eq(s.COORDINATE))
                .orderBy(st.DEPTH))
            .convertFrom(rs2 -> rs2.map(rec -> rec.value1()));
        // The slots that decide the tenant: one that only filters inside the tenant another decides
        // stays out of the fold, its predicate still filtering.
        var sf = GRAPHITRON_OCCURRENCE_TENANT_SLOT_FILTERING;
        var slots = multiset(select(names, s.KEY_POSITION, nodeType)
                .from(s)
                .where(s.GRAPH_NAME.eq(c.GRAPH_NAME), s.PRODUCER_COORDINATE.eq(c.COORDINATE),
                    notExists(selectOne().from(sf).where(sf.GRAPH_NAME.eq(s.GRAPH_NAME),
                        sf.PRODUCER_COORDINATE.eq(s.PRODUCER_COORDINATE), sf.SLOT.eq(s.SLOT))))
                .orderBy(s.SLOT))
            .convertFrom(rs2 -> rs2.map(Records.mapping(RawSlot::new)));
        return store.dsl()
            .select(c.COORDINATE, c.OUTCOME, p.WHEN_EMPTY, slots, e.EMPTY, rs.TABLES_CLASS_FQN, rt.JOOQ_NAME)
            .from(c)
            .leftJoin(p).on(p.GRAPH_NAME.eq(c.GRAPH_NAME), p.COORDINATE.eq(c.COORDINATE), p.PART.eq("TENANT"))
            .leftJoin(e).on(e.GRAPH_NAME.eq(c.GRAPH_NAME), e.COORDINATE.eq(c.COORDINATE))
            .leftJoin(r).on(r.GRAPH_NAME.eq(e.GRAPH_NAME), r.COORDINATE.eq(e.COORDINATE))
            .leftJoin(rt).on(rt.SOURCE_NAME.eq(r.TABLE_SOURCE_NAME), rt.TABLE_SCHEMA.eq(r.TABLE_SCHEMA),
                rt.TABLE_NAME.eq(r.TABLE_NAME))
            .leftJoin(rs).on(rs.SOURCE_NAME.eq(rt.SOURCE_NAME), rs.TABLE_SCHEMA.eq(rt.TABLE_SCHEMA))
            .where(c.GRAPH_NAME.eq(store.graphName()))
            .orderBy(c.COORDINATE)
            .fetch(rec -> new Row(rec.value1(), Outcome.valueOf(rec.value2()),
                Optional.ofNullable(rec.value3()).map(WhenEmpty::valueOf),
                rec.value4().stream().map(raw -> new Slot(raw.names(), Optional.ofNullable(raw.keyPosition()),
                    raw.typeName() == null || raw.keyPosition() == null ? Optional.empty()
                        : Optional.of(new Node(raw.typeName()))))
                    .toList(),
                Optional.ofNullable(rec.value5()).map(kind -> new Empty(EmptyKind.valueOf(kind),
                    Optional.ofNullable(rec.value6()), Optional.ofNullable(rec.value7())))))
            .stream()
            .collect(Collectors.toMap(Row::coordinate, row -> row, (a, b) -> a, java.util.LinkedHashMap::new));
    }
}
