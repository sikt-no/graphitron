package no.sikt.graphitron.emit;

import no.sikt.graphitron.model.read.StoreHandle;
import org.jooq.Records;

import java.util.List;
import java.util.Objects;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_AST_FEDERATION_KEY_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_DIRECTIVE_APPLICATION;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_KEY_FIELD_BINDING;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_MINTED_KEY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_NODE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_COLUMN;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_TABLETYPE;
import static no.sikt.graphitron.model.Tables.SQL_COLUMN;
import static org.jooq.impl.DSL.coalesce;
import static org.jooq.impl.DSL.exists;
import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.inline;
import static org.jooq.impl.DSL.multiset;
import static org.jooq.impl.DSL.select;
import static org.jooq.impl.DSL.selectOne;

/**
 * The entity dispatch's query: each entity a graph resolves by selecting its row from its table, with
 * the key alternatives a representation of it may carry. One row per table-bound object type that
 * carries a federation {@code @key} application, authored or minted, or is a resolvable node, which
 * {@code Query.node} resolves the same way in a graph with no federation at all.
 *
 * <p>The alternatives are the type's {@code @key} applications in ordinal order, so the authored keys
 * come first and a minted one after them. An authored key whose fields bind columns of the type's own
 * table ({@code graphitron_key_field_binding}, kind {@code COLUMN}) is {@link Alternative.Direct},
 * each field's column read off {@code graphitron_occurrence_column}; an authored key whose one field
 * binds the node's own id ({@code NODE_ID}), and a key minted by federation's entity rule
 * ({@code graphitron_minted_key}, role {@code NODE}), is {@link Alternative.NodeId}, decoded with the
 * node's type id and key columns. A key the store refuses never reaches here, since a refused graph is
 * not generated.
 *
 * <p>Rows are in store terms: table and column names as the catalog spells them. Turning them into the
 * catalog references a generator emits is the reader's.
 */
public final class EntityKeyQuery {

    private EntityKeyQuery() {}

    /** One field of a direct key: the field the representation carries and the column it matches. */
    public record KeyField(String fieldName, String columnName, String columnJavaName, String bindingType,
                           String databaseType) {
        public KeyField {
            Objects.requireNonNull(fieldName, "fieldName");
            Objects.requireNonNull(columnName, "columnName");
        }
    }

    /** One key alternative of an entity, and whether this subgraph resolves the entity by it. */
    public sealed interface Alternative {
        boolean resolvable();

        /** A key whose fields each carry the value of one column of the entity's table. */
        record Direct(List<KeyField> fields, boolean resolvable) implements Alternative {
            public Direct {
                fields = List.copyOf(fields);
            }
        }

        /** A key whose one field is the node's own id, decoded into the node's key columns. */
        record NodeId(boolean resolvable) implements Alternative {}
    }

    /**
     * One entity: the type, its table, whether it is a resolvable node, and its key alternatives in
     * ordinal order.
     */
    public record Entity(String typeName, String tableSourceName, String tableSchema, String tableName,
                         boolean resolvableNode, List<Alternative> alternatives) {
        public Entity {
            alternatives = List.copyOf(alternatives);
        }
    }

    private record RawField(int position, String fieldName, String kind, String columnName,
                            String columnJavaName, String bindingType, String databaseType) {}

    private record RawKey(int ordinal, String mintedRole, boolean resolvable, List<RawField> fields) {}

    /** The graph's entities, by type name. */
    public static List<Entity> read(StoreHandle store) {
        var tt = GRAPHITRON_TABLETYPE;
        var n = GRAPHITRON_NODE;
        var a = GRAPHITRON_DIRECTIVE_APPLICATION;
        var ke = GRAPHITRON_AST_FEDERATION_KEY_ENTRY;
        var mk = GRAPHITRON_MINTED_KEY;
        var k = GRAPHITRON_KEY_FIELD_BINDING;
        var oc = GRAPHITRON_OCCURRENCE_COLUMN;
        var c = SQL_COLUMN;
        var fields = multiset(
            select(k.POSITION, k.FIELD_NAME, k.KIND, oc.COLUMN_NAME, c.JOOQ_NAME, c.BINDING_TYPE,
                    c.DATABASE_TYPE)
                .from(k)
                .leftJoin(oc).on(oc.GRAPH_NAME.eq(k.GRAPH_NAME), oc.COORDINATE.eq(k.COORDINATE),
                    oc.BOUND.eq(k.BOUND))
                .leftJoin(c).on(c.SOURCE_NAME.eq(oc.TABLE_SOURCE_NAME), c.TABLE_SCHEMA.eq(oc.TABLE_SCHEMA),
                    c.TABLE_NAME.eq(oc.TABLE_NAME), c.COLUMN_NAME.eq(oc.COLUMN_NAME))
                .where(k.GRAPH_NAME.eq(a.GRAPH_NAME), k.TYPE_NAME.eq(a.COORDINATE),
                    k.DIRECTIVE_NAME.eq(a.DIRECTIVE_NAME), k.ORDINAL.eq(a.ORDINAL))
                .orderBy(k.POSITION))
            .convertFrom(r -> r.map(Records.mapping(RawField::new)));
        // An authored key's resolvable: as written, true where the author left it out; a minted key's
        // as the rule that minted it states.
        var keys = multiset(
            select(a.ORDINAL, mk.ROLE, coalesce(ke.RESOLVABLE, mk.RESOLVABLE, inline(true)), fields)
                .from(a)
                .leftJoin(ke).on(ke.GRAPH_NAME.eq(a.GRAPH_NAME), ke.SOURCE_NAME.eq(a.SOURCE_NAME),
                    ke.SOURCE_LINE.eq(a.SOURCE_LINE), ke.SOURCE_COLUMN.eq(a.SOURCE_COLUMN))
                .leftJoin(mk).on(mk.GRAPH_NAME.eq(a.GRAPH_NAME), mk.TYPE_NAME.eq(a.COORDINATE),
                    mk.ORDINAL.eq(a.ORDINAL), a.ORIGIN.eq("MINTED"))
                .where(a.GRAPH_NAME.eq(tt.GRAPH_NAME), a.COORDINATE.eq(tt.TYPE_NAME),
                    a.DIRECTIVE_NAME.eq("key"))
                .orderBy(a.ORDINAL))
            .convertFrom(r -> r.map(Records.mapping(RawKey::new)));
        var resolvableNode = field(exists(selectOne().from(n)
            .where(n.GRAPH_NAME.eq(tt.GRAPH_NAME), n.TYPE_NAME.eq(tt.TYPE_NAME), n.RESOLVABLE.isTrue())));
        return store.dsl()
            .select(tt.TYPE_NAME, tt.TABLE_SOURCE_NAME, tt.TABLE_SCHEMA, tt.TABLE_NAME, resolvableNode, keys)
            .from(tt)
            .where(tt.GRAPH_NAME.eq(store.graphName()), tt.NAMED_TYPE_KIND.eq("OBJECT"))
            .and(exists(selectOne().from(a).where(a.GRAPH_NAME.eq(tt.GRAPH_NAME),
                    a.COORDINATE.eq(tt.TYPE_NAME), a.DIRECTIVE_NAME.eq("key")))
                .or(exists(selectOne().from(n).where(n.GRAPH_NAME.eq(tt.GRAPH_NAME),
                    n.TYPE_NAME.eq(tt.TYPE_NAME), n.RESOLVABLE.isTrue()))))
            .orderBy(tt.TYPE_NAME)
            .fetch(r -> new Entity(r.value1(), r.value2(), r.value3(), r.value4(), r.value5(),
                r.value6().stream().map(EntityKeyQuery::alternative).toList()));
    }

    private static Alternative alternative(RawKey key) {
        boolean nodeId = "NODE".equals(key.mintedRole())
            || (key.fields().size() == 1 && "NODE_ID".equals(key.fields().getFirst().kind()));
        if (nodeId) {
            return new Alternative.NodeId(key.resolvable());
        }
        return new Alternative.Direct(key.fields().stream()
            .map(f -> new KeyField(f.fieldName(), f.columnName(), f.columnJavaName(), f.bindingType(),
                f.databaseType()))
            .toList(), key.resolvable());
    }
}
