package no.sikt.graphitron.emit;

import no.sikt.graphitron.model.read.StoreHandle;
import org.jooq.Records;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_ACCEPT_KEY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_ACCEPT_VOUCHED_FOR;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_AST_ACCEPT_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_AST_FEDERATION_KEY_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_FIELD;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_DIRECTIVE_APPLICATION;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_KEY_FIELD_BINDING;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_MINTED_KEY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_NODE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_NODE_SERVED_BY_ID;
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
 * node this subgraph serves by its id ({@code graphitron_node_served_by_id}), which {@code Query.node}
 * resolves the same way in a graph with no federation at all.
 *
 * <p>The alternatives are the type's {@code @key} applications in ordinal order, so the authored keys
 * come first and a minted one after them. A key whose fields bind columns of the type's own table
 * ({@code graphitron_key_field_binding}, kind {@code COLUMN}) is {@link Alternative.Direct}, each
 * field's column read off {@code graphitron_occurrence_column}; a key whose one field binds the node's
 * own id ({@code NODE_ID}) is {@link Alternative.NodeId}, decoded with the node's type id and key
 * columns; a key minted for an {@code @accept}, whose one field binds the voucher ({@code VOUCHER}), is
 * {@link Alternative.Voucher}, verified and decoded onto the authored key {@code graphitron_accept_key}
 * names for it. The node key federation synthesises is no different: it is a key whose field binds as the
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

        /**
         * A key minted for an {@code @accept}: its one field is the voucher, verified rather than matched,
         * whose lookup part decodes onto the authored key {@code decodesOnto} names, paired by identity,
         * and whose vouched-for values key the connection the entity is read on. The vouched-for values
         * filter nothing: they are the access decision, and the database's policies test them. It selects
         * by the authored key's columns, so it carries no column of its own for the lookup part.
         *
         * @param ordinal      the minted key's {@code @key} application ordinal, its identity
         * @param voucherField the voucher field's name, the minted key's one field
         * @param scope        the scope the {@code @accept} accepts
         * @param decodesOnto  the authored key the lookup part decodes onto
         * @param vouchedFor   the vouched-for fields, each with the column it reads, in name order
         * @param resolvable   whether this subgraph resolves the entity by the voucher key
         */
        record Voucher(int ordinal, String voucherField, String scope, Identity.Key decodesOnto,
                       List<KeyField> vouchedFor, boolean resolvable) implements Alternative {
            public Voucher {
                Objects.requireNonNull(voucherField, "voucherField");
                Objects.requireNonNull(scope, "scope");
                Objects.requireNonNull(decodesOnto, "decodesOnto");
                vouchedFor = List.copyOf(vouchedFor);
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

        /** The alternative {@code identity} names, which a voucher alternative decodes onto. */
        public Alternative alternative(Identity identity) {
            return alternatives.stream().filter(a -> a.identity().equals(identity)).findFirst()
                .orElseThrow(() -> new IllegalStateException("Entity '" + typeName + "' has no key alternative "
                    + identity));
        }

        /** The declaration index of the alternative {@code identity} names. */
        public int indexOf(Identity identity) {
            for (int i = 0; i < alternatives.size(); i++) {
                if (alternatives.get(i).identity().equals(identity)) {
                    return i;
                }
            }
            throw new IllegalStateException("Entity '" + typeName + "' has no key alternative " + identity);
        }
    }

    private record RawField(String fieldName, String kind, Column column) {}

    private record RawKey(int ordinal, boolean resolvable, List<RawField> fields) {}

    private record RawNode(String typeId, List<Column> keyColumns) {}

    private record RawAccept(int ordinal, String voucherField, String scope, int decodesOnto,
                             List<KeyField> vouchedFor) {}

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
        var s = GRAPHITRON_NODE_SERVED_BY_ID;

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
        // The type's @accept: the minted key's ordinal and field, the scope, the authored key its vouchers
        // decode onto, and its vouched-for fields with the columns they read, in name order.
        var av = GRAPHITRON_ACCEPT_VOUCHED_FOR;
        var vf = GRAPHITRON_FIELD;
        var voc = GRAPHITRON_OCCURRENCE_COLUMN.as("voc");
        var vc = SQL_COLUMN.as("vc");
        var amk = GRAPHITRON_MINTED_KEY.as("amk");
        var ak = GRAPHITRON_ACCEPT_KEY;
        var ae = GRAPHITRON_AST_ACCEPT_ENTRY;
        var vouchedFor = multiset(
            select(av.FIELD_NAME, voc.COLUMN_NAME, vc.JOOQ_NAME, vc.BINDING_TYPE, vc.DATABASE_TYPE)
                .from(av)
                .join(vf).on(vf.GRAPH_NAME.eq(av.GRAPH_NAME), vf.TYPE_NAME.eq(av.TYPE_NAME),
                    vf.FIELD_NAME.eq(av.FIELD_NAME))
                .join(voc).on(voc.GRAPH_NAME.eq(vf.GRAPH_NAME), voc.COORDINATE.eq(vf.COORDINATE),
                    voc.BOUND.eq(tt.TABLE_SCHEMA.concat(inline(".")).concat(tt.TABLE_NAME)))
                .join(vc).on(vc.SOURCE_NAME.eq(voc.TABLE_SOURCE_NAME), vc.TABLE_SCHEMA.eq(voc.TABLE_SCHEMA),
                    vc.TABLE_NAME.eq(voc.TABLE_NAME), vc.COLUMN_NAME.eq(voc.COLUMN_NAME))
                .where(av.GRAPH_NAME.eq(ak.GRAPH_NAME), av.SOURCE_NAME.eq(ak.SOURCE_NAME),
                    av.SOURCE_LINE.eq(ak.SOURCE_LINE), av.SOURCE_COLUMN.eq(ak.SOURCE_COLUMN))
                .orderBy(av.FIELD_NAME))
            .convertFrom(r -> r.map(f -> new KeyField(f.value1(),
                new Column(f.value2(), f.value3(), f.value4(), f.value5()))));
        var accept = multiset(
            select(amk.ORDINAL, amk.FIELDS, ae.SCOPE, ak.ORDINAL, vouchedFor)
                .from(amk)
                .join(ak).on(ak.GRAPH_NAME.eq(amk.GRAPH_NAME), ak.TYPE_NAME.eq(amk.TYPE_NAME))
                .join(ae).on(ae.GRAPH_NAME.eq(ak.GRAPH_NAME), ae.SOURCE_NAME.eq(ak.SOURCE_NAME),
                    ae.SOURCE_LINE.eq(ak.SOURCE_LINE), ae.SOURCE_COLUMN.eq(ak.SOURCE_COLUMN))
                .where(amk.GRAPH_NAME.eq(tt.GRAPH_NAME), amk.TYPE_NAME.eq(tt.TYPE_NAME), amk.ROLE.eq("ACCEPT")))
            .convertFrom(r -> r.map(Records.mapping(RawAccept::new)));
        // The type as a node this subgraph serves by its id, with its type id and key columns in key
        // order.
        var node = multiset(
            select(n.TYPE_ID, multiset(
                    select(nk.COLUMN_NAME, nc.JOOQ_NAME, nc.BINDING_TYPE, nc.DATABASE_TYPE)
                        .from(nk)
                        .join(nc).on(nc.SOURCE_NAME.eq(nk.TABLE_SOURCE_NAME), nc.TABLE_SCHEMA.eq(nk.TABLE_SCHEMA),
                            nc.TABLE_NAME.eq(nk.TABLE_NAME), nc.COLUMN_NAME.eq(nk.COLUMN_NAME))
                        .where(nk.GRAPH_NAME.eq(n.GRAPH_NAME), nk.TYPE_NAME.eq(n.TYPE_NAME))
                        .orderBy(nk.POSITION))
                    .convertFrom(r -> r.map(Records.mapping(Column::new))))
                .from(s)
                .join(n).on(n.GRAPH_NAME.eq(s.GRAPH_NAME), n.TYPE_NAME.eq(s.TYPE_NAME))
                .where(s.GRAPH_NAME.eq(tt.GRAPH_NAME), s.TYPE_NAME.eq(tt.TYPE_NAME)))
            .convertFrom(r -> r.map(Records.mapping(RawNode::new)));

        var out = new ArrayList<Entity>();
        store.dsl()
            .select(tt.TYPE_NAME, tt.TABLE_SCHEMA, tt.TABLE_NAME, t.CLASS_FQN, t.JOOQ_NAME, sc.TABLES_CLASS_FQN,
                node, keys, accept)
            .from(tt)
            .join(t).on(t.SOURCE_NAME.eq(tt.TABLE_SOURCE_NAME), t.TABLE_SCHEMA.eq(tt.TABLE_SCHEMA),
                t.TABLE_NAME.eq(tt.TABLE_NAME))
            .join(sc).on(sc.SOURCE_NAME.eq(t.SOURCE_NAME), sc.TABLE_SCHEMA.eq(t.TABLE_SCHEMA))
            .where(tt.GRAPH_NAME.eq(store.graphName()), tt.NAMED_TYPE_KIND.eq("OBJECT"))
            .and(exists(selectOne().from(a).where(a.GRAPH_NAME.eq(tt.GRAPH_NAME),
                    a.COORDINATE.eq(tt.TYPE_NAME), a.DIRECTIVE_NAME.eq("key")))
                .or(exists(selectOne().from(s).where(s.GRAPH_NAME.eq(tt.GRAPH_NAME),
                    s.TYPE_NAME.eq(tt.TYPE_NAME)))))
            .orderBy(tt.TYPE_NAME)
            .forEach(r -> entity(r.value1(), r.value2(), r.value3(), r.value4(), r.value5(), r.value6(),
                r.value7().isEmpty() ? null : r.value7().getFirst(), r.value8(),
                r.value9().isEmpty() ? null : r.value9().getFirst()).ifPresent(out::add));
        return List.copyOf(out);
    }

    /**
     * The entity a type's keys and nodehood make, or none where a node id alternative names a node this
     * subgraph does not resolve.
     */
    private static java.util.Optional<Entity> entity(String typeName, String schema, String table,
                                                     String tableClass, String tableField, String tablesClass,
                                                     RawNode node, List<RawKey> keys, RawAccept accept) {
        var alternatives = new ArrayList<Alternative>();
        for (var key : keys) {
            if (key.fields().isEmpty()) {
                continue;
            }
            // A voucher key is resolved by verifying the voucher, not by matching columns: its lookup
            // part decodes onto the authored key the store pairs the @accept with, by identity.
            if (key.fields().stream().anyMatch(field -> "VOUCHER".equals(field.kind()))) {
                if (accept == null || accept.ordinal() != key.ordinal()) {
                    throw new IllegalStateException("Entity '" + typeName + "' carries a voucher key #"
                        + key.ordinal() + " the store states no @accept for");
                }
                if (keys.stream().noneMatch(k -> k.ordinal() == accept.decodesOnto())) {
                    throw new IllegalStateException("Entity '" + typeName + "' accepts vouchers decoding onto"
                        + " key #" + accept.decodesOnto() + ", which it does not carry");
                }
                alternatives.add(new Alternative.Voucher(key.ordinal(), accept.voucherField(), accept.scope(),
                    new Identity.Key(accept.decodesOnto()), accept.vouchedFor(), key.resolvable()));
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
