package no.sikt.graphitron.rewrite.model;

import java.util.List;

/**
 * Sealed result wrapper returned by directive walkers. {@link Ok} carries the populated carrier
 * and any non-Error diagnostics (Warning / Information / Hint); {@link Err} carries the typed
 * {@link AuthorError} list plus diagnostics, signalling that the orchestrator must exclude the
 * field from classification.
 *
 * <p>R238 lands the wrapper alongside {@link ServiceMethodCall}; every subsequent walker-carrier
 * slice reuses this shape.
 */
public sealed interface WalkerResult<C> {

    record Ok<C>(C carrier, List<Diagnostic> diagnostics) implements WalkerResult<C> {
        public Ok {
            diagnostics = List.copyOf(diagnostics);
            if (diagnostics.stream().anyMatch(d -> d.severity() == Severity.Error)) {
                throw new IllegalArgumentException("Ok cannot carry Error-severity diagnostics");
            }
        }

        public Ok(C carrier) { this(carrier, List.of()); }
    }

    record Err<C>(List<Rejection.AuthorError> errors, List<Diagnostic> diagnostics) implements WalkerResult<C> {
        public Err {
            errors = List.copyOf(errors);
            diagnostics = List.copyOf(diagnostics);
            if (errors.isEmpty()) {
                throw new IllegalArgumentException("Err must carry at least one error");
            }
        }

        public Err(List<Rejection.AuthorError> errors) { this(errors, List.of()); }
    }
}
