package no.sikt.graphitron.rewrite.model;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Sealed sub-family of {@link Rejection.AuthorError} for {@code ServiceMethodCallWalker}
 * (R238). Each typed arm carries the structural data its diagnostic message and LSP
 * {@code relatedInformation} need; downstream tooling switches on the arm rather than parsing
 * prose.
 *
 * <p>The arm-to-code mapping (see {@code graphitron-rewrite/roadmap/methodcall-walker-carrier.md}'s
 * LSP wire conventions table) is exposed via {@link #lspCode()} so the orchestrator can project
 * a typed error to a {@link Diagnostic} without a separate dispatch table.
 *
 * <p>The two arms here are exactly what the R238 translator-walker produces today. R238 ships its
 * walker as a translator over a resolved {@code MethodRef.Service} rather than fresh SDL+classloader
 * reflection (see the spec's "Walker substrate" note), so the broader failure taxonomy the spec
 * sketched (class-load, ambiguous-method, return-type, arg-mapping, input-bean-shape, ...) is still
 * produced upstream as {@code AuthorError.Structural} prose. Wiring those rejections through typed
 * arms here is a follow-up (R256, {@code service-walker-substrate-absorption.md}); per the
 * "documentation names only live tests/code" principle this seal carries only arms a producer
 * actually instantiates.
 *
 * <p>Subsequent walker slices (condition, tableMethod, externalField) each add their own sibling
 * sub-seal alongside this one, rather than piling typed arms under a single flat
 * {@link Rejection.AuthorError.Structural}.
 */
public sealed interface ServiceMethodCallError extends Rejection.AuthorError permits
    ServiceMethodCallError.MultipleDslContextSlots,
    ServiceMethodCallError.ParameterUnbindable
{
    /** LSP wire code under the {@code graphitron.service-method-call.} namespace. */
    String lspCode();

    @Override default Rejection prefixedWith(String prefix) {
        // Typed arms keep their structural components; prefixing is a no-op concerning
        // structure. The orchestrator's renderer prepends author-facing prose via
        // diagnostic projection, not via Rejection#prefixedWith.
        return this;
    }

    /** Round identifier: which parameter list raised the multi-DSL-slot violation. */
    enum Round { CTOR, METHOD }

    record MultipleDslContextSlots(String className, Round round) implements ServiceMethodCallError {
        @Override public String message() {
            return "service '" + className + "' has more than one DSLContext slot in its "
                + (round == Round.CTOR ? "constructor" : "method")
                + "; the emitter would alias both to the same 'dsl' local";
        }
        @Override public String lspCode() { return "graphitron.service-method-call.multiple-dsl-context-slots"; }
    }

    record ParameterUnbindable(
        String paramName,
        List<String> availableArgs,
        String suggestion
    ) implements ServiceMethodCallError {
        public ParameterUnbindable { availableArgs = List.copyOf(availableArgs); }
        @Override public String message() {
            var sb = new StringBuilder("parameter '").append(paramName)
                .append("' does not match any GraphQL argument, declared context key, "
                    + "or DSLContext slot");
            if (!availableArgs.isEmpty()) {
                sb.append("; available arguments: ")
                  .append(availableArgs.stream().collect(Collectors.joining(", ")));
            }
            if (suggestion != null && !suggestion.isEmpty()) {
                sb.append("; did you mean '").append(suggestion).append("'?");
            }
            return sb.toString();
        }
        @Override public String lspCode() { return "graphitron.service-method-call.parameter-unbindable"; }
    }
}
