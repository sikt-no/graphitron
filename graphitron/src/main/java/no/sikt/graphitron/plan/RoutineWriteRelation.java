package no.sikt.graphitron.plan;

import graphql.schema.FieldCoordinates;
import no.sikt.graphitron.command.RoutineWriteCommand;

import java.util.List;
import java.util.Optional;

/**
 * The routine-write command relation of one generation run: one row per {@code @routine}-writing
 * mutation coordinate, keyed by the coordinate alone. "Exactly one entry point per covered
 * coordinate" is the relation's key rather than a property a test hunts for; a producer minting
 * two rows for one coordinate fails here.
 *
 * <p>The relation covers both routine-write shapes, so unlike the launcher relation it carries no
 * migration dial: the producer's membership is the classifier's two leaves and nothing narrower.
 * A coordinate absent from these rows is a coordinate that does not write through a routine.
 *
 * <p><b>No method-name census, deliberately.</b> Its sibling relations fold {@code (owner, method)}
 * case-insensitively and reject a collision, because their names come out of an upper-camelling
 * formula that is not injective: two field names differing only in their first letter's case mint
 * one {@code rows<Field>}. Here the emitted name is the field's own, verbatim, on a fetchers class
 * named after the parent type, so distinct coordinates always mint distinct methods and a census
 * would be provably vacuous. A <em>folded</em> one would be worse than vacuous: it would reject
 * {@code rentFilm} beside {@code rentfilm}, which emit two perfectly legal Java methods. Do not
 * add either by analogy; the coordinate key is the whole invariant.
 */
public record RoutineWriteRelation(List<RoutineWriteCommand> rows) {

    public RoutineWriteRelation {
        rows = List.copyOf(rows);
        long distinctKeys = rows.stream().map(RoutineWriteCommand::coordinate).distinct().count();
        if (distinctKeys != rows.size()) {
            throw new IllegalArgumentException(
                "the routine-write relation is keyed by coordinate; a coordinate appeared twice");
        }
    }

    /**
     * The row for one coordinate, empty when the coordinate writes through no routine. The
     * fetcher generator dispatches on this presence rather than restating the producer's
     * membership predicate, so the two ends cannot drift.
     */
    public Optional<RoutineWriteCommand> rowFor(String parentTypeName, String fieldName) {
        var coordinate = FieldCoordinates.coordinates(parentTypeName, fieldName);
        return rows.stream().filter(r -> r.coordinate().equals(coordinate)).findFirst();
    }
}
