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
    ReflectionError.ClassUnlinkable,
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
     * The referenced class was found but could not be linked: loading it, or materialising its
     * declared methods, threw a {@link LinkageError}. Materialising a class's method table resolves
     * the parameter and return types of every declared method, not only the one graphitron picks,
     * so a sibling helper's signature is enough to reach this arm. Carries the binary class name
     * and the decoded {@link Cause}, which is what {@link #message()} renders the remedy from.
     */
    record ClassUnlinkable(String className, Cause cause) implements ReflectionError {
        public ClassUnlinkable {
            java.util.Objects.requireNonNull(cause, "cause");
        }
        @Override public String message() {
            return "class '" + className + "' could not be linked: " + cause.render();
        }
        @Override public String lspCode() { return "graphitron.reflect.class-unlinkable"; }

        /**
         * Why the class could not be linked, decoded once from the {@link LinkageError} at the
         * catch, following the {@link AmbiguousMethod.Ambiguity} precedent: the detection site
         * hands over the error, never prose.
         */
        public sealed interface Cause {

            /**
             * A type the class's signatures reach and a superclass (or a class it accesses) in the
             * same package came from two classloaders, so the JVM refused the package-private
             * access between them. In practice a commercial-edition-only jOOQ type: graphitron
             * loads consumer classes with its own plugin classloader as parent, so every
             * {@code org.jooq} class that classloader has comes from graphitron's jOOQ and only
             * the edition-only ones from the consumer's.
             */
            record SplitPackage(String type, String packageName) implements Cause {}

            /** A signature names a type that is not on the codegen classpath at all. */
            record TypeMissing(String type) implements Cause {}

            /** Any other {@link LinkageError}; carries the error's own text. */
            record Unrecognised(String detail) implements Cause {}

            /** The remedy, rendered from the typed cause. */
            default String render() {
                return switch (this) {
                    case SplitPackage s -> "a method signature reaches '" + s.type()
                        + "', which graphitron cannot load: it loads your classes against its own "
                        + ownCopyOf(s.packageName()) + ", which does not carry that type, so package '"
                        + s.packageName() + "' ends up split across two classloaders. Keep methods"
                        + " whose signatures name it off the classes graphitron reflects, for example"
                        + " by moving them into a nested or separate class";
                    case TypeMissing t -> "a method signature names '" + t.type() + "', which"
                        + " is not on the module's compile classpath; declare the dependency that"
                        + " provides it at compile (or provided) scope";
                    case Unrecognised u -> u.detail();
                };
            }

            private static String ownCopyOf(String packageName) {
                return packageName.equals("org.jooq") || packageName.startsWith("org.jooq.")
                    ? "jOOQ (org.jooq:jooq:" + org.jooq.Constants.VERSION + ")"
                    : "copy of that package";
            }

            /**
             * Decodes {@code error} into a cause. An {@link IllegalAccessError} between two classes
             * of one package is a {@link SplitPackage}; the type named is the one the consumer's
             * classloader defined, which is the subclass in the superclass form of the message and
             * the accessing class in the member-access form. A {@link NoClassDefFoundError} naming
             * a type is a {@link TypeMissing}. Everything else is {@link Unrecognised}.
             */
            static Cause of(LinkageError error) {
                String message = error.getMessage() == null ? "" : error.getMessage();
                if (error instanceof IllegalAccessError) {
                    var superAccess = java.util.regex.Pattern.compile(
                        "class (\\S+) cannot access its (?:abstract )?super(?:class|interface) (\\S+)")
                        .matcher(message);
                    if (superAccess.find()
                            && samePackage(superAccess.group(1), superAccess.group(2))) {
                        return new SplitPackage(superAccess.group(1), packageOf(superAccess.group(1)));
                    }
                    var memberAccess = java.util.regex.Pattern.compile(
                        "(?:failed|tried) to access class (\\S+) from class (\\S+)")
                        .matcher(message);
                    if (memberAccess.find()
                            && samePackage(memberAccess.group(1), memberAccess.group(2))) {
                        return new SplitPackage(memberAccess.group(2), packageOf(memberAccess.group(2)));
                    }
                }
                if (error instanceof NoClassDefFoundError && message.matches("[\\w$/.]+")) {
                    return new TypeMissing(message.replace('/', '.'));
                }
                return new Unrecognised(error.toString());
            }

            private static boolean samePackage(String a, String b) {
                return !packageOf(a).isEmpty() && packageOf(a).equals(packageOf(b));
            }

            private static String packageOf(String className) {
                int dot = className.lastIndexOf('.');
                return dot < 0 ? "" : className.substring(0, dot);
            }
        }
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
}
