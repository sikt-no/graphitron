package no.sikt.graphitron.emit;

import no.sikt.graphitron.model.read.StoreHandle;
import org.jooq.Records;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_AST_FEDERATION_KEY_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_DIRECTIVE_APPLICATION;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_KEY_FIELD_BINDING;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_MINTED_KEY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_NODE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_BINDING;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_NODE_KEYCOLUMN;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_COLUMN;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_TABLETYPE;
import static no.sikt.graphitron.model.Tables.SQL_COLUMN;
import static no.sikt.graphitron.model.Tables.SQL_SCHEMA;
import static no.sikt.graphitron.model.Tables.SQL_TABLE;
import static org.jooq.impl.DSL.coalesce;
import static org.jooq.impl.DSL.exists;
import static org.jooq.impl.DSL.inline;
import static org.jooq.impl.DSL.multiset;
import static org.jooq.impl.DSL.select;
import static org.jooq.impl.DSL.selectOne;

/**
 * The entity dispatch's query: each entity a graph resolves by selecting its row from its table, with
 * the key alternatives a representation of it may carry, in the order the dispatch tries them. One row
 * per table-bound object type that carries a {@code @key} application, authored or minted, or is a
 * resolvable node, which {@code Query.node} resolves the same way in a graph with no federation at all.
 *
 * <p>The alternatives are the type's {@code @key} applications in ordinal order, so the authored keys
 * come first and a minted one after them. A key whose fields bind columns of the type's own table
 * ({@code graphitron_key_field_binding}, kind {@code COLUMN}) is {@link Alternative.Direct}, each
 * field's column read off {@code graphitron_occurrence_column}; a key whose one field binds the node's
 * own id ({@code NODE_ID}) is {@link Alternative.NodeId}, decoded with the node's type id and key
 * columns. The node key federation synthesises is no different: it is a key whose field binds as the
 * node's {@code id} does, the node id, or the column where the {@code id} carries {@code @field}. A resolvable node none of whose keys is the node id alternative is
 * given one ahead of the rest, so {@code Query.node} resolves it, unless its {@code id} is a column
 * mapping: an {@code id} carrying {@code @field} reads that column and encodes no node id, so neither
 * {@code Query.node} nor a representation's {@code id} is decoded for it. An entity whose node id alternative
 * belongs to a node this subgraph does not resolve, a federation stub, is no entity here: the subgraph
 * owning it resolves it. A key the store refuses never reaches here, since a refused graph is not
 * generated.
 *
 * <p>Each alternative carries its {@link Identity}, the {@code @key} application's ordinal or the
 * node id alternative, which an entity has at most one of; a reader pairing a fact with an alternative
 * pairs it by identity, never by place in the list.
 *
 * <p>Rows are in store terms: table and column names as the catalog spells them, and the generated
 * classes the catalog states for the table, its class, its field in the schema's {@code Tables} class
 * and that class, which is null where the schema publishes none.
 */
public final class EntityKeyQuery {

    private EntityKeyQuery() {}

    /** Which alternative of an entity this is: its {@code @key} application's ordinal, or the node id. */
    public sealed interface Identity {
        /** An alternative a key over columns states, by its {@code @key} application's ordinal. */
        record Key(int ordinal) implements Identity {}

        /** The node id alternative, of which an entity has at most one. */
        record NodeId() implements Identity {}
    }

    /** A column of the entity's table, as the catalog spells it. */
    public record Column(String columnName, String javaName, String bindingType, String databaseType) {
        public Column {
            Objects.requireNonNull(columnName, "columnName");
        }
    }

    /** One field of a direct key: the field the representation carries and the column it matches. */
    public record KeyField(String fieldName, Column column) {}

    /** One key alternative of an entity, and whether this subgraph resolves the entity by it. */
    public sealed interface Alternative {
        Identity identity();

        boolean resolvable();

        /** A key whose fields each carry the value of one column of the entity's table. */
        record Direct(int ordinal, List<KeyField> fields, boolean resolvable) implements Alternative {
            public Direct {
                fields = List.copyOf(fields);
            }

            @Override
            public Identity identity() {
                return new Identity.Key(ordinal);
            }
        }

        /** A key whose one field is the node's own id, decoded into the node's key columns. */
        record NodeId(String typeId, List<Column> keyColumns, boolean resolvable) implements Alternative {
            public NodeId {
                keyColumns = List.copyOf(keyColumns);
            }

            @Override
            public Identity identity() {
                return new Identity.NodeId();
            }
        }
    }

    /**
     * One entity: the type, its table and the generated classes that name it, and its key alternatives
     * in the order the dispatch tries them.
     */
    public record Entity(String typeName, String tableSchema, String tableName, String tableClass,
                         String tableField, String tablesClass, List<Alternative> alternatives) {
        public Entity {
            alternatives = List.copyOf(alternatives);
        }

        /** Whether this subgraph resolves the entity by any of its keys. */
        public boolean resolvable() {
            return alternatives.stream().anyMatch(Alternative::resolvable);
        }
    }

    private record RawField(String fieldName, String kind, Column column) {}

    private record RawKey(int ordinal, boolean resolvable, List<RawField> fields) {}

    private record RawNode(String typeId, List<Column> keyColumns) {}

    /** The graph's entities, in type name order. */
    public static List<Entity> read(StoreHandle store) {
        var tt = GRAPHITRON_TABLETYPE;
        var n = GRAPHITRON_NODE;
        var nk = GRAPHITRON_NODE_KEYCOLUMN;
        var a = GRAPHITRON_DIRECTIVE_APPLICATION;
        var ke = GRAPHITRON_AST_FEDERATION_KEY_ENTRY;
        var mk = GRAPHITRON_MINTED_KEY;
        var k = GRAPHITRON_KEY_FIELD_BINDING;
        var oc = GRAPHITRON_OCCURRENCE_COLUMN;
        var c = SQL_COLUMN;
        var nc = SQL_COLUMN.as("nc");
        var t = SQL_TABLE;
        var sc = SQL_SCHEMA;
        var b = GRAPHITRON_OCCURRENCE_BINDING;
        // A node this subgraph resolves by its id: resolvable, and its id binding the node id at its
        // table, which an id carrying @field does not, reading the column instead.
        var servedById = n.RESOLVABLE.isTrue().andExists(selectOne().from(b)
            .where(b.GRAPH_NAME.eq(n.GRAPH_NAME), b.COORDINATE.eq(n.TYPE_NAME.concat(inline(".id"))),
                b.BOUND.eq(tt.TABLE_SCHEMA.concat(inline(".")).concat(tt.TABLE_NAME)), b.KIND.eq("NODE_ID")));

        // A key's fields in position order, each COLUMN field with the column it reads.
        var fields = multiset(
            select(k.FIELD_NAME, k.KIND, oc.COLUMN_NAME, c.JOOQ_NAME, c.BINDING_TYPE, c.DATABASE_TYPE)
                .from(k)
                .leftJoin(oc).on(oc.GRAPH_NAME.eq(k.GRAPH_NAME), oc.COORDINATE.eq(k.COORDINATE),
                    oc.BOUND.eq(k.BOUND))
                .leftJoin(c).on(c.SOURCE_NAME.eq(oc.TABLE_SOURCE_NAME), c.TABLE_SCHEMA.eq(oc.TABLE_SCHEMA),
                    c.TABLE_NAME.eq(oc.TABLE_NAME), c.COLUMN_NAME.eq(oc.COLUMN_NAME))
                .where(k.GRAPH_NAME.eq(a.GRAPH_NAME), k.TYPE_NAME.eq(a.COORDINATE),
                    k.DIRECTIVE_NAME.eq(a.DIRECTIVE_NAME), k.ORDINAL.eq(a.ORDINAL))
                .orderBy(k.POSITION))
            .convertFrom(r -> r.map(f -> new RawField(f.value1(), f.value2(),
                f.value3() == null ? null : new Column(f.value3(), f.value4(), f.value5(), f.value6()))));
        // A key's resolvable: an authored key's as written, true where the author left it out; a
        // minted key's as the rule that minted it states.
        var keys = multiset(
            select(a.ORDINAL, coalesce(ke.RESOLVABLE, mk.RESOLVABLE, inline(true)), fields)
                .from(a)
                .leftJoin(ke).on(ke.GRAPH_NAME.eq(a.GRAPH_NAME), ke.SOURCE_NAME.eq(a.SOURCE_NAME),
                    ke.SOURCE_LINE.eq(a.SOURCE_LINE), ke.SOURCE_COLUMN.eq(a.SOURCE_COLUMN))
                .leftJoin(mk).on(mk.GRAPH_NAME.eq(a.GRAPH_NAME), mk.TYPE_NAME.eq(a.COORDINATE),
                    mk.ORDINAL.eq(a.ORDINAL), a.ORIGIN.eq("MINTED"))
                .where(a.GRAPH_NAME.eq(tt.GRAPH_NAME), a.COORDINATE.eq(tt.TYPE_NAME),
                    a.DIRECTIVE_NAME.eq("key"))
                .orderBy(a.ORDINAL))
            .convertFrom(r -> r.map(Records.mapping(RawKey::new)));
        // The type as a node this subgraph resolves, with its type id and key columns in key order.
        var node = multiset(
            select(n.TYPE_ID, multiset(
                    select(nk.COLUMN_NAME, nc.JOOQ_NAME, nc.BINDING_TYPE, nc.DATABASE_TYPE)
                        .from(nk)
                        .join(nc).on(nc.SOURCE_NAME.eq(nk.TABLE_SOURCE_NAME), nc.TABLE_SCHEMA.eq(nk.TABLE_SCHEMA),
                            nc.TABLE_NAME.eq(nk.TABLE_NAME), nc.COLUMN_NAME.eq(nk.COLUMN_NAME))
                        .where(nk.GRAPH_NAME.eq(n.GRAPH_NAME), nk.TYPE_NAME.eq(n.TYPE_NAME))
                        .orderBy(nk.POSITION))
                    .convertFrom(r -> r.map(Records.mapping(Column::new))))
                .from(n)
                .where(n.GRAPH_NAME.eq(tt.GRAPH_NAME), n.TYPE_NAME.eq(tt.TYPE_NAME), servedById))
            .convertFrom(r -> r.map(Records.mapping(RawNode::new)));

        var out = new ArrayList<Entity>();
        store.dsl()
            .select(tt.TYPE_NAME, tt.TABLE_SCHEMA, tt.TABLE_NAME, t.CLASS_FQN, t.JOOQ_NAME, sc.TABLES_CLASS_FQN,
                node, keys)
            .from(tt)
            .join(t).on(t.SOURCE_NAME.eq(tt.TABLE_SOURCE_NAME), t.TABLE_SCHEMA.eq(tt.TABLE_SCHEMA),
                t.TABLE_NAME.eq(tt.TABLE_NAME))
            .join(sc).on(sc.SOURCE_NAME.eq(t.SOURCE_NAME), sc.TABLE_SCHEMA.eq(t.TABLE_SCHEMA))
            .where(tt.GRAPH_NAME.eq(store.graphName()), tt.NAMED_TYPE_KIND.eq("OBJECT"))
            .and(exists(selectOne().from(a).where(a.GRAPH_NAME.eq(tt.GRAPH_NAME),
                    a.COORDINATE.eq(tt.TYPE_NAME), a.DIRECTIVE_NAME.eq("key")))
                .or(exists(selectOne().from(n).where(n.GRAPH_NAME.eq(tt.GRAPH_NAME),
                    n.TYPE_NAME.eq(tt.TYPE_NAME), servedById))))
            .orderBy(tt.TYPE_NAME)
            .forEach(r -> entity(r.value1(), r.value2(), r.value3(), r.value4(), r.value5(), r.value6(),
                r.value7().isEmpty() ? null : r.value7().getFirst(), r.value8()).ifPresent(out::add));
        return List.copyOf(out);
    }

    /**
     * The entity a type's keys and nodehood make, or none where a node id alternative names a node this
     * subgraph does not resolve.
     */
    private static java.util.Optional<Entity> entity(String typeName, String schema, String table,
                                                     String tableClass, String tableField, String tablesClass,
                                                     RawNode node, List<RawKey> keys) {
        var alternatives = new ArrayList<Alternative>();
        for (var key : keys) {
            if (key.fields().isEmpty()) {
                continue;
            }
            boolean nodeId = key.fields().size() == 1 && "NODE_ID".equals(key.fields().getFirst().kind());
            if (nodeId) {
                if (node == null) {
                    return java.util.Optional.empty();
                }
                alternatives.add(new Alternative.NodeId(node.typeId(), node.keyColumns(), key.resolvable()));
            } else {
                alternatives.add(new Alternative.Direct(key.ordinal(), key.fields().stream()
                    .map(f -> new KeyField(f.fieldName(), f.column()))
                    .toList(), key.resolvable()));
            }
        }
        if (node != null && alternatives.stream().noneMatch(Alternative.NodeId.class::isInstance)) {
            alternatives.addFirst(new Alternative.NodeId(node.typeId(), node.keyColumns(), true));
        }
        return java.util.Optional.of(new Entity(typeName, schema, table, tableClass, tableField, tablesClass,
            alternatives));
    }
}
