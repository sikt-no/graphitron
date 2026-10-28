package no.sikt.graphitron.render;

import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.CodeBlock;
import no.sikt.graphitron.rewrite.model.MethodRef;

/**
 * The one spelling of an {@code @externalField} call, {@code <Owner>.<method>(<alias>)}: the
 * expression the method builds over the row {@code alias} names. Shared by the field's own
 * projection ({@link no.sikt.graphitron.command.SelectTerm.HelperCall}) and the presence probe's
 * null test of it ({@link no.sikt.graphitron.command.PresenceProbe.ProbeTerm.NotNull}), so the
 * value and the test that witnesses it cannot call the method differently.
 */
final class ExternalFieldCall {

    private ExternalFieldCall() {}

    static CodeBlock of(MethodRef method, String alias) {
        return CodeBlock.of("$T.$L($L)", ClassName.bestGuess(method.className()), method.methodName(), alias);
    }
}
