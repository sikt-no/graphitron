package no.sikt.graphitron.rewrite;

import graphql.schema.GraphQLInputType;
import graphql.schema.GraphQLList;
import graphql.schema.GraphQLNonNull;
import graphql.schema.GraphQLScalarType;
import graphql.schema.GraphQLType;
import graphql.schema.GraphQLTypeUtil;
import no.sikt.graphitron.javapoet.TypeName;
import no.sikt.graphitron.rewrite.model.GraphitronType;
import no.sikt.graphitron.rewrite.model.WireCoercionError;

/**
 * The single classify-time home for the wire-coercion judgment: for a scalar SDL leaf bound
 * to a consumer-declared Java type, confirm graphql-java's coercion output for that leaf is
 * assignable to the declared type (a true raw pass-through), or produce a typed
 * {@link WireCoercionError.Assignability} rejection.
 *
 * <p>Previously, every arg-classification site fell through to {@code CallSiteExtraction.Direct}
 * and emitted a raw {@code (DeclaredType) wireValue} cast, never checking that graphql-java's
 * coercion for the SDL type actually yields {@code DeclaredType}. graphql-java delivers {@code ID}
 * and enum values as {@code String}, {@code Int} as {@code Integer}, {@code Float} as
 * {@code Double}, input-objects as {@code Map}. So a declared cast target of a jOOQ record, a
 * numeric PK type, a domain class, or a width-mismatched numeric compiles cleanly and
 * {@code ClassCastException}s on the first request. This predicate turns the {@code Direct}
 * fall-through into the <em>narrow</em> arm the check confirms is wire-pass-through.
 *
 * <p>The judgment lives here, at the classifier, not on {@link ScalarTypeResolver} (D1):
 * {@code ScalarTypeResolver} is a pure name↔type mapping that never holds a consumer-declared Java
 * type or a call site; folding the assignability verdict into it would make it a reader of types
 * it has never held. {@code ScalarTypeResolver} owns only the forward mapping
 * ({@link ScalarTypeResolver#coercionOutputType}); this class owns the verdict, and returns a
 * sealed {@link Result} mirroring the {@code EnumMappingResolver.EnumValidation} shape.
 *
 * <p>Enum leaves are <em>not</em> handled here: the enum→{@code String} coercion fact is carried
 * structurally by {@code EnumValueOf}, and enum-constant-name parity (site E) is a distinct axis
 * homed in {@code EnumMappingResolver} producing {@link WireCoercionError.EnumConstantDivergence}.
 * This class is scalar-assignability only.
 */
final class WireCoercionResolver {

    private WireCoercionResolver() {}

    /**
     * Outcome of {@link #checkScalar}. {@link PassThrough} confirms the {@code Direct} raw cast is
     * wire-sound (declared type equals the coercion output, or the leaf is unjudgeable and must not
     * be over-rejected); {@link Rejected} carries the typed {@link WireCoercionError.Assignability}.
     */
    sealed interface Result {
        record PassThrough() implements Result {}
        record Rejected(WireCoercionError error) implements Result {}
    }

    private static final Result PASS_THROUGH = new Result.PassThrough();

    /**
     * Confirms the graphql-java coercion output for {@code sdlLeafType} is assignable to
     * {@code declaredJavaTypeName} (the fully-qualified name off the parameter / member's reflected
     * type), or produces an {@link WireCoercionError.Assignability} rejection tied to {@code site}.
     *
     * <p>Element-wise: a {@code [Int!]} leaf and a {@code List<Integer>} declared type compare on
     * their {@code Int}/{@code Integer} elements. Primitives are boxed before comparison so a
     * declared {@code int} matches an {@code Integer} coercion. When the coercion output cannot be
     * determined (an unrecognised custom scalar, or an SDL leaf that is not a scalar), the check
     * passes through: it never over-rejects a leaf it cannot judge, keeping the audit-cleared
     * custom-scalar path sound. When the leaf and declared type disagree on list-ness the check
     * also passes through, leaving that cardinality concern to the existing shape checks.
     */
    static Result checkScalar(GraphQLInputType sdlLeafType, String declaredJavaTypeName,
            Iterable<GraphitronType> classifiedTypes, String site) {
        if (sdlLeafType == null || declaredJavaTypeName == null) {
            return PASS_THROUGH;
        }
        PeeledSdl sdl = peelSdl(sdlLeafType);
        if (!(sdl.named() instanceof GraphQLScalarType scalar)) {
            return PASS_THROUGH;
        }
        TypeName coercionElement = ScalarTypeResolver.coercionOutputType(scalar.getName(), classifiedTypes);
        if (coercionElement == null) {
            return PASS_THROUGH;
        }
        PeeledJava declared = peelJava(declaredJavaTypeName);
        if (sdl.list() != declared.list()) {
            return PASS_THROUGH;
        }
        String coercionElementFqn = coercionElement.toString();
        if (coercionElementFqn.equals(declared.element())) {
            return PASS_THROUGH;
        }
        String sdlLeafPrinted = GraphQLTypeUtil.simplePrint(sdlLeafType);
        String coercionShown = sdl.list() ? "java.util.List<" + coercionElementFqn + ">" : coercionElementFqn;
        return new Result.Rejected(new WireCoercionError.Assignability(
            sdlLeafPrinted, coercionShown, declaredJavaTypeName, site));
    }

    private record PeeledSdl(boolean list, GraphQLType named) {}

    /** Unwraps one NonNull, one optional List, one inner NonNull to the named leaf type. */
    private static PeeledSdl peelSdl(GraphQLInputType type) {
        GraphQLType t = type;
        boolean list = false;
        if (t instanceof GraphQLNonNull nn) t = nn.getWrappedType();
        if (t instanceof GraphQLList lst) {
            list = true;
            t = lst.getWrappedType();
            if (t instanceof GraphQLNonNull nn2) t = nn2.getWrappedType();
        }
        return new PeeledSdl(list, t);
    }

    private record PeeledJava(boolean list, String element) {}

    /** Peels {@code List<X>} / {@code Set<X>} to {@code X}, boxing a primitive element name. */
    private static PeeledJava peelJava(String typeName) {
        if (typeName.startsWith("java.util.List<") && typeName.endsWith(">")) {
            return new PeeledJava(true,
                typeName.substring("java.util.List<".length(), typeName.length() - 1));
        }
        if (typeName.startsWith("java.util.Set<") && typeName.endsWith(">")) {
            return new PeeledJava(true,
                typeName.substring("java.util.Set<".length(), typeName.length() - 1));
        }
        return new PeeledJava(false, InputBeanResolver.boxPrimitive(typeName));
    }
}
