package no.sikt.graphitron.rewrite.model;

import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.ParameterizedTypeName;
import no.sikt.graphitron.javapoet.TypeName;
import no.sikt.graphitron.rewrite.ScalarTypeResolver;

/**
 * Pure structural derivation of the {@code @service} rows-method's outer return type and
 * per-key element type {@code V}. Composes the field's {@link ReturnTypeRef}, the
 * DataLoader key element type, and the container-axis choice (mapped vs positional) into
 * the rows-method's {@code Map<K, V>} / {@code List<V>} shape.
 *
 * <p>The shapes:
 *
 * <ul>
 *   <li>Mapped (Set keys): {@code Map<KeyType, V>} (single) or {@code Map<KeyType, List<V>>}
 *       (list).</li>
 *   <li>Positional (List keys): {@code List<V>} (single) or {@code List<List<V>>} (list).</li>
 * </ul>
 *
 * <p>One source of truth for two consumers: the validator (strict-equality check on the
 * developer-declared rows-method return type at classify time, in
 * {@code ServiceDirectiveResolver}) and the emitter ({@code .returns(...)} on the rows
 * method body in {@code TypeFetcherGenerator.buildServiceRowsMethod}). The
 * {@code service-directive-resolver-strict-child-service-return}
 * {@link LoadBearingClassifierCheck} key pairs them; this helper is the implementation
 * both sides share so the cross-product cannot drift.
 */
public final class RowsMethodShape {

    private static final ClassName LIST   = ClassName.get("java.util", "List");
    private static final ClassName MAP    = ClassName.get("java.util", "Map");

    private RowsMethodShape() {}

    /**
     * The structurally-determined per-key element type {@code V} the rows method's
     * {@code Map<KeyType, V>} or {@code List<V>} resolves to. Returns {@code null} when the
     * schema doesn't carry enough information to determine {@code V} statically; callers
     * decide whether to skip a strict check (validator) or fall back to a looser type
     * (loader signature).
     *
     * <ul>
     *   <li>{@link ReturnTypeRef.TableBoundReturnType} → {@code tb.table().recordClass()}
     *       (the jOOQ-generated {@code XRecord} class for the field's bound table).</li>
     *   <li>{@link ReturnTypeRef.ResultReturnType} with non-null {@code fqClassName} → the
     *       backing class.</li>
     *   <li>{@link ReturnTypeRef.ScalarReturnType} for one of the five standard GraphQL
     *       scalars ({@code String} / {@code Boolean} / {@code Int} / {@code Float} /
     *       {@code ID}) → the corresponding Java class.</li>
     *   <li>Everything else (custom scalars, enums, {@link ReturnTypeRef.PolymorphicReturnType},
     *       {@link ReturnTypeRef.ResultReturnType} with no backing class) → {@code null}.</li>
     * </ul>
     */
    public static TypeName strictPerKeyType(ReturnTypeRef returnType) {
        return switch (returnType) {
            case ReturnTypeRef.TableBoundReturnType tb -> tb.table().recordClass();
            case ReturnTypeRef.ResultReturnType r -> r.fqClassName() != null
                ? ClassName.bestGuess(r.fqClassName())
                : null;
            case ReturnTypeRef.ScalarReturnType s -> standardScalarJavaType(s.returnTypeName());
            case ReturnTypeRef.PolymorphicReturnType ignored -> null;
        };
    }

    /**
     * Java type for one of the five GraphQL spec built-ins; {@code null} for custom
     * scalars and enums. Routed through {@link ScalarTypeResolver#builtInJavaType(String)}
     * so the producer-side scalar Java-type mapping has one source of truth across the
     * codebase. Phase 2's {@code @scalarType} directive and Phase 3's extended-scalars
     * convention layer surface custom-scalar Java types through the same resolver; callers
     * that wire those phases route their non-null branches through the resolver as well.
     */
    @DependsOnClassifierCheck(
        key = "scalar-resolver.javatype-is-typename",
        reliesOn = "Returns the resolver's TypeName directly into strictPerKeyType, which feeds "
            + "MethodSpec.returns(...) for the rows-method without coercion.")
    @DependsOnClassifierCheck(
        key = "scalar-resolver.coercing-non-erased",
        reliesOn = "Non-null return is the spec-built-in canonical type from the resolver's "
            + "closed table; never Object.")
    public static TypeName standardScalarJavaType(String graphqlScalarName) {
        return ScalarTypeResolver.builtInJavaType(graphqlScalarName);
    }

    /**
     * Builds the structurally-expected outer return type of the rows method from a known
     * {@code perKey} value, the field's return type wrapper, and the two derived primitives
     * needed for the outer shape: the DataLoader key element type and whether the loader
     * uses a mapped (Set-keyed) container. Validator and emitter both call this; only
     * {@code perKey} differs (validator passes {@link #strictPerKeyType}, emitter passes the
     * field-known {@code V}). Taking primitives instead of {@link SourceKey} /
     * {@link LoaderRegistration} keeps this helper independent of the storage shape, so
     * callers that have a different access path (e.g. {@link MethodRef.Param.Sourced} during
     * service-method validation) can call without round-tripping through the field-level
     * accessors.
     */
    public static TypeName outerRowsReturnType(
            TypeName perKey,
            ReturnTypeRef returnType,
            TypeName keyElementType,
            boolean isMapped) {
        boolean isList = returnType.wrapper().isList();
        TypeName valuePerKey = isList ? ParameterizedTypeName.get(LIST, perKey) : perKey;
        if (isMapped) {
            return ParameterizedTypeName.get(MAP, keyElementType, valuePerKey);
        }
        return isList
            ? ParameterizedTypeName.get(LIST, ParameterizedTypeName.get(LIST, perKey))
            : ParameterizedTypeName.get(LIST, perKey);
    }
}
