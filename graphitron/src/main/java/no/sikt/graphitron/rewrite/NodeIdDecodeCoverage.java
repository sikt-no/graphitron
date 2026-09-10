package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.model.derive.NodeIdDecodeCoverageFacts;
import no.sikt.graphitron.model.diagnostics.NodeIdDecodeCoordinate;
import no.sikt.graphitron.model.diagnostics.Rejection;
import no.sikt.graphitron.model.diagnostics.ValidationError;

import java.util.ArrayList;
import java.util.List;

/**
 * The rule that closes the {@code @nodeId} decode's last silence: an authored decoding instruction
 * the generator neither carried out, nor refused, nor failed to reach fails the build instead of
 * reaching a consumer's runtime as a base64 string compared against an integer key column.
 *
 * <p>Two operands and one subtraction. The census is what the author wrote, read off the store
 * ({@link NodeIdDecodeCoverageFacts}); the ledger is what this run did about each coordinate
 * ({@link NodeIdDecodeLedger}), total over the coordinates the walk stands on because the sweep in
 * {@link NodeIdDecodeNotReached} gives the rest their {@code NotReached} row. Totality is what
 * makes the subtraction mean one thing: there is no disposition-by-disposition filtering here and
 * no second subtraction, a row with <em>any</em> disposition being covered, so what is left over is
 * a generator gap by construction.
 *
 * <p><b>Why the residual defers rather than rejecting the schema.</b> The class is defined by the
 * generator not having carried something out, which is exactly "recognised but not yet
 * generator-supported". A structural rejection would tell an author to fix a schema that is
 * correct, and it is the fault the obvious version of this rule (an anti-join against the store's
 * own decode relation) walks straight into. No sub-population here can be attributed to the author,
 * so no arm reaches for {@code structural}.
 *
 * <p><b>Where it runs.</b> At the fold point where the store-backed detections' violations already
 * meet the walk's error stream, which is the one place both operands are in hand: the census off
 * the open store, the ledger off the run. It is deliberately not in the emit plan, which runs after
 * validation and only on an empty error list, so a check reading it could never fail this rule's
 * own gate.
 *
 * <p><b>Drainage.</b> Both install rails are transitional. When the classification walk drains, or
 * the projection relation is re-grained, this rule is re-sourced from whatever states the install
 * then; the obligation belongs to the check rather than to either rail, which is why the ledger's
 * vocabulary is worded over rails and not over carrier types.
 */
public final class NodeIdDecodeCoverage {

    private NodeIdDecodeCoverage() {}

    /**
     * Folds the projected-key rail's installs into {@code ledger} and reports every censused
     * instruction it still has no row for.
     *
     * <p>The rail-two fold happens here rather than in the walk because that rail is not a walk
     * product: its fact is a store row, resolved after capture, and the walk has nothing to say
     * about it. Folding it in before the subtraction is what keeps the residual meaning "no rail
     * reached this" rather than "the walk did not reach this".
     *
     * <p>Each report is located at the instruction the store positioned, which is the directive
     * application the author wrote where there is one and the slot's own declaration where the
     * basis carries no directive.
     */
    public static List<ValidationError> violations(NodeIdDecodeCoverageFacts.Facts facts,
                                                   NodeIdDecodeLedger ledger) {
        for (var installed : facts.projectedInstalls()) {
            ledger.recordProjectedKeyInstall(installed);
        }
        var out = new ArrayList<ValidationError>();
        for (var instruction : facts.census()) {
            if (ledger.rows().containsKey(instruction.coordinate())) {
                continue;
            }
            out.add(ValidationError.forField(instruction.coordinate().errorCoordinate(),
                Rejection.deferred(message(instruction)), instruction.location()));
        }
        return List.copyOf(out);
    }

    /**
     * What the author is told. The coordinate as they would spell it, the node type the id decodes
     * against, and the fact that no rail installs the decode there, with the remedies that exist
     * today: bind the id onto a key column with {@code argMapping}, or move the instruction to a
     * slot one of the rails reaches.
     */
    private static String message(NodeIdDecodeCoverageFacts.Instruction instruction) {
        return spelling(instruction.coordinate()) + " carries a @nodeId for node type '"
            + instruction.nodeTypeName() + "', and nothing in this build decodes it: neither the"
            + " classification walk nor the projected-key rail installs a decode at that"
            + " coordinate, so the encoded id would reach the query as the wire string it arrived"
            + " as. Until an emitter covers this shape, bind the id onto one of that node type's"
            + " key columns with argMapping, or move the @nodeId onto a slot the generator already"
            + " decodes: a filter argument or filter input field on the consuming field's own"
            + " table, or a producer parameter of the argument's name";
    }

    /** The coordinate in the author's own terms: an argument, or an input field under one. */
    private static String spelling(NodeIdDecodeCoordinate coordinate) {
        return switch (coordinate) {
            case NodeIdDecodeCoordinate.Argument a -> "argument '" + a.rootArgumentName()
                + "' on field '" + a.rootTypeName() + "." + a.rootFieldName() + "'";
            case NodeIdDecodeCoordinate.InputField f -> "input field '"
                + f.leaf().containerTypeName() + "." + f.leaf().fieldName() + "', consumed at "
                + f.describe() + ",";
        };
    }
}
