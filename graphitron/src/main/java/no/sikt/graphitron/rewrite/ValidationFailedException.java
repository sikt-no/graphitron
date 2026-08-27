package no.sikt.graphitron.rewrite;

import java.util.List;

/**
 * Thrown by {@link GraphQLRewriteGenerator#generate()} and {@link GraphQLRewriteGenerator#validate()}
 * when {@link GraphitronSchemaValidator} returns a non-empty error list. Carries the structured
 * errors so callers (notably {@code DevMojo}) can render them themselves instead of falling back
 * to the wrapper message and stack trace.
 *
 * <p>Those two entry points also log every error to SLF4J in clang-style {@code file:line:col} form
 * before throwing, so one-shot {@code generate} / {@code validate} mojos keep their existing
 * line-by-line output. The dev loop does not raise this at all:
 * {@link GraphQLRewriteGenerator#runPass()} returns the same errors on its report and logs none of
 * them, so the grouped tree {@code WatchErrorFormatter} renders is the only error output a dev save
 * produces. Which entry point logs is therefore the whole difference; nothing intercepts anything.
 */
@SuppressWarnings("serial") // thrown and caught in-process; ValidationError is not Serializable
public class ValidationFailedException extends RuntimeException {

    private final List<ValidationError> errors;

    public ValidationFailedException(List<ValidationError> errors) {
        super(errors.size() + " schema validation error(s)");
        this.errors = List.copyOf(errors);
    }

    public List<ValidationError> errors() {
        return errors;
    }
}
