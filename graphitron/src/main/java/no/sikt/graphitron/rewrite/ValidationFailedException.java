package no.sikt.graphitron.rewrite;

import java.util.List;

/**
 * Thrown by {@link GraphQLRewriteGenerator#generate()} and {@link GraphQLRewriteGenerator#validate()}
 * when {@link GraphitronSchemaValidator} returns a non-empty error list. Carries the structured
 * errors so callers (notably {@code DevMojo}) can render them themselves instead of falling back
 * to the wrapper message and stack trace.
 *
 * <p>The generator additionally logs every error to SLF4J in clang-style {@code file:line:col}
 * form before throwing, so one-shot {@code generate} / {@code validate} mojos keep their existing
 * line-by-line output. The dev-loop formatter intercepts this exception and replaces that
 * emission with grouped output; see {@code WatchErrorFormatter}.
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
