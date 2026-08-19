package no.sikt.graphitron.plan;

import graphql.schema.FieldCoordinates;
import no.sikt.graphitron.command.KeyProjection;
import no.sikt.graphitron.command.KeyProjectionRelation;
import no.sikt.graphitron.rewrite.GraphitronSchema;
import no.sikt.graphitron.rewrite.derive.ResolvedKeyProjections;
import no.sikt.graphitron.rewrite.model.ColumnRef;
import no.sikt.graphitron.rewrite.model.GraphitronType;

import java.util.List;

/**
 * Produces {@link KeyProjectionRelation} by joining the store's resolved projections against the
 * walked model's node types: the store says which coordinate projects which column of which node
 * type, and the model says what decoding that node type costs.
 *
 * <p>The division is the one the item turns on. Resolving a written segment to a key column is a
 * query over captured facts, spelled once in {@code intent_resolved_node_key_projection} and read by
 * {@link ResolvedKeyProjections}; re-deriving it here against the model would be a second spelling of
 * the same answer, agreeing until one of the two changed. What the model is asked for instead is
 * strictly the emission data the store does not hold: the encoder class, the wire type id, the
 * record class, and the {@link ColumnRef} objects behind the names. So the store decides, and this
 * locates.
 *
 * <p>Locating is where the two can be caught disagreeing, and it fails loudly rather than quietly.
 * The store resolves a column name across three tiers; the model reconciles {@code @node} against the
 * bound table's generated metadata. Those are two resolutions of one fact, and they are expected to
 * agree; a store column the model's key list does not carry means they did not, and there is no
 * defensible emission for that case: dropping the row would emit the raw wire value the whole item
 * exists to stop, and guessing a column would encode ids against a key the author never named. So it
 * throws, naming both sides.
 */
public final class KeyProjectionCommands {

    private KeyProjectionCommands() {}

    /**
     * Joins {@code projections} onto {@code schema}'s node types, one relation row per projected
     * binding. Empty in, empty out, which is a graph with no projected binding and also a plan
     * produced with no store behind it.
     */
    public static KeyProjectionRelation produce(ResolvedKeyProjections.Projections projections,
                                                GraphitronSchema schema) {
        return new KeyProjectionRelation(projections.rows().stream()
            .map(row -> rowOf(row, schema))
            .toList());
    }

    /** One store row located against the model, or an {@link IllegalStateException} naming the drift. */
    private static KeyProjection rowOf(ResolvedKeyProjections.Projection row,
                                       GraphitronSchema schema) {
        var nodeType = nodeTypeOf(row, schema);
        return new KeyProjection(
            FieldCoordinates.coordinates(row.typeName(), row.fieldName()),
            row.argumentPath(),
            nodeType.name(),
            nodeType.decodeMethod(),
            nodeType.table(),
            columnOf(row, nodeType.nodeKeyColumns()));
    }

    /**
     * The model's node type for the name the store's projection carries. The store resolved a key
     * column for it, which on every tier requires the type to be a node, so a model that does not
     * classify it as one is the two sides disagreeing about nodehood itself.
     */
    private static GraphitronType.NodeType nodeTypeOf(ResolvedKeyProjections.Projection row,
                                                      GraphitronSchema schema) {
        var type = schema.types().get(row.nodeTypeName());
        if (type instanceof GraphitronType.NodeType nodeType) {
            return nodeType;
        }
        throw new IllegalStateException(
            "Graphitron generator bug (key projection): the store resolved key column '"
            + row.columnName() + "' of node type '" + row.nodeTypeName() + "' for '"
            + row.typeName() + "." + row.fieldName() + "' entry '" + row.argumentPath()
            + "', but the walked model classifies '" + row.nodeTypeName()
            + "' as " + (type == null ? "no type at all" : type.getClass().getSimpleName())
            + " rather than a node type; the store's key-column tiers and the model's node"
            + " classification have drifted");
    }

    /**
     * The {@link ColumnRef} the store's column name denotes, matched case-insensitively because that
     * is the rule everywhere a column spelling meets a catalog name. The two sides spell the same
     * column under different tiers, so this is a lookup and not a second resolution: it is asking the
     * model for the object behind a name the store already picked.
     */
    private static ColumnRef columnOf(ResolvedKeyProjections.Projection row,
                                      List<ColumnRef> keyColumns) {
        return keyColumns.stream()
            .filter(c -> c.sqlName().equalsIgnoreCase(row.columnName())
                || c.javaName().equalsIgnoreCase(row.columnName()))
            .findFirst()
            .orElseThrow(() -> new IllegalStateException(
                "Graphitron generator bug (key projection): the store resolved '"
                + row.columnName() + "' as a key column of node type '" + row.nodeTypeName()
                + "' for '" + row.typeName() + "." + row.fieldName() + "' entry '"
                + row.argumentPath() + "', but the walked model's key list for that type is "
                + keyColumns.stream().map(ColumnRef::sqlName).toList()
                + "; the store's three key-column tiers and the model's @node reconciliation have"
                + " drifted, and there is no emission that could be right under both"));
    }
}
