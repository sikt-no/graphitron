package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.model.diagnostics.Rejection;

/**
 * What one run did about one decoding {@code @nodeId} coordinate. The three arms are exhaustive
 * over the outcomes a coordinate the walk stands on can have, and that totality is the whole point:
 * a coordinate with no disposition at all is one the generator neither carried out, nor refused, nor
 * failed to reach, which is a generator gap by construction and nothing else.
 *
 * <p>A sealed arm set rather than a boolean plus a nullable rejection, because one arm carries rows
 * and the others carry only their identity.
 *
 * <p>The obligation this seal serves is the validator's corollary of a classifier decision: a
 * classifier decision implying a generator branch has to fail at validation when that branch is
 * unimplemented, and a dropped decode is that corollary's {@code @nodeId} instance. Stated that way
 * it names no program, which is what lets a rail be re-sourced without the obligation moving: when
 * the classification walk drains, or the projection relation is re-grained, whatever states the
 * install then mints these instead.
 */
public sealed interface NodeIdDecodeDisposition {

    /**
     * The rails a decode is installed by. Two today, and the enum is where a third announces itself:
     * a rail is a place an install is decided, not a variant of anything, so nothing in the type
     * system pairs one with the coordinate it happened at and this names them instead.
     */
    enum Rail {
        /**
         * The classification walk's own carriers: a filter carrier, an {@code InputColumnBinding}, a
         * {@code LookupMapping} or a {@code CallParam}, holding one of
         * {@link no.sikt.graphitron.rewrite.model.CallSiteExtraction}'s decode arms.
         */
        CLASSIFICATION_WALK,
        /**
         * The projected-key rail, which is no walk product at all: an {@code argMapping} binding
         * resolves a key column off the decoded id and
         * {@link no.sikt.graphitron.render.ProjectedKeyReads} renders the decode for it. Where
         * {@link no.sikt.graphitron.render.ProjectedKeyReads#installRailOwns} stands the walk rail
         * down, this is the rail that installs.
         */
        PROJECTED_KEY
    }

    /** The generator carries the decode out at this coordinate, on {@code rail}. */
    record Installed(Rail rail) implements NodeIdDecodeDisposition {}

    /**
     * The generator refused the coordinate and said so. The rejection is the one the refusing site
     * already minted, carried rather than restated, so the ledger cannot describe a refusal other
     * than the one the build reports.
     */
    record Refused(Rejection rejection) implements NodeIdDecodeDisposition {}

    /**
     * The walk never got to the coordinate: the owning field's classification aborted above it. Its
     * own diagnostic is the field's, and the coverage rule stays quiet here rather than growing a
     * second, wrong-cause message on an already-failing build.
     */
    record NotReached() implements NodeIdDecodeDisposition {}
}
