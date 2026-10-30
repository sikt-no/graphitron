package no.sikt.graphitron.render;

import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.CodeBlock;

/**
 * The one failure a generated node-id decode raises, and the message it carries.
 *
 * <p>Emitted as statement form: peek the wire value's type prefix, then throw the generated
 * {@code GraphitronClientException} with a two-branch message that tells a structurally-malformed id
 * from a well-formed id of another node type. Statement form rather than an expression so a
 * developer can breakpoint the decode and read a meaningful stack frame.
 *
 * <p>Here rather than on any one host because every host that decodes a node id must fail the same
 * way: the key helpers {@link CompositeDecodeHelperRegistry} mints for a predicate, the record helpers
 * {@link RecordDecodeFragments} mints for an {@code argMapping} projection or a {@code @service}
 * input-bean member, the lookup rows {@link LookupRows} builds, and the legacy core's DML input
 * decodes and jOOQ-record {@code @service} decodes. A message that differed between them would tell a
 * client that one spelling of an id validates and another does not, and a host raising anything but
 * the client error would have its text redacted where the field has no errors channel.
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
        return CodeBlock.builder()
            .addStatement("$T peeked = $T.peekTypeId($L)", String.class, encoderClass, peekArg)
            .addStatement("throw new $T($L)", clientException(outputPackage),
                messageExpr(expectedTypeId, displayName, msgVar, "peeked"))
            .build();
    }

    /**
     * The single-type throw as statements, for a host that holds the wire value in an {@code Object}
     * local rather than a {@code String}: it declares {@code <wireLocal>Peeked} off the local's own
     * name, so two throws in one generated method do not collide, and raises the client error with
     * the same message {@link #throwStatement} carries, echoing the wire value. A non-string value
     * peeks to {@code null} and reads as malformed, as in {@link #multiCandidateThrowStatement}.
     *
     * <p>The local must not be statically {@code String}: generated code targets Java 17, where
     * {@code instanceof String s} on a {@code String} expression does not compile. A host that already
     * holds a {@code String} uses {@link #throwStatement}.
     *
     * @param clientException the generated client-error type the throw raises
     * @param wireLocal       the {@code Object} local holding the wire value
     */
    public static CodeBlock throwStatement(ClassName clientException, ClassName encoderClass,
            String expectedTypeId, String displayName, String wireLocal) {
        String textLocal = wireLocal + "Text";
        String peekedLocal = wireLocal + "Peeked";
        return CodeBlock.builder()
            .addStatement("$T $L = $T.peekTypeId($L instanceof String $L ? $L : null)",
                String.class, peekedLocal, encoderClass, wireLocal, textLocal, textLocal)
            .addStatement("throw new $T($L)", clientException,
                messageExpr(expectedTypeId, displayName, wireLocal, peekedLocal))
            .build();
    }

    /**
     * The generated client-error type, {@code <outputPackage>.schema.GraphitronClientException}, for a
     * render-side host. The {@code render} package may not import the plan side's minting of the unit
     * name, so this is the render side's one copy of the formula.
     */
    public static ClassName clientException(String outputPackage) {
        return ClassName.get(outputPackage + ".schema", "GraphitronClientException");
    }

    /**
     * The multi-candidate message expression, for a host that already holds the peeked prefix in a
     * local: a malformed id, a prefix matching one of the candidates at the wrong key arity, and a
     * prefix belonging to none of them, classified exactly as the single-type message classifies its
     * one candidate. {@code candidates} is the candidate list as the client reads it.
     *
     * <p>Here rather than on either host because the two grains that decode a polymorphic node id
     * must fail identically: a lookup argument on a field returning a multitable interface, where the
     * generator consumes the decode itself, and a {@code @service} slot, where the decode crosses
     * into author code. One bad id failing two ways would tell a client that one spelling of an id
     * validates and another does not, which is this class's whole reason for existing.
     */
    public static CodeBlock multiCandidateMessage(java.util.List<String> candidateTypeIds,
            String candidates, String wireExpr, String peekedLocal) {
        var malformed = CodeBlock.builder().add("$L == null", peekedLocal);
        for (String typeId : candidateTypeIds) {
            malformed.add(" || $S.equals($L)", typeId, peekedLocal);
        }
        return CodeBlock.of("$L\n    ? $S + $L + $S\n    : $S + $L + $S + $L + $S",
            malformed.build(),
            "Invalid node id \"", wireExpr,
            "\" for this argument: not a valid id, expected an id of one of: " + candidates,
            "Invalid node id \"", wireExpr, "\" for this argument: decodes to type \"",
            peekedLocal, "\", expected an id of one of: " + candidates);
    }

    /**
     * The multi-candidate throw as statements, for a host that holds the wire value in a local and no
     * peeked prefix: it declares the two locals off {@code wireLocal}'s own name and raises the
     * client error with {@link #multiCandidateMessage}'s text.
     *
     * @param wireLocal the local holding the wire value, of any declared type; the {@code String}
     *                  narrowing happens inside the peek argument, so a non-string value peeks to
     *                  {@code null} and reads as malformed
     */
    public static CodeBlock multiCandidateThrowStatement(String outputPackage, ClassName encoderClass,
            java.util.List<String> candidateTypeIds, String candidates, String wireLocal) {
        String textLocal = wireLocal + "Text";
        String peekedLocal = wireLocal + "Peeked";
        return CodeBlock.builder()
            .addStatement("$T $L = $T.peekTypeId($L instanceof String $L ? $L : null)",
                String.class, peekedLocal, encoderClass, wireLocal, textLocal, textLocal)
            .addStatement("throw new $T($L)", clientException(outputPackage),
                multiCandidateMessage(candidateTypeIds, candidates, wireLocal, peekedLocal))
            .build();
    }

    /**
     * The ternary message expression. {@code peeked == null} (bad base64, or no colon) and
     * {@code peeked.equals(expectedTypeId)} (right type prefix, wrong key arity) both read as
     * "malformed"; any other non-null prefix is a well-formed id of another type and names the type
     * it decoded to.
     */
    private static CodeBlock messageExpr(String expectedTypeId, String displayName, String msgVar,
            String peekedLocal) {
        return CodeBlock.of(
            "$L == null || $S.equals($L)\n"
          + "    ? $S + $L + $S\n"
          + "    : $S + $L + $S + $L + $S",
            peekedLocal, expectedTypeId, peekedLocal,
            "Invalid node id \"", msgVar, "\" for this argument: not a valid " + displayName + " id",
            "Invalid node id \"", msgVar, "\" for this argument: decodes to type \"", peekedLocal,
            "\", expected a " + displayName + " id");
    }
}
