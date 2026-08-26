package no.sikt.graphitron.rewrite.derive;

import no.sikt.graphitron.rewrite.ValidationError;

import java.util.ArrayList;
import java.util.List;

/**
 * Everything the store-backed detection pass found, one field per rule family. The product
 * {@code FactCapture} returns and the generator folds into its error stream, so a family acquiring
 * a derivation joins this record rather than threading a second return value through the capture
 * entry points.
 *
 * <p>Each family keeps its own typed product rather than being flattened to errors here: the
 * authored-claim family's field verdicts are read by the LSP snapshot's {@code Conflicted}
 * projection overlay, and the {@code argMapping} family's defects carry the coordinates a consumer
 * would otherwise recover by parsing a message. {@link #violations()} is the one place the error
 * stream is assembled, in family declaration order, so no caller decides the order for itself.
 *
 * <p>{@link #argmappingProjections} and {@link #nodeIdDecodes} are two families rather than one
 * because they refuse the same two {@code @nodeId} facts at two carriers a store row tells apart, an
 * {@code argMapping} entry binding the node id and a producer parameter matching its name, and each
 * keys its rows on the coordinate its own remedy names. One family spanning both would have had to
 * pick one keying and restate the other's rows under it.
 *
 * <p>{@link #referenceForParticipants} is the family whose question no single coordinate can ask:
 * a {@code @referenceFor} on a {@code @nodeId} filter input field names a participant of the
 * <em>consuming</em> query's return type, and one input type may be consumed by several queries with
 * different participant sets. Per use site the classifier treats a non-matching name as inert; that
 * this family exists is what keeps inertness from swallowing a typo.
 *
 * <p>Not every member is a detection, and {@link #keyProjections} is the first that is not: it is the
 * positive half of the {@code argMapping} node-id resolution, read for the plan to emit from rather
 * than to reject. It rides here because the store handle does, opened for the capture and closed with
 * it, so a later phase wanting a store fact either reads it inside this pass or reopens the store to
 * ask a question the pass could have answered. The record is therefore what one open store yielded,
 * violations and emission facts alike.
 */
public record StoreDetections(AuthoredClaimConflicts.Detection claims,
                              ArgmappingProjectionDefects.Detection argmappingProjections,
                              NodeIdDecodeDefects.Detection nodeIdDecodes,
                              ReferenceForParticipantDefects.Detection referenceForParticipants,
                              ResolvedKeyProjections.Projections keyProjections) {

    /** The empty detection, for callers running capture without the detection pass. */
    public static StoreDetections empty() {
        return new StoreDetections(AuthoredClaimConflicts.Detection.empty(),
            ArgmappingProjectionDefects.Detection.empty(),
            NodeIdDecodeDefects.Detection.empty(),
            ReferenceForParticipantDefects.Detection.empty(),
            ResolvedKeyProjections.Projections.empty());
    }

    /** Every violation every family minted, each family's own order preserved within it. */
    public List<ValidationError> violations() {
        var out = new ArrayList<>(claims.violations());
        out.addAll(argmappingProjections.violations());
        out.addAll(nodeIdDecodes.violations());
        out.addAll(referenceForParticipants.violations());
        return List.copyOf(out);
    }

    /**
     * The authored-claim family's conflict verdicts, the one product a consumer beyond the error
     * stream reads today.
     */
    public List<AuthoredClaimConflicts.FieldVerdict.Conflict> fieldConflicts() {
        return claims.fieldConflicts();
    }
}
