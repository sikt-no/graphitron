package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.rewrite.model.DefaultedSlot;
import no.sikt.graphitron.rewrite.model.ErrorChannel;
import no.sikt.graphitron.rewrite.model.GraphitronType.ErrorType;
import no.sikt.graphitron.rewrite.model.GraphitronType.ErrorType.ExceptionHandler;
import no.sikt.graphitron.rewrite.model.GraphitronType.ErrorType.SqlStateHandler;
import no.sikt.graphitron.rewrite.model.GraphitronType.ErrorType.ValidationHandler;
import no.sikt.graphitron.rewrite.model.GraphitronType.ErrorType.VendorCodeHandler;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit coverage for {@link CheckedExceptionMatcher#unmatched}: each scenario in the §4 match
 * rule has a focused test. Scenarios:
 *
 * <ul>
 *   <li>An empty declared-exceptions list returns the empty unmatched list.</li>
 *   <li>An exact-class {@code ExceptionHandler} match (declared = handler class).</li>
 *   <li>A subtype-of-handler match (declared {@code SQLDataException} covered by
 *       {@code ExceptionHandler(SQLException)}).</li>
 *   <li>A {@code SqlStateHandler} or {@code VendorCodeHandler} covers any
 *       {@code SQLException}.</li>
 *   <li>A {@code SqlStateHandler}/{@code VendorCodeHandler} alone does not cover a
 *       non-{@code SQLException} declared throw.</li>
 *   <li>{@code ValidationHandler} covers nothing in the matcher (it's a wrapper-side flag).</li>
 *   <li>The {@code InterruptedException} and {@code IOException} exemptions skip the match
 *       check entirely.</li>
 *   <li>Unchecked exceptions ({@code RuntimeException} subclasses) are skipped (Java doesn't
 *       require them in {@code throws} but a method may declare them anyway).</li>
 *   <li>An absent channel rejects every non-exempt declared checked exception.</li>
 *   <li>An exception class that fails to load surfaces in the unmatched list with a
 *       descriptive suffix.</li>
 * </ul>
 */
@UnitTier
class CheckedExceptionMatcherTest {

    @Test
    void emptyDeclaredList_returnsEmpty() {
        var channel = channelWith(new ExceptionHandler("java.lang.RuntimeException", Optional.empty(), Optional.empty()));
        assertThat(CheckedExceptionMatcher.unmatched(List.of(), Optional.of(channel))).isEmpty();
    }

    @Test
    void exceptionHandler_exactClass_matches() {
        var channel = channelWith(new ExceptionHandler("java.sql.SQLException", Optional.empty(), Optional.empty()));
        assertThat(CheckedExceptionMatcher.unmatched(List.of("java.sql.SQLException"), Optional.of(channel)))
            .isEmpty();
    }

    @Test
    void exceptionHandler_subclassOfHandlerClass_matches() {
        // ExceptionHandler(SQLException) covers a method declaring throws SQLDataException
        // because the handler's class is a supertype of the declared class.
        var channel = channelWith(new ExceptionHandler("java.sql.SQLException", Optional.empty(), Optional.empty()));
        assertThat(CheckedExceptionMatcher.unmatched(List.of("java.sql.SQLDataException"), Optional.of(channel)))
            .isEmpty();
    }

    @Test
    void exceptionHandler_supertypeNotCoveredBySubtypeHandler() {
        // A method throws Throwable is NOT covered by ExceptionHandler(SQLException) — the
        // handler's class is more specific than the declared class.
        var channel = channelWith(new ExceptionHandler("java.sql.SQLException", Optional.empty(), Optional.empty()));
        assertThat(CheckedExceptionMatcher.unmatched(List.of("java.lang.Throwable"), Optional.of(channel)))
            .containsExactly("java.lang.Throwable");
    }

    @Test
    void sqlStateHandler_coversAnySqlException() {
        // SqlStateHandler matches any SQLException at runtime (the state predicate runs
        // inside the cause-chain walk). Same applies to VendorCodeHandler.
        var channel = channelWith(new SqlStateHandler("23503", Optional.empty(), Optional.empty()));
        assertThat(CheckedExceptionMatcher.unmatched(List.of("java.sql.SQLException"), Optional.of(channel)))
            .isEmpty();
        assertThat(CheckedExceptionMatcher.unmatched(List.of("java.sql.SQLDataException"), Optional.of(channel)))
            .isEmpty();
    }

    @Test
    void vendorCodeHandler_coversAnySqlException() {
        var channel = channelWith(new VendorCodeHandler("12345", Optional.empty(), Optional.empty()));
        assertThat(CheckedExceptionMatcher.unmatched(List.of("java.sql.SQLException"), Optional.of(channel)))
            .isEmpty();
    }

    @Test
    void sqlStateHandlerAlone_doesNotCoverNonSqlException() {
        // SqlStateHandler / VendorCodeHandler only cover SQLException subclasses — a method
        // throws java.lang.Exception needs a corresponding ExceptionHandler.
        var channel = channelWith(new SqlStateHandler("23503", Optional.empty(), Optional.empty()));
        assertThat(CheckedExceptionMatcher.unmatched(List.of("java.lang.Exception"), Optional.of(channel)))
            .containsExactly("java.lang.Exception");
    }

    @Test
    void validationHandler_coversNothingInMatcher() {
        // ValidationHandler is a wrapper-side flag; it never participates in the dispatch arm
        // and so never covers a declared exception in this matcher.
        var channel = channelWith(new ValidationHandler(Optional.empty()));
        assertThat(CheckedExceptionMatcher.unmatched(List.of("java.sql.SQLException"), Optional.of(channel)))
            .containsExactly("java.sql.SQLException");
    }

    @Test
    void interruptedException_isExempt() {
        // InterruptedException is exempt: the matcher skips it without consulting the channel.
        var noChannel = Optional.<ErrorChannel>empty();
        assertThat(CheckedExceptionMatcher.unmatched(List.of("java.lang.InterruptedException"), noChannel))
            .isEmpty();
    }

    @Test
    void ioException_isExempt() {
        // IOException (and its subclasses) is exempt: a service method declaring
        // throws IOException doesn't need a corresponding handler.
        var noChannel = Optional.<ErrorChannel>empty();
        assertThat(CheckedExceptionMatcher.unmatched(
                List.of("java.io.IOException", "java.io.FileNotFoundException"), noChannel))
            .isEmpty();
    }

    @Test
    void uncheckedException_isSkipped() {
        // RuntimeException subclasses are unchecked — Java doesn't require them in throws
        // but a method may declare them anyway. The §4 check applies to checked exceptions
        // only; unchecked declarations are silently skipped.
        var noChannel = Optional.<ErrorChannel>empty();
        assertThat(CheckedExceptionMatcher.unmatched(
                List.of("java.lang.IllegalArgumentException", "java.lang.NullPointerException"),
                noChannel))
            .isEmpty();
    }

    @Test
    void absentChannel_rejectsEveryNonExemptCheckedException() {
        // No channel ⟹ no handlers ⟹ every non-exempt declared checked exception is unmatched.
        var noChannel = Optional.<ErrorChannel>empty();
        assertThat(CheckedExceptionMatcher.unmatched(
                List.of("java.sql.SQLException", "java.io.IOException", "java.lang.IllegalArgumentException"),
                noChannel))
            .containsExactly("java.sql.SQLException");
    }

    @Test
    void unloadableException_appearsWithSuffix() {
        // A declared exception class that can't be loaded surfaces as unmatched with a
        // "(not on classifier classpath)" suffix so the schema author sees the configuration
        // problem on the classifier rejection.
        var noChannel = Optional.<ErrorChannel>empty();
        var unmatched = CheckedExceptionMatcher.unmatched(
            List.of("com.example.MissingException"), noChannel);
        assertThat(unmatched).hasSize(1);
        assertThat(unmatched.get(0)).contains("com.example.MissingException")
            .contains("not on classifier classpath");
    }

    @Test
    void multipleDeclaredExceptions_partialMatch_returnsOnlyUnmatched() {
        // A method may declare multiple checked exceptions; only the unmatched ones surface.
        var channel = channelWith(new ExceptionHandler("java.sql.SQLException", Optional.empty(), Optional.empty()));
        assertThat(CheckedExceptionMatcher.unmatched(
                List.of("java.sql.SQLException", "java.lang.Exception"), Optional.of(channel)))
            .containsExactly("java.lang.Exception");
    }

    private static ErrorChannel channelWith(ErrorType.Handler handler) {
        var errorType = new ErrorType("Err", null, List.of(handler));
        return new ErrorChannel(
            List.of(errorType),
            ClassName.bestGuess("com.example.Payload"),
            1,
            List.of(new DefaultedSlot(0, "data", ClassName.get("java.lang", "String"), "null")),
            "PAYLOAD");
    }
}
