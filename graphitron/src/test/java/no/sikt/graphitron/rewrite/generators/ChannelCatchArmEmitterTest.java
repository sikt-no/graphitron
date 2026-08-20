package no.sikt.graphitron.rewrite.generators;

import graphql.language.SourceLocation;
import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.ParameterizedTypeName;
import no.sikt.graphitron.javapoet.TypeName;
import no.sikt.graphitron.rewrite.model.ErrorChannel;
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
 * {@code Outcome.ErrorList} and falls through to redact, and the validator-pre-step early
 * return) without pinning the full generated body. The emitter takes
 * {@link ErrorChannel.Mapped} directly; the {@link ErrorChannel.RouterDispatched} arms and the
 * no-channel disposition emit through {@code TypeFetcherGenerator}'s catch-arm seams, whose
 * output the pipeline tier pins.
 */
@UnitTier
class ChannelCatchArmEmitterTest {

    private static final String OUTPUT_PACKAGE = "com.example.gen";
    private static final TypeName OUTCOME_OF_RECORD = ParameterizedTypeName.get(
        ClassName.get(OUTPUT_PACKAGE + ".schema", "Outcome"),
        ClassName.get("com.example", "SakRecord"));

    @Test
    void emit_mappedChannel_walksMappingsAndReturnsErrorList() {
        var channel = new ErrorChannel.Mapped(List.of(anyErrorType()), "FILM_PAYLOAD");

        var code = ChannelCatchArmEmitter.emit(channel, OUTCOME_OF_RECORD, OUTPUT_PACKAGE).toString();

        assertThat(code)
            .contains(".FILM_PAYLOAD")
            .contains("for (")
            .contains("cause = e")
            .contains("mapping.match(cause)")
            .contains("ErrorList<>(")
            .contains("cause")
            .as("unmapped fall-through stays a redact")
            .contains("redact(e, env)");
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
            List.of(new ErrorType.ExceptionHandler("java.lang.RuntimeException",
                Optional.empty(), new no.sikt.graphitron.rewrite.model.GraphitronType.ErrorType.ClientMessage.FromSource())), List.of());
    }
}
