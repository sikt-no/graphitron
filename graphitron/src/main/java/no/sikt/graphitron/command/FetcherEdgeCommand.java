package no.sikt.graphitron.command;

import graphql.schema.FieldCoordinates;

import java.util.List;
import java.util.Objects;

/**
 * One row of the fetcher edge relation: the generated units one coordinate's emitted
 * {@code <Type>Fetchers} methods reference, for the non-launcher coordinate families whose
 * references ride no other relation. {@link #owner} is the fetchers unit the coordinate's
 * methods land in; {@link #targets} are the referenced units, in a deterministic order
 * (participant projection classes for the polymorphic and discriminated deliveries, condition
 * glue for a polymorphic root's participant filters, the node dispatch unit for
 * {@code node(id:)} / {@code nodes(ids:)}, the terminus projection for a routine write).
 *
 * <p>Purely referential: the row emits nothing itself. It states, as plan data, which committed
 * units the coordinate's emitted methods compile against, and the recompile-graph projection
 * reads the edges off it instead of re-deriving them from the model.
 */
public record FetcherEdgeCommand(FieldCoordinates coordinate, UnitRef owner, List<UnitRef> targets) {

    public FetcherEdgeCommand {
        Objects.requireNonNull(coordinate, "a fetcher edge row is keyed by its field coordinate");
        Objects.requireNonNull(owner, "owner");
        targets = List.copyOf(targets);
        if (targets.isEmpty()) {
            throw new IllegalArgumentException(
                "a fetcher edge row carries at least one target; a coordinate referencing"
                + " nothing has no row");
        }
    }
}
