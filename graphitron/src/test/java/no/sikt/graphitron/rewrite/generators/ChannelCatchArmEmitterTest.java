package no.sikt.graphitron.rewrite.generators;

import graphql.language.SourceLocation;
import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.ParameterizedTypeName;
import com.palantir.javapoet.TypeName;
import no.sikt.graphitron.rewrite.model.GraphitronType.ErrorType;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Emitter unit-tier tests for {@link ChannelCatchArmEmitter} and
 * {@link ChannelEarlyReturnEmitter}. Renders {@code CodeBlock.toString()} once per arm to anchor
 * the structural intent (the {@code Mapped} mapping-walk that returns an
 * {@code Outcome.ErrorList} and falls through to the no-channel disposition, and the validator-pre-step early
 * return) without pinning the full generated body. The emitter takes
 * {@link ErrorChannel.Mapped} directly; the no-channel disposition emits through
 * {@code TypeFetcherGenerator}'s catch-arm seams, whose output the pipeline tier pins.
 */
@UnitTier
class ChannelCatchArmEmitterTest {

    private static final String OUTPUT_PACKAGE = "com.example.gen";
    private static final TypeName OUTCOME_OF_RECORD = ParameterizedTypeName.get(
        ClassName.get(OUTPUT_PACKAGE + ".schema", "Outcome"),
        ClassName.get("com.example", "SakRecord"));

    @Test
    void emit_mappedChannel_claimsAgainstItsMappingsAndReturnsErrorList() {
        var code = ChannelCatchArmEmitter.emit("FILM_PAYLOAD", OUTCOME_OF_RECORD, OUTPUT_PACKAGE).toString();

        assertThat(code)
            .contains(".FILM_PAYLOAD")
            .contains("ErrorList<>(")
            .as("unmapped fall-through is the no-channel disposition")
            .contains("ErrorRouter.surfaceClientErrorOrRedact(e, env);")
            .doesNotContain("ErrorRouter.redact(e, env)");
    }

    @Test
    void earlyReturn_wrapsViolationsLocalInErrorList() {
        var code = ChannelEarlyReturnEmitter.emit(OUTCOME_OF_RECORD, "violations", OUTPUT_PACKAGE)
            .toString();

        assertThat(code)
            .contains("newResult()")
            .contains("ErrorList<>(violations)")
            .contains("build()");
    }

    private static ErrorType anyErrorType() {
        return new ErrorType("NotFound", new SourceLocation(1, 1),
            List.of(new ErrorType.ExceptionHandler(new ErrorType.ExceptionClass.ClasspathClass("java.lang.RuntimeException"),
                Optional.empty(), new no.sikt.graphitron.rewrite.model.GraphitronType.ErrorType.ClientMessage.FromSource())), List.of());
    }
}
