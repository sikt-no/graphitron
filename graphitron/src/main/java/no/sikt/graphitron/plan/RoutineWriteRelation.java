package no.sikt.graphitron.plan;

import no.sikt.graphitron.command.RoutineWriteCommand;
import no.sikt.graphitron.command.TenantRouting;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The routine-write command relation of one generation run: one row per {@code @routine}-writing
 * mutation coordinate, keyed by the coordinate alone. "Exactly one entry point per covered
 * coordinate" is the relation's key rather than a property a test hunts for; a producer minting
 * two rows for one coordinate fails at construction, in the {@link CoordinateIndex} every
 * coordinate-keyed relation holds.
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
 *
 * <p>{@link #tenancy} is the run's acquisition axis (see {@link TenantRouting}), carried here
 * rather than on every row because whether a build routes per tenant at all is a run-grain fact.
 * Its coverage of these rows is this relation's invariant: under a routed axis every row's
 * coordinate carries an arm, checked below, because a routine write reaching emission with no
 * stated acquisition would acquire the default source in a build where that is the wrong tenant.
 */
public record RoutineWriteRelation(CoordinateIndex<RoutineWriteCommand> index, TenantRouting tenancy) {

    /** The relation over {@code rows}, indexed on the coordinate key it is declared to have. */
    public RoutineWriteRelation(List<RoutineWriteCommand> rows, TenantRouting tenancy) {
        this(CoordinateIndex.of(rows, RoutineWriteCommand::coordinate, "routine-write"), tenancy);
    }

    public RoutineWriteRelation {
        Objects.requireNonNull(index, "index");
        Objects.requireNonNull(tenancy, "tenancy");
        if (tenancy instanceof TenantRouting.Routed routed) {
            for (var row : index.rows()) {
                if (!routed.byCoordinate().containsKey(row.coordinate())) {
                    throw new IllegalArgumentException(
                        "the routine-write coordinate " + row.coordinate() + " has no tenancy"
                        + " acquisition in a routed run; every row this relation holds is an entry"
                        + " point that declares a connection, and an uncovered one would declare"
                        + " the default source's");
                }
            }
        }
    }

    /** The rows with no tenant routing: the axis a single-tenant run states once. */
    public static RoutineWriteRelation unrouted(List<RoutineWriteCommand> rows) {
        return new RoutineWriteRelation(rows, new TenantRouting.Unrouted());
    }

    /** The rows in producer order. */
    public List<RoutineWriteCommand> rows() {
        return index.rows();
    }

    /**
     * The row for one coordinate, empty when the coordinate writes through no routine. Unlike
     * the launcher relation's twin this is not a dispatch seam: the fetcher generator reaches it
     * only where the classification already routed a routine write, so it reads the row with
     * {@code orElseThrow} and an absence there is a producer bug rather than a fall-through. The
     * one presence read is {@link EmitPlan}'s, asking whether a key projection was reached.
     */
    public Optional<RoutineWriteCommand> rowFor(String parentTypeName, String fieldName) {
        return index.rowFor(parentTypeName, fieldName);
    }
}
