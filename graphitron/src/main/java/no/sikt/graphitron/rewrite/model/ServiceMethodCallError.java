package no.sikt.graphitron.rewrite.model;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Sealed sub-family of {@link Rejection.AuthorError} for {@code ServiceMethodCallWalker}
 *. Each typed arm carries the structural data its diagnostic message and LSP
 * {@code relatedInformation} need; downstream tooling switches on the arm rather than parsing
 * prose.
 *
 * <p>The arm-to-code mapping (see {@code roadmap/methodcall-walker-carrier.md}'s
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
    ServiceMethodCallError.ParameterUnbindable,
    ServiceMethodCallError.InstanceHolderUnconstructible,
    ServiceMethodCallError.ArgumentParameterMismatch,
    ServiceMethodCallError.DtoSourcesUnsupported,
    ServiceMethodCallError.UnrecognizedSourcesType
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

    /** Which reason an instance {@code @service} holder is unconstructible. */
    enum HolderProblem { ABSTRACT_OR_INTERFACE, NO_BINDABLE_CTOR }

    /**
     * An instance {@code @service} method's enclosing class cannot be used as a holder: it is
     * abstract / an interface, or it exposes no public constructor whose parameters are each
     * bindable from a {@code DSLContext} slot or a declared context argument (R256 relaxed the
     * legacy {@code (DSLContext)}-only rule). Carries the class/method coordinate, the class's
     * simple name (for the fix hint), and which {@link HolderProblem} applies.
     */
    record InstanceHolderUnconstructible(
        String className,
        String methodName,
        String classSimpleName,
        HolderProblem problem
    ) implements ServiceMethodCallError {
        @Override public String message() {
            return switch (problem) {
                case ABSTRACT_OR_INTERFACE -> "method '" + methodName + "' in class '" + className
                    + "' is an instance method, but its enclosing class is abstract or an interface"
                    + " — make the method static, or move it to a concrete class with a public"
                    + " constructor whose parameters are each a DSLContext or a declared context argument";
                case NO_BINDABLE_CTOR -> "method '" + methodName + "' in class '" + className
                    + "' is an instance method but the class has no public constructor whose"
                    + " parameters are each a DSLContext or a declared context argument — add e.g. `public "
                    + classSimpleName + "(DSLContext ctx)`, or make the method static";
            };
        }
        @Override public String lspCode() { return "graphitron.service-method-call.instance-holder-unconstructible"; }
    }

    /**
     * A Java parameter did not match any GraphQL argument or context key on the field. Carries the
     * parameter and method names, the available GraphQL argument names and context keys (so the
     * author can spot a typo), and a pre-rendered {@code suggestion} hint (the rename / argMapping /
     * dot-path guidance the classifier computed). Subsumes the prose the legacy
     * {@code Rejection.structural} arm produced at {@code ServiceCatalog}.
     */
    record ArgumentParameterMismatch(
        String paramName,
        String methodName,
        List<String> availableArgs,
        List<String> availableContextKeys,
        String suggestion
    ) implements ServiceMethodCallError {
        public ArgumentParameterMismatch {
            availableArgs = List.copyOf(availableArgs);
            availableContextKeys = List.copyOf(availableContextKeys);
        }
        private static String formatNameSet(List<String> names) {
            return names.isEmpty()
                ? "(none)"
                : names.stream().sorted().collect(Collectors.joining(", ", "[", "]"));
        }
        @Override public String message() {
            return "parameter '" + paramName + "' in method '" + methodName
                + "' does not match any GraphQL argument or context key on this field"
                + " — available GraphQL arguments: " + formatNameSet(availableArgs)
                + "; available context keys: " + formatNameSet(availableContextKeys)
                + (suggestion == null ? "" : suggestion);
        }
        @Override public String lspCode() { return "graphitron.service-method-call.argument-parameter-mismatch"; }
    }

    /**
     * A {@code @service} SOURCES parameter is a {@code List<DTO>} / {@code Set<DTO>} whose element
     * is not backed by a jOOQ {@code TableRecord} (free-form DTO sources are unsupported). Carries
     * the parameter and method names plus the classifier's {@code reason} (the {@code @sourceRow}
     * hint).
     */
    record DtoSourcesUnsupported(
        String paramName,
        String methodName,
        String reason
    ) implements ServiceMethodCallError {
        @Override public String message() {
            return "parameter '" + paramName + "' in method '" + methodName + "': " + reason;
        }
        @Override public String lspCode() { return "graphitron.service-method-call.dto-sources-unsupported"; }
    }

    /**
     * A {@code @service} parameter looks like a SOURCES batch shape but its element type is not one
     * the classifier recognises ({@code RowN} / {@code RecordN} / {@code TableRecord}). Carries the
     * parameter and method names plus the unrecognised Java type name.
     */
    record UnrecognizedSourcesType(
        String paramName,
        String methodName,
        String typeName
    ) implements ServiceMethodCallError {
        @Override public String message() {
            return "parameter '" + paramName + "' in method '" + methodName
                + "' has an unrecognized sources type: '" + typeName + "'";
        }
        @Override public String lspCode() { return "graphitron.service-method-call.unrecognized-sources-type"; }
    }
}
