package no.sikt.graphitron.lsp.parsing;

import java.util.Set;

/**
 * Per-directive policy that consumers (completions, diagnostics, hover,
 * goto-definition) read instead of re-deriving from scattered string literals.
 * Sibling to {@link Behavior}: {@code Behavior} is the <em>coordinate</em> axis
 * ("what binds at this schema coordinate"); {@code DirectivePolicy} is the
 * <em>directive-name</em> axis ("what is true of the directive the cursor sits
 * under"). The two are orthogonal, and the carve-outs below cannot key on
 * {@code Behavior} precisely because they discriminate by directive name where a
 * single coordinate is shared across directives.
 *
 * <p>The motivating case: {@code ExternalCodeReference.className} is the same
 * {@link SchemaCoordinate} whether it appears under {@code @enum} or the
 * deprecated {@code @record}. A consumer that must suppress class binding for
 * {@code @record} (R307) cannot read that off the coordinate's {@code Behavior};
 * it reads it off the enclosing directive name. Before this table that test was
 * copy-pasted as {@code "record".equals(...)} into five consumers, and the
 * method-validating set was owned by {@code Diagnostics} but only mirrored by
 * comment elsewhere.
 *
 * <p>String identity (not constant identity) is the contract, matching
 * {@code InferredDirectiveArgs} and the directive-vocabulary source-of-truth in
 * the build tier.
 */
public final class DirectivePolicy {

    private DirectivePolicy() {}

    /**
     * Directives whose {@code className}/{@code method} slot binds no live Java
     * element. {@code @record} (R307) is deprecated and ignored: its
     * {@code ExternalCodeReference} arg wraps a type marker, not a class or
     * method invocation, so class/method diagnostics, completions, hover, and
     * goto-definition all skip it. The coordinate is shared with {@code @enum},
     * so the carve-out keys on the directive name, not the coordinate.
     */
    private static final Set<String> DEAD_BINDING_DIRECTIVES = Set.of("record");

    /**
     * Directives whose {@code method} field is a live method invocation that
     * should be validated and completed against the resolved class. Directives
     * outside this set ({@code @record} / {@code @enum}, whose binding wraps a
     * type rather than a method) skip method validation.
     */
    private static final Set<String> METHOD_BINDING_DIRECTIVES = Set.of(
        "service", "condition", "externalField", "tableMethod", "reference", "sourceRow"
    );

    /**
     * Whether the named directive's class/method slot binds a live Java element.
     * False only for the deprecated/ignored directives in
     * {@link #DEAD_BINDING_DIRECTIVES} (today: {@code @record}). The R307
     * carve-out: when this returns false, class binding (diagnostics,
     * completions, hover, goto-definition) is suppressed.
     */
    public static boolean bindsLiveClass(String directiveName) {
        return !DEAD_BINDING_DIRECTIVES.contains(directiveName);
    }

    /**
     * Whether the named directive's {@code method} slot binds a live method
     * invocation worth validating against the resolved class. False for
     * directives whose method slot wraps a type ({@code @record} / {@code @enum}).
     */
    public static boolean bindsLiveMethod(String directiveName) {
        return METHOD_BINDING_DIRECTIVES.contains(directiveName);
    }
}
