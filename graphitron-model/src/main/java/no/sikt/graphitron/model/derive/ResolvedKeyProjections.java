package no.sikt.graphitron.model.derive;

import no.sikt.graphitron.model.jooq.ColumnRef;
import no.sikt.graphitron.model.jooq.TableRef;
import org.jooq.DSLContext;

import java.util.List;

import static no.sikt.graphitron.model.Tables.INTENT_RESOLVED_NODE_KEY_PROJECTION;

/**
 * The {@code argMapping} bindings that decode a node id and project one of its key columns, read off
 * {@code intent_resolved_node_key_projection} as the positive population an emitter acts on. Sibling
 * of {@link ArgmappingProjectionDefects}, which reads the negative half of the same resolution: what
 * that class turns into rejections, this one turns into emission.
 *
 * <p>The read is deliberately coarser than the view's own grain, and the coarsening is the product's
 * one design decision. The view keys rows by {@code (site, use_site, position)} because a rejection
 * has to name the use site that is asking; an emitter needs none of that, because what it looks a
 * projection up by is the binding in its hand: the coordinate the {@code argMapping} sits on and the
 * path as written. So the projection is a function of {@code (type, field, argument_path)} and this
 * reads it at that grain, distinct. The trailing segment rides along without widening that grain: the
 * view's two arms are disjoint on the path's own trailing-segment count, so one written path carries
 * one value for it, and a path that somehow carried two would collide on
 * {@code KeyProjectionRelation}'s key rather than doubling the read.
 *
 * <p>Dropping {@code site} from the key is sound rather than convenient, and it is worth stating why
 * because a reader would otherwise suspect a collapse. Within one {@code (type, field)} coordinate
 * every site that resolves a leaf resolves it against the same slots, so one written path reaches one
 * leaf and one node type whatever directive spelled it: a field-site {@code @condition} and a
 * {@code @routine} on the same field naming {@code input.inventoryId.inventory_id} are two pair rows
 * with one answer. The input-field {@code @condition} coordinate is the input type's own, which
 * shares no name with an output type, so it does not meet the others at all. Two rows that did
 * disagree would collide on {@code KeyProjectionRelation}'s key and fail
 * the build rather than having one arbitrarily win, which is why nothing here re-checks it.
 *
 * <p>Nothing is decoded into a verdict vocabulary and nothing is rejected: a resolvable projection
 * whose site no emitter reads is {@link ArgmappingProjectionDefects}'s deferral, decided there
 * against the wired set, and by the time this runs that decision has already failed the build. What
 * survives to here is a projection an emitter will read.
 */
public final class ResolvedKeyProjections {

    private ResolvedKeyProjections() {}

    /**
     * One projected binding, carrying everything its emission needs and nothing a walk holds: the
     * {@code argMapping} coordinate and path that named it, the trailing segment the author spelled
     * past the node id where they spelled one, the node type the wire id decodes against, that node
     * type's wire id and table, the ordered key list the decode loads, and the one column to read off
     * the decoded record.
     *
     * <p>{@code trailingSegmentName} is null where the author named no column and the key's arity
     * inferred it, and that null is the only thing telling the two resolutions apart. An emitter needs
     * it because the wire id it decodes sits at the written path minus that segment where one was
     * spelled and at the whole written path where none was; the two spellings are dotted paths of the
     * same arity at a nested leaf, so nothing about the path itself says which is which.
     *
     * <p>The key list rides beside the projected column rather than being derivable from it, because
     * the decode is positional: a {@code fromArray} load names every key field in key order and the
     * projection then reads one of them. Carrying only the projected column would leave the emitter
     * unable to write the load at all.
     */
    public record Projection(String typeName, String fieldName, String argumentPath,
                             String trailingSegmentName, String nodeTypeName, String typeId,
                             TableRef nodeTable, List<ColumnRef> keyColumns, ColumnRef column) {

        public Projection {
            keyColumns = List.copyOf(keyColumns);
        }
    }

    /**
     * The pass's typed product: every projection the graph resolved, at the grain a plan joins them
     * onto command rows by. Empty for a graph whose {@code argMapping} paths all bind ordinary
     * values, and for a run with no store to read.
     */
    public record Projections(List<Projection> rows) {

        public Projections {
            rows = List.copyOf(rows);
        }

        /** The empty product, for callers producing a plan with no store behind it. */
        public static Projections empty() {
            return new Projections(List.of());
        }
    }

    /**
     * Reads every resolved projection over {@code graphName}'s partition, in coordinate order, joined
     * to its node type's emission facts.
     *
     * <p>A projection whose node type the store cannot assemble facts for is a build failure rather
     * than a dropped row, and the two ways to arrive there are named in the message. Dropping it would
     * emit the raw base64 wire value, which is the silence this whole family exists to close, so there
     * is no lenient reading available: by the time this runs, the detections have already passed the
     * projection as emittable.
     */
    public static Projections read(DSLContext dsl, String graphName) {
        var nodeTables = StoreNodeTables.read(dsl, graphName);
        var p = INTENT_RESOLVED_NODE_KEY_PROJECTION;
        return new Projections(dsl
            .selectDistinct(p.TYPE_NAME, p.FIELD_NAME, p.WRITTEN_PATH, p.TRAILING_NAME,
                p.NODE_TYPE_NAME, p.COLUMN_NAME)
            .from(p)
            .where(p.GRAPH_NAME.eq(graphName))
            .orderBy(p.TYPE_NAME, p.FIELD_NAME, p.WRITTEN_PATH)
            .fetch(row -> projectionOf(nodeTables, row.value1(), row.value2(), row.value3(),
                row.value4(), row.value5(), row.value6())));
    }

    /** One store row joined to its node type's facts, or a failure naming which side came up short. */
    private static Projection projectionOf(StoreNodeTables.Tables nodeTables, String typeName,
                                           String fieldName, String argumentPath,
                                           String trailingSegmentName, String nodeTypeName,
                                           String columnName) {
        var nodeTable = nodeTables.get(nodeTypeName).orElseThrow(() -> new IllegalStateException(
            "Graphitron generator bug (key projection): the store resolved a key column of node type"
            + " '" + nodeTypeName + "' for '" + typeName + "." + fieldName + "' entry '"
            + argumentPath + "', but no table facts assembled for that type; either its @table"
            + " binding resolves more than one candidate or its schema publishes no Tables class, and"
            + " a projection cannot be emitted without the record class and the column constants"));
        var column = nodeTable.keyColumns().stream()
            .filter(c -> c.sqlName().equalsIgnoreCase(columnName)
                || c.javaName().equalsIgnoreCase(columnName))
            .findFirst()
            .orElseThrow(() -> new IllegalStateException(
                "Graphitron generator bug (key projection): the store resolved '" + columnName
                + "' as a key column of node type '" + nodeTypeName + "' for '" + typeName + "."
                + fieldName + "' entry '" + argumentPath + "', but the key list assembled for that"
                + " type is " + nodeTable.keyColumns().stream().map(ColumnRef::sqlName).toList()
                + "; the key-column relation and the bound table's own columns have drifted"));
        return new Projection(typeName, fieldName, argumentPath, trailingSegmentName, nodeTypeName,
            nodeTable.typeId(), nodeTable.table(), nodeTable.keyColumns(), column);
    }
}
