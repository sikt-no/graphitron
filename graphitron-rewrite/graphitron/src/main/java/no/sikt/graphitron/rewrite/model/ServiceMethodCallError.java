package no.sikt.graphitron.rewrite.model;

import no.sikt.graphitron.javapoet.TypeName;

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
 * <p>Subsequent walker slices (condition, tableMethod, externalField) each add their own sibling
 * sub-seal alongside this one, rather than piling typed arms under a single flat
 * {@link Rejection.AuthorError.Structural}.
 */
public sealed interface ServiceMethodCallError extends Rejection.AuthorError permits
    ServiceMethodCallError.ClassNotLoadable,
    ServiceMethodCallError.AmbiguousMethod,
    ServiceMethodCallError.ReturnTypeMismatch,
    ServiceMethodCallError.InstanceHolderMissingCtor,
    ServiceMethodCallError.CtorParamFromArg,
    ServiceMethodCallError.MultipleDslContextSlots,
    ServiceMethodCallError.ParameterNamesMissing,
    ServiceMethodCallError.ParameterUnbindable,
    ServiceMethodCallError.InputBeanShape,
    ServiceMethodCallError.ArgMappingParseError,
    ServiceMethodCallError.ArgMappingUnknownArg,
    ServiceMethodCallError.ArgMappingPathRejected
{
    /** LSP wire code under the {@code graphitron.service-method-call.} namespace. */
    String lspCode();

    @Override default Rejection prefixedWith(String prefix) {
        // Typed arms keep their structural components; prefixing is a no-op concerning
        // structure. The orchestrator's renderer prepends author-facing prose via
        // diagnostic projection, not via Rejection#prefixedWith.
        return this;
    }

    record ClassNotLoadable(String className) implements ServiceMethodCallError {
        @Override public String message() {
            return "service class '" + className + "' is not loadable on the codegen classloader";
        }
        @Override public String lspCode() { return "graphitron.service-method-call.class-not-loadable"; }
    }

    record AmbiguousMethod(
        String className,
        String methodName,
        List<String> candidateSignatures
    ) implements ServiceMethodCallError {
        public AmbiguousMethod { candidateSignatures = List.copyOf(candidateSignatures); }
        @Override public String message() {
            return "service method '" + className + "." + methodName
                + "' is ambiguous after arity match; candidates:\n  - "
                + String.join("\n  - ", candidateSignatures);
        }
        @Override public String lspCode() { return "graphitron.service-method-call.ambiguous-method"; }
    }

    record ReturnTypeMismatch(TypeName expected, TypeName actual) implements ServiceMethodCallError {
        @Override public String message() {
            return "service method return type '" + actual + "' is not shape-compatible with "
                + "the SDL field's classified return shape '" + expected + "'";
        }
        @Override public String lspCode() { return "graphitron.service-method-call.return-type-mismatch"; }
    }

    record InstanceHolderMissingCtor(String className, String attemptedSignature) implements ServiceMethodCallError {
        @Override public String message() {
            return "service instance holder '" + className + "' has no public constructor matching "
                + attemptedSignature + "; provide a constructor whose parameter slots resolve to "
                + "DSLContext / declared context arguments";
        }
        @Override public String lspCode() { return "graphitron.service-method-call.instance-holder-missing-ctor"; }
    }

    record CtorParamFromArg(String className, String paramName) implements ServiceMethodCallError {
        @Override public String message() {
            return "constructor parameter '" + paramName + "' on '" + className
                + "' resolves to a GraphQL field argument; ctor slots may only bind DSLContext "
                + "or declared context arguments";
        }
        @Override public String lspCode() { return "graphitron.service-method-call.ctor-param-from-arg"; }
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

    record ParameterNamesMissing(String className, String methodName) implements ServiceMethodCallError {
        @Override public String message() {
            return "service class '" + className + "' was compiled without -parameters; method '"
                + methodName + "'s parameter names are erased and cannot be resolved against "
                + "@argMapping or context-argument names";
        }
        @Override public String lspCode() { return "graphitron.service-method-call.parameter-names-missing"; }
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

    record InputBeanShape(String beanClass, String reason) implements ServiceMethodCallError {
        @Override public String message() {
            return "input bean '" + beanClass + "' shape rejected: " + reason;
        }
        @Override public String lspCode() { return "graphitron.service-method-call.input-bean-shape"; }
    }

    record ArgMappingParseError(String rawMapping, String parserDetail) implements ServiceMethodCallError {
        @Override public String message() {
            return "@service(argMapping: '" + rawMapping + "') is not parseable: " + parserDetail;
        }
        @Override public String lspCode() { return "graphitron.service-method-call.arg-mapping-parse-error"; }
    }

    record ArgMappingUnknownArg(
        String javaParam,
        String refHead,
        List<String> availableArgs
    ) implements ServiceMethodCallError {
        public ArgMappingUnknownArg { availableArgs = List.copyOf(availableArgs); }
        @Override public String message() {
            return "@argMapping for parameter '" + javaParam + "' references unknown argument '"
                + refHead + "'; available arguments: "
                + availableArgs.stream().collect(Collectors.joining(", "));
        }
        @Override public String lspCode() { return "graphitron.service-method-call.arg-mapping-unknown-arg"; }
    }

    record ArgMappingPathRejected(
        String javaParam,
        String offendingSegment,
        String reason
    ) implements ServiceMethodCallError {
        @Override public String message() {
            return "@argMapping path for parameter '" + javaParam + "' rejected at segment '"
                + offendingSegment + "': " + reason;
        }
        @Override public String lspCode() { return "graphitron.service-method-call.arg-mapping-path-rejected"; }
    }
}
