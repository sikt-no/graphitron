package no.sikt.graphitron.model.diagnostics;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Sealed sub-family of {@link Rejection.AuthorError} for the <em>reflection-intrinsic</em>
 * failures shared across the paths that reflect a developer method: the {@code @service} phases
 * ({@code ServiceCatalog.decodeServiceMethod} and {@code bindServiceMethod}, with
 * {@link ReturnTypeMismatch} raised a phase above them by
 * {@code ServiceDirectiveResolver}'s classify step), {@code ServiceCatalog.reflectTableMethod},
 * and {@code reflectExternalField}. These failures are not {@code @service}-specific: a class that
 * cannot be loaded, a method whose return type does not match the field's declared type, a class
 * compiled without {@code -parameters}, or an overloaded method name are all properties of the
 * reflected Java method regardless of which directive references it. Per the spec's
 * shared-vs-service partition, these arms live in their own
 * sub-seal under {@code graphitron.reflect.} rather than forcing
 * {@link ServiceMethodCallError} to carry a reflection failure.
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
    ReflectionError.AmbiguousMethod,
    ReflectionError.SeamParameterMissing,
    ReflectionError.SeamCandidateAmbiguous,
    ReflectionError.HookNotStatic,
    ReflectionError.HookThrowsChecked,
    ReflectionError.HandleTypeMismatch
{
    /** LSP wire code under the {@code graphitron.reflect.} namespace. */
    String lspCode();

    @Override default Rejection prefixedWith(String prefix) {
        // Typed arms keep their structural components; prefixing is a no-op concerning structure.
        // The orchestrator's renderer prepends caller-specific prose via diagnostic projection.
        return this;
    }

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
     * simple form the message surfaces.
     */
    record ReturnTypeMismatch(
        String className,
        String methodName,
        String expectedTypeSimple,
        String actualTypeSimple
    ) implements ReflectionError {
        @Override public String message() {
            return "method '" + methodName + "' in class '" + className
                + "' must return '" + expectedTypeSimple
                + "' to match the field's declared return type — got '" + actualTypeSimple + "'";
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
     * The referenced name does not denote one call shape. Two populations reach this arm, told
     * apart by {@link Ambiguity} rather than by prose composed at the detection site: a coordinate
     * that admits exactly one declaration found several ({@link Ambiguity.NameShared}), or the
     * {@code @condition} path found several that disagree on the binding shape, on the axis the
     * remaining arms name.
     *
     * <p>Carries the class/method coordinate and every same-named declaration's rendered signature,
     * so a consumer (the LSP included) sees the overload set the author actually wrote without
     * parsing prose. Signatures rather than bare arities: an arity list cannot show a set that
     * collides at one arity, which is the whole of the {@code @condition} population.
     */
    record AmbiguousMethod(
        String className,
        String methodName,
        List<String> candidateSignatures,
        Ambiguity ambiguity
    ) implements ReflectionError {
        public AmbiguousMethod {
            candidateSignatures = List.copyOf(candidateSignatures);
            java.util.Objects.requireNonNull(ambiguity, "ambiguity");
        }
        @Override public String message() {
            return "method '" + methodName + "' in class '" + className + "' has "
                + candidateSignatures.size() + " declarations ("
                + String.join("; ", candidateSignatures) + "): " + guidance();
        }
        @Override public String lspCode() { return "graphitron.reflect.ambiguous-method"; }

        /** The remedy, rendered from the typed axis rather than assembled by the detection site. */
        private String guidance() {
            String shapeRule = " On a @condition the declarations of one name may differ only in"
                + " their jOOQ Table parameters, so make the rest of the signature identical across"
                + " them, or collapse the set to a single method taking org.jooq.Table<?>.";
            return switch (ambiguity) {
                case Ambiguity.NameShared ignored ->
                    "graphitron cannot pick one; rename or remove overloads so exactly one method"
                        + " named '" + methodName + "' exists";
                case Ambiguity.StaticModifier ignored ->
                    "they disagree on the static modifier." + shapeRule;
                case Ambiguity.ReturnType ignored ->
                    "they disagree on the return type." + shapeRule;
                case Ambiguity.ParameterCount ignored ->
                    "they declare different numbers of parameters." + shapeRule;
                case Ambiguity.ThrowsClause ignored ->
                    "they disagree on the declared throws clause." + shapeRule;
                case Ambiguity.ParameterPosition p ->
                    "parameter " + p.position() + " is neither a jOOQ Table parameter in every"
                        + " declaration nor identical in name and declared type across them."
                        + shapeRule;
            };
        }

        /**
         * Why the shared name resolved to no single call shape. A typed discriminant threaded as an
         * explicit constructor input, the way {@code ServiceCatalog.SeamFilter} is, so
         * {@link #message()} switches on it and no caller pre-renders a hint. The five shape arms
         * are exactly the axes the {@code @condition} admission rule demands agreement on, which is
         * what lets the agreed-shape value name the axis at the moment its construction fails.
         */
        public sealed interface Ambiguity {

            /**
             * A coordinate admitting exactly one declaration found several. The {@code @service},
             * {@code @externalField}, and enum-mapping paths resolve by name alone, so any second
             * declaration lands here.
             */
            record NameShared() implements Ambiguity {}

            /** Some declarations are {@code static} and some are not. */
            record StaticModifier() implements Ambiguity {}

            /** The declarations return different types. */
            record ReturnType() implements Ambiguity {}

            /** The declarations take different numbers of parameters. */
            record ParameterCount() implements Ambiguity {}

            /** The declarations declare different checked exceptions. */
            record ThrowsClause() implements Ambiguity {}

            /**
             * One position is neither {@code Table}-assignable in every declaration nor identical
             * in name and declared type across them. {@code position} is zero-based, as the
             * generator counts parameters everywhere else.
             */
            record ParameterPosition(int position) implements Ambiguity {}
        }
    }

    /**
     * No declaration of the referenced session-hook method carries exactly one seam parameter
     * ({@code org.jooq.Configuration} or {@code java.sql.Connection}). The seam rule is also the
     * overload selector, so this covers both a single method with zero (or several) seam-typed
     * parameters and an overload set where no candidate qualifies. Carries every same-named
     * candidate's rendered parameter list so the author can see what was inspected.
     */
    record SeamParameterMissing(
        String className,
        String methodName,
        List<String> candidateSignatures
    ) implements ReflectionError {
        public SeamParameterMissing { candidateSignatures = List.copyOf(candidateSignatures); }
        @Override public String message() {
            return "method '" + methodName + "' in class '" + className + "' has no declaration with"
                + " exactly one seam parameter (org.jooq.Configuration or java.sql.Connection) —"
                + " a session hook declares exactly one seam parameter anywhere in its parameter list;"
                + " candidates inspected: "
                + candidateSignatures.stream().collect(Collectors.joining("; ", "[", "]"));
        }
        @Override public String lspCode() { return "graphitron.reflect.seam-parameter-missing"; }
    }

    /**
     * More than one same-named declaration of the referenced session-hook method carries a seam
     * parameter, so the seam rule cannot select an overload. Carries each qualifying candidate's
     * rendered parameter list.
     */
    record SeamCandidateAmbiguous(
        String className,
        String methodName,
        List<String> candidateSignatures
    ) implements ReflectionError {
        public SeamCandidateAmbiguous { candidateSignatures = List.copyOf(candidateSignatures); }
        @Override public String message() {
            return "method '" + methodName + "' in class '" + className + "' has "
                + candidateSignatures.size() + " declarations carrying a seam parameter"
                + " (org.jooq.Configuration or java.sql.Connection) — graphitron cannot pick one;"
                + " qualifying candidates: "
                + candidateSignatures.stream().collect(Collectors.joining("; ", "[", "]"))
                + ". Rename or remove overloads so exactly one seam-carrying method named '"
                + methodName + "' exists";
        }
        @Override public String lspCode() { return "graphitron.reflect.seam-candidate-ambiguous"; }
    }

    /**
     * The referenced session-hook method is not {@code public static}. The generated hook class
     * emits a direct {@code ClassName.method(...)} call, so instance (or non-public) hook methods
     * are unsupported by construction.
     */
    record HookNotStatic(String className, String methodName) implements ReflectionError {
        @Override public String message() {
            return "session-hook method '" + methodName + "' in class '" + className
                + "' must be public static — the generated hook calls 'ClassName.method(...)' directly";
        }
        @Override public String lspCode() { return "graphitron.reflect.hook-not-static"; }
    }

    /**
     * The referenced session-hook method declares a checked exception. A session hook has no
     * field coordinate and no {@code @error} channel to route through, so a declared checked
     * exception has nowhere to land; unchecked exceptions propagate into the fail-closed
     * connection eviction instead.
     */
    record HookThrowsChecked(
        String className,
        String methodName,
        List<String> declaredExceptions
    ) implements ReflectionError {
        public HookThrowsChecked { declaredExceptions = List.copyOf(declaredExceptions); }
        @Override public String message() {
            return "session-hook method '" + methodName + "' in class '" + className
                + "' declares checked exception(s) "
                + declaredExceptions.stream().collect(Collectors.joining(", ", "[", "]"))
                + " — a session hook cannot declare checked exceptions (there is no error channel"
                + " to route them through); catch and wrap in an unchecked exception, which fails"
                + " the request and evicts the connection";
        }
        @Override public String lspCode() { return "graphitron.reflect.hook-throws-checked"; }
    }

    /**
     * The {@code <unmount>} method's non-seam parameter does not accept the {@code <mount>}
     * method's return type. Both are the consumer's own declarations, so the message names both
     * real signatures; an unmount with no non-seam parameter is always legal (the handle is
     * simply not passed) and never reaches this arm.
     */
    record HandleTypeMismatch(
        String mountClassName,
        String mountMethodName,
        String handleTypeSimple,
        String unmountClassName,
        String unmountMethodName,
        String unmountParamTypeSimple
    ) implements ReflectionError {
        @Override public String message() {
            return "<unmount> method '" + unmountMethodName + "' in class '" + unmountClassName
                + "' takes '" + unmountParamTypeSimple + "', but <mount> method '" + mountMethodName
                + "' in class '" + mountClassName + "' returns '" + handleTypeSimple
                + "' — the unmount's non-seam parameter must be exactly the mount's handle type"
                + " (or the unmount takes only the seam parameter, discarding the handle)";
        }
        @Override public String lspCode() { return "graphitron.reflect.handle-type-mismatch"; }
    }
}
