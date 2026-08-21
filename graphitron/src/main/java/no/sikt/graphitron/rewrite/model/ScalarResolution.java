package no.sikt.graphitron.rewrite.model;

import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.TypeName;

/**
 * Outcome of resolving a GraphQL scalar to its Java type + {@code GraphQLScalarType} constant.
 * Sealed so consumers switch exhaustively; per-arm payloads carry the data each consumer
 * actually needs rather than collapsing into a single prose {@code reason} string.
 *
 * <p>{@link Successful} admits {@link Resolved} (the consumer's scalar is reachable via a
 * {@code public static final GraphQLScalarType} constant) and {@link Synthesised} (no
 * referenceable constant; the generator inlines a {@code GraphQLScalarType.newScalar()...build()}
 * call at emit time, borrowing a coercing from another constant). Both arms share
 * {@link Successful#javaType()} so consumers that only read the Java type stay variant-agnostic.
 *
 * <p>{@link Resolved}'s {@link TypeName} is what input-record components, service params and
 * {@code Field<X>} projections bind to; its owner class and field name feed the synthesized
 * schema's {@code .additionalType(...)} registration.
 *
 * <p>{@link Synthesised} adds the SDL name the scalar registers under and a
 * {@code (coercingSourceOwner, coercingSourceField)} pair naming the {@code GraphQLScalarType}
 * constant whose {@code getCoercing()} the generator borrows. Two cases reach this arm, both
 * characterised by "the scalar must register under its SDL name, and that name is not the name
 * of any constant we can hand to {@code additionalType} directly":
 *
 * <ul>
 *   <li>Federation-namespace scalars ({@code federation__FieldSet}, etc.) that have no
 *       public-static-final form on the consumer classpath at all; the coercing is borrowed from
 *       {@code _Any.type}.</li>
 *   <li>Aliasing {@code @scalarType} declarations whose SDL name differs from the intrinsic
 *       {@code getName()} of the constant they resolve to: a
 *       {@code scalar LocalDate @scalarType(scalar: "...ExtendedScalars.Date")} (constant named
 *       {@code Date}, SDL name {@code LocalDate}). Registering the constant directly would
 *       register the scalar under the constant's name, leaving every {@code typeRef(sdlName)}
 *       unresolved at schema build; the coercing is borrowed from the resolved constant itself.</li>
 * </ul>
 *
 * <p>Registering under the SDL name keeps directive-argument {@code GraphQLTypeReference}s and
 * field type references resolvable at schema build.
 *
 * <p>Each {@link Rejected} arm names a distinct misconfiguration class.
 */
public sealed interface ScalarResolution permits ScalarResolution.Successful, ScalarResolution.Rejected {

    sealed interface Successful extends ScalarResolution
        permits Resolved, Synthesised {
        TypeName javaType();
    }

    record Resolved(
        TypeName javaType,
        ClassName scalarConstantOwner,
        String scalarConstantField
    ) implements Successful {}

    record Synthesised(
        TypeName javaType,
        String sdlName,
        ClassName coercingSourceOwner,
        String coercingSourceField
    ) implements Successful {}

    sealed interface Rejected extends ScalarResolution
        permits Rejected.ClassNotFound, Rejected.UndeclaredClass, Rejected.FieldNotFound,
                Rejected.FieldNotAccessible, Rejected.NullAtCodegen, Rejected.NotAScalarType,
                Rejected.CoercingErased {

        record ClassNotFound(String fqn) implements Rejected {}

        /**
         * The named class may well resolve at codegen (through a transitive dependency or the
         * plugin's own classpath), but no classpath entry this module may name carries it, so the
         * nameability rule rejects it before any load is attempted. {@code reason} is the
         * canonical sentence from {@code ClasspathNameability}, naming the carrying coordinate
         * where one was found.
         */
        record UndeclaredClass(String fqn, String reason) implements Rejected {}

        record FieldNotFound(String className, String fieldName) implements Rejected {}

        record FieldNotAccessible(
            String className, String fieldName,
            boolean isPublic, boolean isStatic
        ) implements Rejected {}

        /**
         * A {@code public static} field that evaluates to {@code null} at codegen,
         * an initialization side-effect the consumer must own.
         */
        record NullAtCodegen(String className, String fieldName) implements Rejected {}

        /** Field is public-static-non-null but not assignable to {@code GraphQLScalarType}. */
        record NotAScalarType(
            String className, String fieldName, String actualTypeFqn
        ) implements Rejected {}

        /**
         * The Coercing's {@code I} type parameter erases to {@code Object}.
         * {@link #declarationKind} tells the user-facing message which fix to suggest
         * (extract anonymous class, declare concrete type parameters, etc.).
         */
        record CoercingErased(
            String coercingClass, CoercingDeclarationKind declarationKind
        ) implements Rejected {}
    }
}
