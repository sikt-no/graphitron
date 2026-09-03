package no.sikt.graphitron.model.derive;

import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import no.sikt.graphitron.model.tables.GraphitronTabletype;
import no.sikt.graphitron.model.tables.SqlColumn;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_NODEHOOD;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_NODE_KEYCOLUMN;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_NODE_KEYCOLUMN_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_TABLETYPE;
import static no.sikt.graphitron.model.Tables.INTENT_NODE_METADATA_DEFECT;
import static no.sikt.graphitron.model.Tables.SQL_COLUMN;
import static no.sikt.graphitron.model.Tables.SQL_CONSTRAINT_COLUMN;
import static no.sikt.graphitron.model.Tables.SQL_NODE_KEY_COLUMN;
import static no.sikt.graphitron.model.Tables.SQL_NODE_METADATA;
import static no.sikt.graphitron.model.Tables.SQL_PRIMARY_KEY;
import static org.jooq.impl.DSL.count;
import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.notExists;
import static org.jooq.impl.DSL.selectCount;
import static org.jooq.impl.DSL.selectOne;
import static org.jooq.impl.DSL.upper;
import static org.jooq.impl.DSL.val;

/**
 * The capture-cadence writer of {@code graphitron_node_keycolumn}: which catalog columns carry a
 * node type's identity, in the order an id is built from them.
 *
 * <p>Three populations answer and they are tried in a fixed order: the columns an author pinned on
 * {@code @node(keyColumns:)}, the ones the bound table's generated class publishes, and the bound
 * table's own primary key. Only one answers for a given type. The order is the author's contract
 * first, because a pinned key is a published wire format that whatever the generator emits does not
 * move, and the catalog's own key last, because it is the default rather than a statement anyone
 * made.
 *
 * <p>A tier that applies and fails to resolve yields nothing rather than falling through, which is
 * the one rule here worth stating twice. An author who pinned columns and misspelled one has made
 * an error, and quietly encoding ids against the primary key instead would publish a wire format
 * they never asked for and would do it silently. The anti-join against
 * {@code graphitron_node_keycolumn_entry} is where that author is told. The same all-or-nothing
 * applies within a tier: a key list with a hole would decode values into the wrong positions, so a
 * single unresolved position drops the whole tuple.
 *
 * <p>Both of the first two tiers carry authored spellings meeting a catalog name, so both fold to
 * resolve, against the column's SQL name and against its generated field name alike. The third
 * needs no fold, reading catalog rows on both sides.
 *
 * <p>Runs as a stage of the graphitron gatherer after {@link NodeHood}, whose rows every tier here
 * stands on. Stated here rather than inside the gatherer so the seeding harness makes the same call.
 */
public final class NodeKeyColumns {

    private NodeKeyColumns() {}

    /** Clears and re-derives the graph's node key columns; see the class javadoc. */
    public static void derive(DSLContext dsl, String graphName) {
        dsl.deleteFrom(GRAPHITRON_NODE_KEYCOLUMN)
            .where(GRAPHITRON_NODE_KEYCOLUMN.GRAPH_NAME.eq(graphName)).execute();
        pinned(dsl, graphName);
        published(dsl, graphName);
        primaryKey(dsl, graphName);
    }

    /**
     * The author's tier. Applies to any node whose {@code @node} named columns, and answers only
     * where every one of them resolves; the count comparison is what makes a partly resolving list
     * yield nothing at all.
     */
    private static void pinned(DSLContext dsl, String graphName) {
        var e = GRAPHITRON_NODE_KEYCOLUMN_ENTRY;
        var b = GRAPHITRON_TABLETYPE;
        var c = SQL_COLUMN;
        dsl.insertInto(GRAPHITRON_NODE_KEYCOLUMN)
            .columns(GRAPHITRON_NODE_KEYCOLUMN.GRAPH_NAME, GRAPHITRON_NODE_KEYCOLUMN.TYPE_NAME,
                GRAPHITRON_NODE_KEYCOLUMN.POSITION, GRAPHITRON_NODE_KEYCOLUMN.COLUMN_NAME,
                GRAPHITRON_NODE_KEYCOLUMN.COLUMN_ORIGIN,
                GRAPHITRON_NODE_KEYCOLUMN.TABLE_SOURCE_NAME,
                GRAPHITRON_NODE_KEYCOLUMN.TABLE_SCHEMA, GRAPHITRON_NODE_KEYCOLUMN.TABLE_NAME)
            .select(dsl
                .select(e.GRAPH_NAME, e.TYPE_NAME, e.POSITION, c.COLUMN_NAME, val("SDL_PINNED"),
                    b.TABLE_SOURCE_NAME, b.TABLE_SCHEMA, b.TABLE_NAME)
                .from(e)
                .join(GRAPHITRON_NODEHOOD)
                .on(GRAPHITRON_NODEHOOD.GRAPH_NAME.eq(e.GRAPH_NAME),
                    GRAPHITRON_NODEHOOD.TYPE_NAME.eq(e.TYPE_NAME))
                .join(b).on(b.GRAPH_NAME.eq(e.GRAPH_NAME), b.TYPE_NAME.eq(e.TYPE_NAME))
                .join(c).on(c.SOURCE_NAME.eq(b.TABLE_SOURCE_NAME),
                    c.TABLE_SCHEMA.eq(b.TABLE_SCHEMA), c.TABLE_NAME.eq(b.TABLE_NAME))
                .and(folds(c, e.COLUMN_REF))
                .where(e.GRAPH_NAME.eq(graphName))
                .and(everyPositionResolves(e.GRAPH_NAME, e.TYPE_NAME)))
            .execute();
    }

    /**
     * The generated class's tier, for a node whose {@code @node} pinned nothing. Well-formedness is
     * the conjunction {@code intent_node_metadata_defect} states, a metadata row with no defect
     * rows against it, rather than the anti-join alone: a table publishing nothing has no defects
     * either and answers nothing here.
     */
    private static void published(DSLContext dsl, String graphName) {
        var k = SQL_NODE_KEY_COLUMN;
        var m = SQL_NODE_METADATA;
        var d = INTENT_NODE_METADATA_DEFECT;
        var b = GRAPHITRON_TABLETYPE;
        var c = SQL_COLUMN;
        var n = GRAPHITRON_NODEHOOD;
        dsl.insertInto(GRAPHITRON_NODE_KEYCOLUMN)
            .columns(GRAPHITRON_NODE_KEYCOLUMN.GRAPH_NAME, GRAPHITRON_NODE_KEYCOLUMN.TYPE_NAME,
                GRAPHITRON_NODE_KEYCOLUMN.POSITION, GRAPHITRON_NODE_KEYCOLUMN.COLUMN_NAME,
                GRAPHITRON_NODE_KEYCOLUMN.COLUMN_ORIGIN,
                GRAPHITRON_NODE_KEYCOLUMN.TABLE_SOURCE_NAME,
                GRAPHITRON_NODE_KEYCOLUMN.TABLE_SCHEMA, GRAPHITRON_NODE_KEYCOLUMN.TABLE_NAME)
            .select(dsl
                .select(n.GRAPH_NAME, n.TYPE_NAME, k.POSITION, c.COLUMN_NAME, val("JOOQ_METADATA"),
                    b.TABLE_SOURCE_NAME, b.TABLE_SCHEMA, b.TABLE_NAME)
                .from(n)
                .join(b).on(b.GRAPH_NAME.eq(n.GRAPH_NAME), b.TYPE_NAME.eq(n.TYPE_NAME))
                .join(m).on(m.SOURCE_NAME.eq(b.TABLE_SOURCE_NAME),
                    m.TABLE_SCHEMA.eq(b.TABLE_SCHEMA), m.TABLE_NAME.eq(b.TABLE_NAME))
                .join(k).on(k.SOURCE_NAME.eq(m.SOURCE_NAME), k.TABLE_SCHEMA.eq(m.TABLE_SCHEMA),
                    k.TABLE_NAME.eq(m.TABLE_NAME))
                .join(c).on(c.SOURCE_NAME.eq(b.TABLE_SOURCE_NAME),
                    c.TABLE_SCHEMA.eq(b.TABLE_SCHEMA), c.TABLE_NAME.eq(b.TABLE_NAME))
                .and(folds(c, k.COLUMN_NAME))
                .where(n.GRAPH_NAME.eq(graphName))
                .and(resolvesUniquely(b, k.COLUMN_NAME))
                .and(pinnedNothing(n.GRAPH_NAME, n.TYPE_NAME))
                .and(notExists(selectOne().from(d)
                    .where(d.SOURCE_NAME.eq(m.SOURCE_NAME), d.TABLE_SCHEMA.eq(m.TABLE_SCHEMA),
                        d.TABLE_NAME.eq(m.TABLE_NAME)))))
            .execute();
    }

    /**
     * The catalog's own tier, for a node no earlier one answered for. Reads catalog rows on both
     * sides, so it needs no fold and cannot half-resolve; a table with no primary key simply has no
     * rows here, which is the state a node over a routine result or a view reaches.
     */
    private static void primaryKey(DSLContext dsl, String graphName) {
        var pk = SQL_PRIMARY_KEY;
        var cc = SQL_CONSTRAINT_COLUMN;
        var b = GRAPHITRON_TABLETYPE;
        var n = GRAPHITRON_NODEHOOD;
        dsl.insertInto(GRAPHITRON_NODE_KEYCOLUMN)
            .columns(GRAPHITRON_NODE_KEYCOLUMN.GRAPH_NAME, GRAPHITRON_NODE_KEYCOLUMN.TYPE_NAME,
                GRAPHITRON_NODE_KEYCOLUMN.POSITION, GRAPHITRON_NODE_KEYCOLUMN.COLUMN_NAME,
                GRAPHITRON_NODE_KEYCOLUMN.COLUMN_ORIGIN,
                GRAPHITRON_NODE_KEYCOLUMN.TABLE_SOURCE_NAME,
                GRAPHITRON_NODE_KEYCOLUMN.TABLE_SCHEMA, GRAPHITRON_NODE_KEYCOLUMN.TABLE_NAME)
            .select(dsl
                .select(n.GRAPH_NAME, n.TYPE_NAME, cc.POSITION, cc.COLUMN_NAME,
                    val("CATALOG_PRIMARY_KEY"),
                    b.TABLE_SOURCE_NAME, b.TABLE_SCHEMA, b.TABLE_NAME)
                .from(n)
                .join(b).on(b.GRAPH_NAME.eq(n.GRAPH_NAME), b.TYPE_NAME.eq(n.TYPE_NAME))
                .join(pk).on(pk.SOURCE_NAME.eq(b.TABLE_SOURCE_NAME),
                    pk.TABLE_SCHEMA.eq(b.TABLE_SCHEMA), pk.TABLE_NAME.eq(b.TABLE_NAME))
                .join(cc).on(cc.SOURCE_NAME.eq(pk.SOURCE_NAME),
                    cc.TABLE_SCHEMA.eq(pk.TABLE_SCHEMA), cc.TABLE_NAME.eq(pk.TABLE_NAME),
                    cc.CONSTRAINT_NAME.eq(pk.CONSTRAINT_NAME))
                .where(n.GRAPH_NAME.eq(graphName))
                .and(pinnedNothing(n.GRAPH_NAME, n.TYPE_NAME))
                .and(notExists(selectOne().from(GRAPHITRON_NODE_KEYCOLUMN)
                    .where(GRAPHITRON_NODE_KEYCOLUMN.GRAPH_NAME.eq(n.GRAPH_NAME),
                        GRAPHITRON_NODE_KEYCOLUMN.TYPE_NAME.eq(n.TYPE_NAME)))))
            .execute();
    }

    /**
     * An authored spelling meeting a catalog column, matched against the column's SQL name and its
     * generated field name alike. Both are what a consumer sees, so an author naming either has
     * named the column, which is the convention every crossing of this kind in the schema uses.
     */
    private static Condition folds(SqlColumn c, Field<String> written) {
        return c.COLUMN_NAME_UPPER.eq(upper(written)).or(c.JOOQ_NAME_UPPER.eq(upper(written)));
    }

    /**
     * True where exactly one column of the bound table answers to the spelling. Matching on two
     * names means two columns can answer to one, a table carrying both a {@code filmid} and a
     * {@code film_id} whose generated name folds the same way, and a spelling two columns answer to
     * has not resolved. Declining is both the honest reading and the safe one: the alternative is
     * two rows at one position, which the key forbids, so an unlucky catalog would fail capture
     * rather than produce an answer anyone could act on.
     */
    private static Condition resolvesUniquely(GraphitronTabletype b, Field<String> written) {
        var c = SQL_COLUMN.as("fold_probe");
        return field(selectCount().from(c)
            .where(c.SOURCE_NAME.eq(b.TABLE_SOURCE_NAME), c.TABLE_SCHEMA.eq(b.TABLE_SCHEMA),
                c.TABLE_NAME.eq(b.TABLE_NAME))
            .and(folds(c, written))).eq(val(1));
    }

    /** True where the type pinned no key columns at all, which is what lets a later tier apply. */
    private static Condition pinnedNothing(Field<String> graph, Field<String> type) {
        var e = GRAPHITRON_NODE_KEYCOLUMN_ENTRY;
        return notExists(selectOne().from(e)
            .where(e.GRAPH_NAME.eq(graph), e.TYPE_NAME.eq(type)));
    }

    /**
     * True where every position the author pinned resolves to exactly one column of the bound table.
     * Counting both sides rather than testing each row is what makes the tier all-or-nothing: one
     * entry that matches nothing, or matches two columns, leaves the counts unequal and the whole
     * tuple goes unwritten. Equality therefore says more than that the tier answered. It says each
     * position resolves single-valuedly, which is what keeps the insert one row per position and is
     * why the join it guards needs no uniqueness test of its own.
     */
    private static Condition everyPositionResolves(Field<String> graph, Field<String> type) {
        var e = GRAPHITRON_NODE_KEYCOLUMN_ENTRY.as("pinned_probe");
        var b = GRAPHITRON_TABLETYPE.as("binding_probe");
        var written = field(selectCount().from(e)
            .where(e.GRAPH_NAME.eq(graph), e.TYPE_NAME.eq(type)));
        var resolved = field(selectCount()
            .from(e)
            .join(b).on(b.GRAPH_NAME.eq(e.GRAPH_NAME), b.TYPE_NAME.eq(e.TYPE_NAME))
            .where(e.GRAPH_NAME.eq(graph), e.TYPE_NAME.eq(type))
            .and(resolvesUniquely(b, e.COLUMN_REF)));
        return written.eq(resolved);
    }
}
