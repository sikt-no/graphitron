package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.rewrite.model.ErrorChannel;
import no.sikt.graphitron.rewrite.model.GraphitronType.ErrorType;
import no.sikt.graphitron.rewrite.model.GraphitronType.ErrorType.ExceptionHandler;
import no.sikt.graphitron.rewrite.model.GraphitronType.ErrorType.SqlStateHandler;
import no.sikt.graphitron.rewrite.model.GraphitronType.ErrorType.ValidationHandler;
import no.sikt.graphitron.rewrite.model.GraphitronType.ErrorType.VendorCodeHandler;

import java.io.IOException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Declared-checked-exception match rule: verifies each non-exempt checked exception a method
 * declares (FQNs captured from {@link java.lang.reflect.Method#getExceptionTypes()}) is covered
 * by at least one handler on the surrounding field's {@link ErrorChannel}. Coverage rationale
 * the code does not show: {@link SqlStateHandler} and {@link VendorCodeHandler} cover any
 * declared {@link java.sql.SQLException} subtype because both match any {@code SQLException}
 * in the cause chain at runtime; {@link ValidationHandler} covers nothing here, it is a
 * wrapper-side pre-execution flag that never participates in the catch arm's dispatch.
 *
 * <p>{@link InterruptedException} and {@link java.io.IOException} (and subclasses) are exempt:
 * infrastructure errors that should redact rather than surface as a typed {@code @error}.
 * Unchecked exceptions are skipped even when declared; they still flow through the catch arm
 * at runtime. Behavior is covered by {@code CheckedExceptionClassificationTest}.
 *
 * <p>The classifier turns a non-empty result into an {@code UnclassifiedField} with a
 * descriptive reason.
 */
final class CheckedExceptionMatcher {

    private CheckedExceptionMatcher() {}

    /**
     * Returns the FQNs of declared exceptions that are checked, non-exempt, and not covered by
     * any handler on the channel. The channel may be {@link Optional#empty()} (the field has
     * no error channel); in that case the handler list is implicitly empty and any non-exempt
     * checked exception is unmatched.
     *
     * <p>An exception class that fails to load via {@link Class#forName(String)} is appended to
     * the unmatched list with a {@code "(not on classifier classpath)"} suffix; classifier
     * code paths reflect on developer classes already, so an unloadable declared exception is
     * a real configuration problem the schema author should see.
     */
    static List<String> unmatched(List<String> declaredExceptions, Optional<? extends ErrorChannel> channel,
            ClassLoader codegenLoader) {
        if (declaredExceptions.isEmpty()) return List.of();
        var handlers = channel.map(ErrorChannel::mappedErrorTypes)
            .orElse(List.of())
            .stream()
            .flatMap(et -> et.handlers().stream())
            .toList();
        var unmatched = new ArrayList<String>();
        for (var fqn : declaredExceptions) {
            Class<?> ex;
            try {
                // nameability: exempt (declared exception read off a reflected throws clause)
                ex = Class.forName(fqn, false, codegenLoader);
            } catch (ClassNotFoundException e) {
                unmatched.add(fqn + " (not on classifier classpath)");
                continue;
            }
            if (!isChecked(ex)) continue;
            if (isExempt(ex)) continue;
            if (!coveredByAnyHandler(ex, handlers, codegenLoader)) unmatched.add(fqn);
        }
        return List.copyOf(unmatched);
    }

    private static boolean isChecked(Class<?> ex) {
        if (!Throwable.class.isAssignableFrom(ex)) return false;
        if (RuntimeException.class.isAssignableFrom(ex)) return false;
        if (Error.class.isAssignableFrom(ex)) return false;
        return true;
    }

    /**
     * Schema authors who want explicit handling of an exempt exception can still declare a
     * matching {@link ExceptionHandler}; the exemption only means the absence of one is not a
     * classifier error.
     */
    private static boolean isExempt(Class<?> ex) {
        return InterruptedException.class.isAssignableFrom(ex)
            || IOException.class.isAssignableFrom(ex);
    }

    private static boolean coveredByAnyHandler(Class<?> ex, List<ErrorType.Handler> handlers,
            ClassLoader codegenLoader) {
        for (var h : handlers) {
            if (covers(h, ex, codegenLoader)) return true;
        }
        return false;
    }

    private static boolean covers(ErrorType.Handler h, Class<?> ex, ClassLoader codegenLoader) {
        return switch (h) {
            case ExceptionHandler eh -> {
                Class<?> handlerClass;
                try {
                    // nameability: exempt (revalidates the @error className TypeBuilder.validateExceptionClass already gated)
                    handlerClass = Class.forName(eh.exceptionClassName(), false, codegenLoader);
                } catch (ClassNotFoundException ignored) {
                    yield false;
                }
                yield handlerClass.isAssignableFrom(ex);
            }
            case SqlStateHandler ignored -> SQLException.class.isAssignableFrom(ex);
            case VendorCodeHandler ignored -> SQLException.class.isAssignableFrom(ex);
            case ValidationHandler ignored -> false;
        };
    }
}
