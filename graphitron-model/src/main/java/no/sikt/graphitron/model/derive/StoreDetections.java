package no.sikt.graphitron.model.derive;

import org.jooq.DSLContext;
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
 * a capture leaves for the generator to fold into its error stream, so a family acquiring
 * a derivation joins this record rather than threading a second return value through the capture
 * entry points.
 *
 * <p>Each family keeps its own typed product rather than being flattened to errors here: the
 * authored-claim family's field verdicts are read by the LSP snapshot's {@code Conflicted}
 * projection overlay, and the {@code argMapping} family's defects carry the coordinates a consumer
 * would otherwise recover by parsing a message. {@link #violations()} is the one place the error
 * stream is assembled, in family declaration order, so no caller decides the order for itself.
 *
 * <p>{@link #referenceForParticipants} is the family whose question no single coordinate can ask:
 * a {@code @referenceFor} on a {@code @nodeId} filter input field names a participant of the
 * <em>consuming</em> query's return type, and one input type may be consumed by several queries with
 * different participant sets. Per use site the classifier treats a non-matching name as inert; that
 * this family exists is what keeps inertness from swallowing a typo.
 *
 * <p>{@link #sessionIdentity} judges the {@code $session} bindings and the configured handle
 * class against the build's {@code <sessionState>} and the classpath, which no other family reads.
 *
 * <p>{@link #nodeIdDecodeCoverage} is the second member that is not a detection, and it is not one
 * for a different reason than {@link #keyProjections}: it is half of a rule whose other half the
 * store does not hold at all. What the generator did about each decoding {@code @nodeId} coordinate
 * is the classification run's own fact, so the verdict is pronounced where the run's ledger meets
 * these rows, and what rides here is only the store's side of the subtraction.
 *
 * <p>{@link #legacyKeyShapes} is the shapes the encoder converts old ids through, an emission fact
 * on {@link #keyProjections}' terms.
 *
 * <p>Not every member is a detection, and {@link #keyProjections} is the first that is not: it is the
 * positive half of the {@code argMapping} node-id resolution, read for the plan to emit from rather
 * than to reject. It rides here because the store handle does, opened for the capture and closed with
 * it, so a later phase wanting a store fact either reads it inside this pass or reopens the store to
 * ask a question the pass could have answered. The record is therefore what one open store yielded,
 * violations and emission facts alike.
 */
public record StoreDetections(ClaimConflicts.Detection claims,
                              ArgmappingProjectionDefects.Detection argmappingProjections,
                              ReferenceForParticipantDefects.Detection referenceForParticipants,
                              SessionIdentityDefects.Detection sessionIdentity,
                              ResolvedKeyProjections.Projections keyProjections,
                              NodeIdDecodeCoverageFacts.Facts nodeIdDecodeCoverage,
                              StoreLegacyKeyShapes.Shapes legacyKeyShapes) {

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
        roster.put("ClaimConflicts", ClaimConflicts.READS);
        roster.put("ArgmappingProjectionDefects", ArgmappingProjectionDefects.READS);
        roster.put("ReferenceForParticipantDefects", ReferenceForParticipantDefects.READS);
        roster.put("SessionIdentityDefects", SessionIdentityDefects.READS);
        roster.put("ResolvedKeyProjections", ResolvedKeyProjections.READS);
        roster.put("NodeIdDecodeCoverageFacts", NodeIdDecodeCoverageFacts.READS);
        roster.put("StoreLegacyKeyShapes", StoreLegacyKeyShapes.READS);
        return Collections.unmodifiableMap(roster);
    }

    /**
     * These detections over what a store already holds.
     *
     * <p>A read, and here rather than beside a capture because it writes nothing: a pass that may
     * read facts and may not write them should not have to name a capture class to ask.
     */
    public static StoreDetections over(DSLContext dsl, String graphName,
                                          ClassifiedRun classified) {
        return switch (classified) {
            case ClassifiedRun.Absent ignored -> StoreDetections.empty();
            case ClassifiedRun.Present present -> {
                yield new StoreDetections(ClaimConflicts.detect(dsl, graphName),
                    ArgmappingProjectionDefects.detect(dsl, graphName),
                    ReferenceForParticipantDefects.detect(dsl, graphName),
                    SessionIdentityDefects.detect(dsl, graphName),
                    ResolvedKeyProjections.read(dsl, graphName),
                    NodeIdDecodeCoverageFacts.read(dsl, graphName),
                    StoreLegacyKeyShapes.read(dsl, graphName));
            }
        };
    }

    /** The empty detection, for callers running capture without the detection pass. */
    public static StoreDetections empty() {
        return new StoreDetections(ClaimConflicts.Detection.empty(),
            ArgmappingProjectionDefects.Detection.empty(),
            ReferenceForParticipantDefects.Detection.empty(),
            SessionIdentityDefects.Detection.empty(),
            ResolvedKeyProjections.Projections.empty(),
            NodeIdDecodeCoverageFacts.Facts.empty(),
            StoreLegacyKeyShapes.Shapes.empty());
    }

    /** Every violation every family minted, each family's own order preserved within it. */
    public List<ValidationError> violations() {
        var out = new ArrayList<>(claims.violations());
        out.addAll(argmappingProjections.violations());
        out.addAll(referenceForParticipants.violations());
        out.addAll(sessionIdentity.violations());
        return List.copyOf(out);
    }
}
