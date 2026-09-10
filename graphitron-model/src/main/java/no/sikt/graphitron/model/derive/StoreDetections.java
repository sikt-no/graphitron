package no.sikt.graphitron.model.derive;

import no.sikt.graphitron.model.diagnostics.ValidationError;
import org.jooq.Table;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
 * <p>{@link #nodeIdPolymorphicDecodes} is the {@code @nodeId} family whose question is about the
 * <em>set</em> of node types an id may belong to rather than about one: a {@code typeName:} naming a
 * multitable interface or union. Separate from {@link #nodeIdDecodes} for the reason its view is
 * separate from that family's, and the two populations are disjoint by construction: the incumbent
 * joins the node key's shape on the slot's resolved type, and a container resolves no key of its own.
 * It is also the one {@code @nodeId} family that reports at every site, its first verdict being that
 * the coordinate is not one the polymorphic rule reaches at all.
 *
 * <p>{@link #nodeIdLandings} is the {@code @nodeId} family whose question is
 * about a predicate rather than about a signature: whether the key a decode yields lands on the
 * columns the author's path says it does. It is separate from {@link #nodeIdDecodes} because the
 * two judge different operands at different grains, a producer parameter against a key's shape and
 * a per-position landing against a catalog column, and a coordinate can fail either without the
 * other having anything to say.
 *
 * <p>{@link #referenceForParticipants} is the family whose question no single coordinate can ask:
 * a {@code @referenceFor} on a {@code @nodeId} filter input field names a participant of the
 * <em>consuming</em> query's return type, and one input type may be consumed by several queries with
 * different participant sets. Per use site the classifier treats a non-matching name as inert; that
 * this family exists is what keeps inertness from swallowing a typo.
 *
 * <p>{@link #unlowerableOrderings} is the family whose question is neither about a name nor about
 * a signature but about whether a fact the schema states reaches the SQL it was written for: an
 * ordering is available at a coordinate and the coordinate's own read shape delivers none. It is
 * separate from every family above because it judges no author spelling at all; the declaration is
 * well formed, and what fails is that nothing lowers it.
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
                              NodeIdPolymorphicDecodeDefects.Detection nodeIdPolymorphicDecodes,
                              NodeIdLandingDefects.Detection nodeIdLandings,
                              ReferenceForParticipantDefects.Detection referenceForParticipants,
                              UnlowerableOrderings.Detection unlowerableOrderings,
                              ResolvedKeyProjections.Projections keyProjections) {

    /**
     * What each component of the pass reads, keyed by the component's own name, in the family
     * order the record declares.
     *
     * <p>The roster the detection pass's read-cadence reach is computed off. A component's entry is
     * its own {@code READS} set rather than a list restated here, so a component swapped, added or
     * repointed edits its reads where the component sits and this roster follows; what a gate over
     * it cannot see is a component that names a relation without putting it in its set, which is
     * the residue the gate discloses rather than one this roster closes.
     *
     * <p>Keyed by name rather than by the record component, because the name is what a gate's
     * failure has to print, and because one entry, {@link ResolvedKeyProjections}, reads through
     * {@code read} rather than a {@code detect} and so has no uniform method to key on.
     */
    public static Map<String, Set<Table<?>>> reads() {
        var roster = new LinkedHashMap<String, Set<Table<?>>>();
        roster.put("AuthoredClaimConflicts", AuthoredClaimConflicts.READS);
        roster.put("ArgmappingProjectionDefects", ArgmappingProjectionDefects.READS);
        roster.put("NodeIdDecodeDefects", NodeIdDecodeDefects.READS);
        roster.put("NodeIdPolymorphicDecodeDefects", NodeIdPolymorphicDecodeDefects.READS);
        roster.put("NodeIdLandingDefects", NodeIdLandingDefects.READS);
        roster.put("ReferenceForParticipantDefects", ReferenceForParticipantDefects.READS);
        roster.put("UnlowerableOrderings", UnlowerableOrderings.READS);
        roster.put("ResolvedKeyProjections", ResolvedKeyProjections.READS);
        return Collections.unmodifiableMap(roster);
    }

    /** The empty detection, for callers running capture without the detection pass. */
    public static StoreDetections empty() {
        return new StoreDetections(AuthoredClaimConflicts.Detection.empty(),
            ArgmappingProjectionDefects.Detection.empty(),
            NodeIdDecodeDefects.Detection.empty(),
            NodeIdPolymorphicDecodeDefects.Detection.empty(),
            NodeIdLandingDefects.Detection.empty(),
            ReferenceForParticipantDefects.Detection.empty(),
            UnlowerableOrderings.Detection.empty(),
            ResolvedKeyProjections.Projections.empty());
    }

    /** Every violation every family minted, each family's own order preserved within it. */
    public List<ValidationError> violations() {
        var out = new ArrayList<>(claims.violations());
        out.addAll(argmappingProjections.violations());
        out.addAll(nodeIdDecodes.violations());
        out.addAll(nodeIdPolymorphicDecodes.violations());
        out.addAll(nodeIdLandings.violations());
        out.addAll(referenceForParticipants.violations());
        out.addAll(unlowerableOrderings.violations());
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
