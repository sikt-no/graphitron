package no.sikt.graphitron.rewrite.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.SequencedMap;

/**
 * One {@code @nodeId} argument of a multitable polymorphic root query field whose participants
 * resolve <em>different</em> node types for it, paired with the per-participant decoder each branch
 * filters through.
 *
 * <p>A bare {@code @nodeId} infers its node type from the containing table, and a multitable root
 * classifies once per participant against that participant's own table, so one argument can mean
 * "a Customer id" on one branch and "a Staff id" on the next. The branches each prune themselves on
 * a decode miss ({@link CallSiteExtraction.PruneOnMismatch}), which turns the field into a dispatch:
 * exactly the branch that owns the supplied id contributes rows. This record is what the generated
 * fetcher needs to keep the client error alive at field granularity: before stage 1 runs it checks
 * the wire id against every decoder here, and throws when none of them accepts it.
 *
 * <p>Absent for a shared target. An explicit {@code @nodeId(typeName:)} pins one node type for every
 * branch, and so does a bare {@code @nodeId} whose participants happen to share a node type; both
 * keep the shipped {@link CallSiteExtraction.ThrowOnMismatch} branch semantics and produce no
 * dispatch fact.
 *
 * <p>The decoders are the {@link HelperRef.Decode} references the branches themselves consume, never
 * a restated (typeName, typeId) pair: both are reachable through the reference
 * ({@link HelperRef.Decode#nodeTypeName()} / {@link HelperRef.Decode#typeId()}), and a copy could
 * drift from the branch it claims to describe with nothing failing at build time.
 *
 * @param argName              the top-level SDL argument name
 * @param list                 whether the argument is list-shaped ({@code [ID!]} vs {@code ID}); the
 *                             guard checks each element and names the offending one
 * @param decodeByParticipant  participant type name to that participant's decoder, in the
 *                             participant order stage 1 emits its branches in. Ordered rather than a
 *                             plain {@code Map} because the generated guard's candidate-type list and
 *                             helper registration order are read by tests and by consumers' diffs.
 */
public record NodeIdArgDispatch(
        String argName,
        boolean list,
        SequencedMap<String, HelperRef.Decode> decodeByParticipant) {

    public NodeIdArgDispatch {
        if (argName == null || argName.isBlank()) {
            throw new IllegalArgumentException("a node-id dispatch fact names the argument it dispatches on");
        }
        if (decodeByParticipant == null || decodeByParticipant.size() < 2) {
            throw new IllegalArgumentException(
                "a node-id dispatch fact carries a decoder for at least two participants; one"
                + " participant is a shared target and keeps ThrowOnMismatch (argument '"
                + argName + "')");
        }
        decodeByParticipant = Collections.unmodifiableSequencedMap(
            new LinkedHashMap<>(decodeByParticipant));
    }

    /** The candidate node types this argument accepts an id of, in participant order. */
    public java.util.List<String> candidateNodeTypeNames() {
        return decodeByParticipant.values().stream().map(HelperRef.Decode::nodeTypeName).toList();
    }
}
