package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.model.diagnostics.NodeIdDecodeCoordinate;
import no.sikt.graphitron.rewrite.model.CallSiteExtraction;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What one classification run did about every decoding {@code @nodeId} coordinate it stood on: one
 * {@link NodeIdDecodeDisposition} per coordinate, minted where the coordinate and the verdict are
 * held together. The positive half of {@link NodeIdDecodeCoverage}'s rule, whose residual is the
 * authored instructions this ledger has no row for.
 *
 * <p><b>Why a ledger rather than a survey of carriers.</b> An install is a construction site, not a
 * variant: {@link CallSiteExtraction} seals the decode <em>arms</em> and pairs none of them with the
 * coordinate its construction happened at, and one whole rail (the projected-key rail) is not one of
 * its arms at all. A rule that instead enumerated the places a decode is built would be an inventory
 * a reader has to keep true, and an inventory that falls behind reports a working shape as a dropped
 * instruction. Recording the disposition at the deciding site makes "which rails install a decode" a
 * compiler question, which is what {@link #installedBy(CallSiteExtraction)} and its sibling below
 * turn it into.
 *
 * <p><b>The mints are transcriptions, not second decisions.</b> Both entry points switch
 * exhaustively over a verdict some other site already pronounced, so an arm added to either sealed
 * type is a compile error here rather than a silent gap in the ledger. Nothing in this class decides
 * whether a decode was installed; it decides only how to say so.
 */
public final class NodeIdDecodeLedger {

    /**
     * One row per coordinate, keyed by the coordinate's own components. A map rather than a list:
     * a coordinate reached twice (the same argument classified once per participant of a multitable
     * consumer, say) is one coordinate with one disposition, and the first mint is the walk's answer
     * for it. Later mints are dropped rather than overwriting, so a per-participant re-entry cannot
     * demote an install a sibling branch made.
     */
    private final Map<NodeIdDecodeCoordinate, NodeIdDecodeDisposition> rows = new LinkedHashMap<>();

    /**
     * Records {@code disposition} at {@code at}, unless the coordinate already carries one. The one
     * write path: every {@code record*} method below funnels here, so a coordinate's row is minted
     * in exactly one place whatever decided it.
     */
    private void put(NodeIdDecodeCoordinate at, NodeIdDecodeDisposition disposition) {
        rows.putIfAbsent(at, disposition);
    }

    /**
     * Transcribes the {@code @nodeId} leaf resolver's verdict. The resolver's seal is already the
     * decision this ledger wants: four arms install a decode and {@code Rejected} refuses, and its
     * two callers ({@link BuildContext#classifyInputField} and {@code FieldBuilder.classifyArgument})
     * are the sites that hold the coordinate the resolver declines to carry.
     */
    void recordLeaf(NodeIdDecodeCoordinate at, NodeIdLeafResolver.Resolved resolved) {
        put(at, switch (resolved) {
            case NodeIdLeafResolver.Resolved.SameTable ignored -> installedByTheWalk();
            case NodeIdLeafResolver.Resolved.FkTarget ignored -> installedByTheWalk();
            case NodeIdLeafResolver.Resolved.AuthorOwnedPredicate ignored -> installedByTheWalk();
            case NodeIdLeafResolver.Resolved.Rejected r ->
                new NodeIdDecodeDisposition.Refused(r.rejection());
        });
    }

    /**
     * Records a producer-slot binding that resolved to {@code extraction}, as an install where that
     * extraction carries a decode.
     *
     * <p>Where it carries none, this writes nothing, and the silence is the point: a decoding
     * instruction whose slot resolved to an extraction that hands the wire string on verbatim is
     * precisely the failure class this ledger exists to expose, so it falls to the residual rather
     * than being recorded as anything. A slot the resolver <em>refused</em> is a different fact and
     * takes {@link #recordRefusal} instead.
     */
    void recordSlot(NodeIdDecodeCoordinate at, CallSiteExtraction extraction) {
        if (installedBy(extraction)) {
            put(at, installedByTheWalk());
        }
    }

    /**
     * Records an install the caller has already established, for a site whose decode is not held in
     * a {@link CallSiteExtraction} the ledger could be shown. The record rail's per-field key
     * decode is the one such site: its leaf is a {@code RecordKeyDecode}, which is not an arm of
     * the seal, and the arm that will carry it is assembled from every field of the input type
     * after this one has been decided.
     */
    void recordInstall(NodeIdDecodeCoordinate at) {
        put(at, installedByTheWalk());
    }

    /** Records a refusal some site other than the leaf resolver minted. */
    void recordRefusal(NodeIdDecodeCoordinate at,
                       no.sikt.graphitron.model.diagnostics.Rejection rejection) {
        put(at, new NodeIdDecodeDisposition.Refused(rejection));
    }

    /**
     * Records that the walk's classification of the coordinate's owning field aborted above it.
     * Minted from the walk's own structural fact (the field classified as
     * {@code GraphitronField.UnclassifiedField}), never from whether some other family currently
     * emits a message at the coordinate.
     */
    void recordNotReached(NodeIdDecodeCoordinate at) {
        put(at, new NodeIdDecodeDisposition.NotReached());
    }

    /** Records the projected-key rail's install, decided off the store rather than by the walk. */
    void recordProjectedKeyInstall(NodeIdDecodeCoordinate at) {
        put(at, new NodeIdDecodeDisposition.Installed(
            NodeIdDecodeDisposition.Rail.PROJECTED_KEY));
    }

    private static NodeIdDecodeDisposition installedByTheWalk() {
        return new NodeIdDecodeDisposition.Installed(
            NodeIdDecodeDisposition.Rail.CLASSIFICATION_WALK);
    }

    /**
     * The {@link CallSiteExtraction} arms that carry a node-id decode, held beside the switch below
     * so the arm set is a value a meta-test can check rather than a shape only a reader can see.
     *
     * <p>{@code NodeIdDecodeArmCoverageTest} computes the same set structurally, off which arms
     * reach a handle on the opaque wire format, and fails when the two disagree. That is what makes
     * a fifth decode arm someone else's build failure: the switch below stops compiling because it
     * is exhaustive, and if it is answered {@code false} the meta-test says the arm carries a
     * decode anyway. Neither guard alone catches both mistakes.
     *
     * <p>{@link CallSiteExtraction.NestedInputField} is not a member and could not be: it carries
     * whatever its leaf carries, which is a property of a value rather than of the arm.
     */
    public static final java.util.Set<Class<? extends CallSiteExtraction>> DECODE_ARMS =
        java.util.Set.of(CallSiteExtraction.ThrowOnMismatch.class,
            CallSiteExtraction.PruneOnMismatch.class,
            CallSiteExtraction.NodeIdDecodeRecord.class,
            CallSiteExtraction.NodeIdDecodePolymorphicRecord.class,
            CallSiteExtraction.JooqRecord.class);

    /**
     * Whether this extraction carries a node-id decode, and so is the walk rail installing one.
     *
     * <p>Exhaustive over {@link CallSiteExtraction} on purpose, and the exhaustiveness is the pin
     * this design rests on rather than a style preference: a fifth decode arm is a compile error in
     * this switch, where an author has to say which side it falls on, instead of a silent absence
     * downstream that reads as a dropped instruction and fails a build that should pass.
     * {@code NodeIdDecodeArmCoverageTest} states the other half, that no arm carrying a decode
     * helper is answered {@code false} here.
     */
    public static boolean installedBy(CallSiteExtraction extraction) {
        return switch (extraction) {
            case CallSiteExtraction.NodeIdDecodeKeys ignored -> true;
            case CallSiteExtraction.NodeIdDecodeRecord ignored -> true;
            case CallSiteExtraction.NodeIdDecodePolymorphicRecord ignored -> true;
            // The one decode leaf that is not itself an arm of the seal: a record-bound input type
            // holds one RecordKeyDecode per @nodeId field under it, so the arm installs exactly
            // where it holds at least one.
            case CallSiteExtraction.JooqRecord r -> !r.keyDecodes().isEmpty();
            // A descent installs whatever its leaf does; ConditionResolver.rewrapForNested carries
            // a whole-slot install through as that leaf.
            case CallSiteExtraction.NestedInputField n -> installedBy(n.leaf());
            case CallSiteExtraction.Direct ignored -> false;
            case CallSiteExtraction.EnumValueOf ignored -> false;
            case CallSiteExtraction.ContextArg ignored -> false;
            case CallSiteExtraction.JooqConvert ignored -> false;
            case CallSiteExtraction.InputBean ignored -> false;
        };
    }

    /** Every coordinate this run disposed of, in mint order. */
    public Map<NodeIdDecodeCoordinate, NodeIdDecodeDisposition> rows() {
        return Collections.unmodifiableMap(rows);
    }

    /** The empty ledger, for a caller with no classification run behind it. */
    public static NodeIdDecodeLedger empty() {
        return new NodeIdDecodeLedger();
    }
}
