package no.sikt.graphitron.rewrite.derive;

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
 * reads it at that grain, distinct.
 *
 * <p>Dropping {@code site} from the key is sound rather than convenient, and it is worth stating why
 * because a reader would otherwise suspect a collapse. Within one {@code (type, field)} coordinate
 * every site that resolves a leaf resolves it against the same slots, so one written path reaches one
 * leaf and one node type whatever directive spelled it: a field-site {@code @condition} and a
 * {@code @routine} on the same field naming {@code input.inventoryId.inventory_id} are two pair rows
 * with one answer. The input-field {@code @condition} coordinate is the input type's own, which
 * shares no name with an output type, so it does not meet the others at all. Two rows that did
 * disagree would collide on {@link no.sikt.graphitron.command.KeyProjectionRelation}'s key and fail
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
     * One projected binding: the {@code argMapping} coordinate and path that named it, the node type
     * the wire id decodes against, and the key column to read off the decoded record. Spellings as
     * the store has them, the column under the winning key-column tier's own spelling rather than the
     * author's, which is the spelling that lines up with the decode's value order.
     */
    public record Projection(String typeName, String fieldName, String argumentPath,
                             String nodeTypeName, String columnName) {}

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

    /** Reads every resolved projection over {@code graphName}'s partition, in coordinate order. */
    public static Projections read(DSLContext dsl, String graphName) {
        var p = INTENT_RESOLVED_NODE_KEY_PROJECTION;
        return new Projections(dsl
            .selectDistinct(p.TYPE_NAME, p.FIELD_NAME, p.ARGUMENT_PATH, p.NODE_TYPE_NAME,
                p.COLUMN_NAME)
            .from(p)
            .where(p.GRAPH_NAME.eq(graphName))
            .orderBy(p.TYPE_NAME, p.FIELD_NAME, p.ARGUMENT_PATH)
            .fetch(row -> new Projection(row.value1(), row.value2(), row.value3(), row.value4(),
                row.value5())));
    }
}
