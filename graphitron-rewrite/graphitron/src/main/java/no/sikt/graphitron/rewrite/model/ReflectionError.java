package no.sikt.graphitron.rewrite.model;

import java.util.List;
import java.util.stream.Collectors;

/**
 * R256 — sealed sub-family of {@link Rejection.AuthorError} for the <em>reflection-intrinsic</em>
 * failures shared across {@code ServiceCatalog}'s three reflect helpers
 * ({@code reflectServiceMethod}, {@code reflectTableMethod}, {@code reflectExternalField}). These
 * failures are not {@code @service}-specific: a class that cannot be loaded, a method whose return
 * type does not match the field's declared type, a class compiled without {@code -parameters}, or
 * an overloaded method name are all properties of the reflected Java method regardless of which
 * directive references it. Per the spec's shared-vs-service partition, these arms live in their own
 * sub-seal under {@code graphitron.reflect.} rather than forcing
 * {@link ServiceMethodCallError} to carry a {@code @tableMethod} failure.
 *
 * <p>Sibling to {@link ServiceMethodCallError} / {@link UpdateRowsError} / {@link DeleteRowsError}:
 * each typed arm carries the structural data its diagnostic message needs and a stable
 * {@link #lspCode()} so the LSP {@code Diagnostic} projector can read the wire code without a
 * separate dispatch table (see {@code Diagnostics.lspCodeOf}). Adding a permit here lands with a
 * {@code RejectionSeverityCoverageTest.sampleFor} branch and a {@code typed-rejection.adoc}
 * paragraph (both drift-guarded).
 */
public sealed interface ReflectionError extends Rejection.AuthorError permits
    ReflectionError.ClassNotLoaded,
    ReflectionError.ReturnTypeMismatch,
    ReflectionError.ParameterNamesMissing,
    ReflectionError.AmbiguousMethod
{
    /** LSP wire code under the {@code graphitron.reflect.} namespace. */
    String lspCode();

    @Override default Rejection prefixedWith(String prefix) {
        // Typed arms keep their structural components; prefixing is a no-op concerning structure.
        // The orchestrator's renderer prepends caller-specific prose via diagnostic projection.
        return this;
    }

    /**
     * Which reflect helper produced a {@link ReturnTypeMismatch}; specialises the message prose
     * (the {@code @service} return-type rule vs. the {@code @tableMethod} generated-table-class
     * rule) without splitting the shared arm into two.
     */
    enum ReturnContext { SERVICE, TABLE_METHOD }

    /**
     * The referenced class could not be loaded through the codegen classloader (a
     * {@link ClassNotFoundException} at the reflect site). Carries the binary class name the
     * author wrote.
     */
    record ClassNotLoaded(String className) implements ReflectionError {
        @Override public String message() {
            return "class '" + className + "' could not be loaded";
        }
        @Override public String lspCode() { return "graphitron.reflect.class-not-loaded"; }
    }

    /**
     * The reflected method's return type does not equal the type the field's declared return
     * requires. Carries the class/method coordinate, the expected vs. actual type rendered in the
     * simple form the message surfaces, and the {@link ReturnContext} that selects the prose.
     */
    record ReturnTypeMismatch(
        String className,
        String methodName,
        String expectedTypeSimple,
        String actualTypeSimple,
        ReturnContext context
    ) implements ReflectionError {
        @Override public String message() {
            return switch (context) {
                case SERVICE -> "method '" + methodName + "' in class '" + className
                    + "' must return '" + expectedTypeSimple
                    + "' to match the field's declared return type — got '" + actualTypeSimple + "'";
                case TABLE_METHOD -> "method '" + methodName + "' in class '" + className
                    + "' must return the generated jOOQ table class '" + expectedTypeSimple
                    + "' for @tableMethod with a @table-bound return type — got '"
                    + actualTypeSimple + "'";
            };
        }
        @Override public String lspCode() { return "graphitron.reflect.return-type-mismatch"; }
    }

    /**
     * The class was compiled without {@code -parameters}, so a parameter that needs its name to
     * bind to a GraphQL argument or context key has no name to match. Carries the class/method
     * coordinate; the {@code -parameters} fix is named in the message.
     */
    record ParameterNamesMissing(String className, String methodName) implements ReflectionError {
        @Override public String message() {
            return "parameter names not available for method '" + methodName + "' in class '"
                + className + "' — compile with -parameters flag (see warning above for instructions)";
        }
        @Override public String lspCode() { return "graphitron.reflect.parameter-names-missing"; }
    }

    /**
     * More than one declared method shares the referenced name. The reflect helpers previously
     * took {@code methods.get(0)} (JVM declaration order) silently; this arm makes the ambiguity a
     * typed failure. Carries the class/method coordinate and the parameter arities of every
     * same-name declaration so the author can see which overloads collide.
     */
    record AmbiguousMethod(
        String className,
        String methodName,
        List<Integer> candidateArities
    ) implements ReflectionError {
        public AmbiguousMethod { candidateArities = List.copyOf(candidateArities); }
        @Override public String message() {
            return "method '" + methodName + "' in class '" + className + "' is overloaded ("
                + candidateArities.size() + " declarations with parameter counts "
                + candidateArities.stream().map(String::valueOf).collect(Collectors.joining(", ", "[", "]"))
                + ") — graphitron cannot pick one; rename or remove overloads so exactly one method"
                + " named '" + methodName + "' exists";
        }
        @Override public String lspCode() { return "graphitron.reflect.ambiguous-method"; }
    }
}
