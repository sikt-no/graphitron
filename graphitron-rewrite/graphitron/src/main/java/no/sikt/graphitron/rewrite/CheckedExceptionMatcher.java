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
 * R12 §4 declared-checked-exception match rule. Walks a method's declared exception list
 * (as fully-qualified class names captured from {@link java.lang.reflect.Method#getExceptionTypes()})
 * and verifies each non-exempt checked exception is covered by at least one handler on the
 * surrounding field's {@link ErrorChannel}, per the spec's match rule:
 *
 * <ul>
 *   <li>{@link ExceptionHandler}: covered when the declared exception class is assignable to
 *       the handler's {@code exceptionClassName} (the handler's class is a supertype of, or
 *       equal to, the declared class). E.g. {@code ExceptionHandler(SQLException)} covers a
 *       method declaring {@code throws SQLDataException}; a method declaring {@code throws
 *       Throwable} is covered only by {@code ExceptionHandler(Throwable)}.</li>
 *   <li>{@link SqlStateHandler} / {@link VendorCodeHandler}: cover any declared exception
 *       assignable to {@link java.sql.SQLException}, since both variants match any
 *       {@code SQLException} in the cause chain at runtime. A declared exception that is not a
 *       {@code SQLException} subclass is not covered by these variants alone.</li>
 *   <li>{@link ValidationHandler}: covers nothing in this matcher — it's a wrapper-side
 *       pre-execution flag and never participates in the catch arm's dispatch.</li>
 * </ul>
 *
 * <p>Exemptions: {@link InterruptedException} and {@link java.io.IOException} (and their
 * subclasses) are infrastructure errors that should redact rather than surface as a typed
 * {@code @error}; they are exempt from the match rule per the §4 "Special cases" subsection,
 * so a service method declaring {@code throws IOException} does not need a corresponding
 * channel handler.
 *
 * <p>Unchecked exceptions ({@link RuntimeException} subclasses) are also skipped — Java
 * doesn't require them in {@code throws} clauses, but a method may declare them anyway
 * (e.g. {@code throws IllegalArgumentException}). The §4 check applies to checked exceptions
 * only; unchecked throws still flow through the catch arm at runtime.
 *
 * <p>Returns a list of FQNs that were not covered. An empty list means every declared
 * checked exception is either exempt or covered. The classifier turns a non-empty list into
 * an {@code UnclassifiedField} with a descriptive reason.
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
    static List<String> unmatched(List<String> declaredExceptions, Optional<ErrorChannel> channel) {
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
                ex = Class.forName(fqn);
            } catch (ClassNotFoundException e) {
                unmatched.add(fqn + " (not on classifier classpath)");
                continue;
            }
            if (!isChecked(ex)) continue;
            if (isExempt(ex)) continue;
            if (!coveredByAnyHandler(ex, handlers)) unmatched.add(fqn);
        }
        return List.copyOf(unmatched);
    }

    /**
     * A class is "checked" when it is a {@link Throwable} but not a {@link RuntimeException}
     * (or {@link Error}, but Java forbids declaring those in {@code throws} anyway, so this
     * branch is defensive). Used to filter declared exceptions before the match-rule walk.
     */
    private static boolean isChecked(Class<?> ex) {
        if (!Throwable.class.isAssignableFrom(ex)) return false;
        if (RuntimeException.class.isAssignableFrom(ex)) return false;
        if (Error.class.isAssignableFrom(ex)) return false;
        return true;
    }

    /**
     * Per §4 "Special cases": {@link InterruptedException} and {@link IOException} (and their
     * subclasses) are exempt. Schema authors who want explicit handling can still declare a
     * matching {@link ExceptionHandler}; the exemption only means the absence of one is not a
     * classifier error.
     */
    private static boolean isExempt(Class<?> ex) {
        return InterruptedException.class.isAssignableFrom(ex)
            || IOException.class.isAssignableFrom(ex);
    }

    private static boolean coveredByAnyHandler(Class<?> ex, List<ErrorType.Handler> handlers) {
        for (var h : handlers) {
            if (covers(h, ex)) return true;
        }
        return false;
    }

    private static boolean covers(ErrorType.Handler h, Class<?> ex) {
        return switch (h) {
            case ExceptionHandler eh -> {
                Class<?> handlerClass;
                try {
                    handlerClass = Class.forName(eh.exceptionClassName());
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
