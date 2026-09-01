package no.sikt.graphitron.plan;

import graphql.schema.FieldCoordinates;
import no.sikt.graphitron.command.KeyProjection;
import no.sikt.graphitron.command.KeyProjectionRelation;
import no.sikt.graphitron.rewrite.derive.ResolvedKeyProjections;

/**
 * Produces {@link KeyProjectionRelation} from the store's resolved projections. A shape transform and
 * nothing more: every fact a projection's emission needs is already on the row this reads, so there is
 * no second source to reconcile against and no lookup that could fail here.
 *
 * <p>That emptiness is the design. An earlier shape of this producer joined the store's rows against
 * the walked {@code GraphitronSchema} to fetch the node type's table, key list and decode reference,
 * and described the split as a virtue: the store decides, the model locates. It is not one. A producer
 * reads facts, and the walked model is not a fact source, so locating against it is the walk surviving
 * one tier past the point it was supposed to end. The facts moved to where they belong,
 * {@link ResolvedKeyProjections} assembling them out of the catalog and intent relations, and what was
 * a join with two failure modes became this.
 *
 * <p>Nothing about the generated encoder appears on the way through, which is the other half of the
 * same rule. The projection's emitted read decodes through {@code NodeIdEncoder.decodeValues(typeId,
 * wire)}, so the only decode input a command row needs is the type id; the class those live on is a
 * function of generator configuration and belongs to render, which holds that configuration already.
 */
public final class KeyProjectionCommands {

    private KeyProjectionCommands() {}

    /**
     * Maps {@code projections} onto command rows, one per projected binding. Empty in, empty out,
     * which is a graph with no projected binding and also a plan produced with no store behind it.
     */
    public static KeyProjectionRelation produce(ResolvedKeyProjections.Projections projections) {
        return new KeyProjectionRelation(projections.rows().stream()
            .map(row -> new KeyProjection(
                FieldCoordinates.coordinates(row.typeName(), row.fieldName()),
                row.argumentPath(),
                row.trailingSegmentName(),
                row.nodeTypeName(),
                row.typeId(),
                row.nodeTable(),
                row.keyColumns(),
                row.column()))
            .toList());
    }
}
