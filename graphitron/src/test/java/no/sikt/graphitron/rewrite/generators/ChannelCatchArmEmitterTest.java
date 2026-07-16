package no.sikt.graphitron.rewrite.generators;

import graphql.language.SourceLocation;
import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.CodeBlock;
import no.sikt.graphitron.javapoet.ParameterizedTypeName;
import no.sikt.graphitron.javapoet.TypeName;
import no.sikt.graphitron.rewrite.model.ErrorChannel;
import no.sikt.graphitron.rewrite.model.GraphitronType.ErrorType;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Emitter unit-tier tests for {@link ChannelCatchArmEmitter} and
 * {@link ChannelEarlyReturnEmitter}. Renders {@code CodeBlock.toString()} once per arm to anchor
 * the structural intent (the redact-only arm, the {@code Mapped} mapping-walk that returns an
 * {@code Outcome.ErrorList}, the {@code LocalContext} sentinel arm, and the validator-pre-step
 * early return) without pinning the full generated body.
 */
@UnitTier
class ChannelCatchArmEmitterTest {

    private static final String OUTPUT_PACKAGE = "com.example.gen";
    private static final TypeName OUTCOME_OF_RECORD = ParameterizedTypeName.get(
        ClassName.get(OUTPUT_PACKAGE + ".schema", "Outcome"),
        ClassName.get("com.example", "SakRecord"));

    @Test
    void emit_emptyChannel_producesSurfaceClientErrorOrRedactArm() {
        var code = ChannelCatchArmEmitter.emit(
            Optional.empty(), OUTCOME_OF_RECORD, OUTPUT_PACKAGE, null).toString();

        // The no-channel disposition surfaces a GraphitronClientException and otherwise redacts.
        assertThat(code).contains("surfaceClientErrorOrRedact(e, env)");
        assertThat(code).doesNotContain("ErrorList");
    }

    @Test
    void emit_mappedChannel_walksMappingsAndReturnsErrorList() {
        var channel = Optional.<ErrorChannel>of(new ErrorChannel.Mapped(
            List.of(anyErrorType()), "FILM_PAYLOAD"));

        var code = ChannelCatchArmEmitter.emit(channel, OUTCOME_OF_RECORD, OUTPUT_PACKAGE, null).toString();

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
    void emit_localContextChannel_routesToDispatchToLocalContextWithSentinel() {
        var channel = Optional.<ErrorChannel>of(new ErrorChannel.LocalContext(
            List.of(anyErrorType()), "FILM_PAYLOAD"));
        var sentinel = CodeBlock.of("__sentinel");

        var code = ChannelCatchArmEmitter.emit(channel, OUTCOME_OF_RECORD, OUTPUT_PACKAGE, sentinel).toString();

        assertThat(code)
            .contains("dispatchToLocalContext(e,")
            .contains(".FILM_PAYLOAD")
            .contains("__sentinel");
    }

    @Test
    void emit_localContextChannel_withoutSentinel_throws() {
        var channel = Optional.<ErrorChannel>of(new ErrorChannel.LocalContext(
            List.of(anyErrorType()), "FILM_PAYLOAD"));

        assertThatThrownBy(() ->
            ChannelCatchArmEmitter.emit(channel, OUTCOME_OF_RECORD, OUTPUT_PACKAGE, null))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("sentinel");
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
                Optional.empty(), Optional.empty())), List.of());
    }
}
