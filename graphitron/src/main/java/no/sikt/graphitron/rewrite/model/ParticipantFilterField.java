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

    /**
     * The field's {@code @nodeId} arguments whose node type differs per participant, each carrying the
     * per-branch decoders the fetcher's matches-none guard checks a supplied id against. Empty when
     * every {@code @nodeId} argument resolves one shared node type across the participants, which is
     * every explicit {@code @nodeId(typeName:)} and every bare one over participants that share a
     * node type.
     *
     * <p>One accessor rather than a component per leaf record: the fact is the same on both, and the
     * branch filters it pairs with already live beside the field rather than on it.
     */
    List<NodeIdArgDispatch> nodeIdArgDispatches();
}
