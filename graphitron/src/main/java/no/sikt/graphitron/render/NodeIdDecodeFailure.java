package no.sikt.graphitron.render;

import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.CodeBlock;

/**
 * The one failure a generated node-id decode raises, and the message it carries.
 *
 * <p>Emitted as statement form: peek the wire value's type prefix, then throw the generated
 * {@code GraphitronClientException} with a two-branch message that tells a structurally-malformed id
 * from a well-formed id of another node type. Statement form rather than an expression so a
 * developer can breakpoint the decode and read a meaningful stack frame.
 *
 * <p>Here rather than on either host because two host families decode a node id and one bad id must
 * fail the same way at both grains: the key helpers {@link CompositeDecodeHelperRegistry} mints,
 * which project a decoded key for a predicate, and the record helpers
 * {@link RecordDecodeFragments} mints for an {@code argMapping} key-column projection. The two read
 * the same wire value at different grains, so a message that differed between them would tell a
 * client that one spelling of a filter validates its ids and another does not.
 *
 * <p>The second base64 walk {@code peekTypeId} performs, re-decoding what the decode already
 * discarded, is deliberate: it runs only on the error path, which is about to throw and abort the
 * field, so the redundant work costs nothing where it matters.
 */
public final class NodeIdDecodeFailure {

    private NodeIdDecodeFailure() {}

    /**
     * The throw, as statements ready to drop into a helper body's mismatch branch.
     *
     * @param outputPackage the run's output package, which is how the generated client-error type is
     *                      reached ({@code <outputPackage>.schema.GraphitronClientException})
     * @param encoderClass  the generated encoder the {@code peekTypeId} call qualifies with
     * @param expectedTypeId the wire type id the decode matched against; a peek equal to it is the
     *                      right type at the wrong arity, which reads as malformed rather than as a
     *                      wrong type
     * @param displayName   the node type as the message names it to the client
     * @param peekArg       the wire expression fed to {@code peekTypeId}, already a {@code String}
     * @param msgVar        the local concatenated into the message text
     */
    public static CodeBlock throwStatement(String outputPackage, ClassName encoderClass,
            String expectedTypeId, String displayName, String peekArg, String msgVar) {
        ClassName clientException = ClassName.get(outputPackage + ".schema", "GraphitronClientException");
        return CodeBlock.builder()
            .addStatement("$T peeked = $T.peekTypeId($L)", String.class, encoderClass, peekArg)
            .addStatement("throw new $T($L)", clientException,
                messageExpr(expectedTypeId, displayName, msgVar))
            .build();
    }

    /**
     * The ternary message expression. {@code peeked == null} (bad base64, or no colon) and
     * {@code peeked.equals(expectedTypeId)} (right type prefix, wrong key arity) both read as
     * "malformed"; any other non-null prefix is a well-formed id of another type and names the type
     * it decoded to.
     */
    private static CodeBlock messageExpr(String expectedTypeId, String displayName, String msgVar) {
        return CodeBlock.of(
            "peeked == null || $S.equals(peeked)\n"
          + "    ? $S + $L + $S\n"
          + "    : $S + $L + $S + peeked + $S",
            expectedTypeId,
            "Invalid node id \"", msgVar, "\" for this argument: not a valid " + displayName + " id",
            "Invalid node id \"", msgVar, "\" for this argument: decodes to type \"",
            "\", expected a " + displayName + " id");
    }
}
