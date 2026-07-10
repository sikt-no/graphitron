package no.sikt.graphitron.rewrite.model;

import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.TypeName;

/**
 * Outcome of resolving a GraphQL scalar to its Java type + {@code GraphQLScalarType} constant.
 * Sealed so consumers (validator, LSP diagnostic builder, emitters) switch exhaustively;
 * per-arm payloads carry the data each consumer actually needs rather than collapsing into a
 * single prose {@code reason} string.
 *
 * <p>{@link Successful} is the intermediate sub-interface that admits both
 * {@link Resolved} (consumer's scalar is reachable via a {@code public static final}
 * {@code GraphQLScalarType} constant) and {@link Synthesised} (no referenceable constant;
 * the generator inlines a {@code GraphQLScalarType.newScalar()...build()} call at emit time,
 * borrowing a coercing from another constant). Both arms share a {@link Successful#javaType()}
 * accessor so consumers that only read the Java type stay variant-agnostic.
 *
 * <p>{@link Resolved} carries the JavaPoet {@link TypeName} for the recovered Java type
 * (input-record components, service params, {@code Field<X>} projections bind to it), plus
 * the owner class and field name of the {@code public static final GraphQLScalarType}
 * constant the consumer pointed at (for the synthesized schema's {@code .additionalType(...)}
 * registration).
 *
 * <p>{@link Synthesised} carries the same Java type plus the SDL name the scalar should
 * register under and a {@code (coercingSourceOwner, coercingSourceField)} pair pointing at
 * a {@code GraphQLScalarType} constant whose {@code getCoercing()} the generator borrows
 * for the synthesised scalar. Two cases reach this arm, both characterised by "the scalar must
 * register under its SDL name, and that name is not the name of any constant we can hand to
 * {@code additionalType} directly":
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
 * <p>Each {@link Rejected} arm names a distinct misconfiguration class; LSP fix-its in a
 * later phase will switch on the variant to offer per-arm hints (extract anonymous class,
 * declare concrete type parameters, did-you-mean against the consumer's compile classpath).
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
        permits Rejected.ClassNotFound, Rejected.FieldNotFound, Rejected.FieldNotAccessible,
                Rejected.NullAtCodegen, Rejected.NotAScalarType, Rejected.CoercingErased {

        record ClassNotFound(String fqn) implements Rejected {}

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
