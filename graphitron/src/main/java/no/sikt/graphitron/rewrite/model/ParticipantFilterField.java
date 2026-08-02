package no.sikt.graphitron.rewrite.model;

import java.util.List;

/**
 * A polymorphic root whose filter surface rides per-participant rows rather than a flat filter
 * list: the multi-table UNION ALL roots ({@code QueryInterfaceField} / {@code QueryUnionField}).
 * Sibling capability to {@link SqlGeneratingField} on the condition axis; consumers that mint
 * or count condition surfaces pattern-match on this interface instead of enumerating the two
 * leaf records.
 *
 * <p>Deliberately not implemented by the child polymorphic leaves: their per-participant
 * filter surface (where present) is dispatch payload, not a coordinate-level condition trigger,
 * and widening this capability to them would change condition membership.
 */
public interface ParticipantFilterField {
    List<ParticipantFilters> participantFilters();
}
