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
 * <p>One source of truth for three consumers: the validator (strict-equality check on the
 * developer-declared rows-method return type at classify time, in
 * {@code ServiceDirectiveResolver}), the rows-method emitter ({@code .returns(...)} on the
 * rows method body in {@code TypeFetcherGenerator.buildServiceRowsMethod}), and the
 * data-fetcher emitter (the {@code DataLoader<K, V>} typing line built in
 * {@code TypeFetcherGenerator.buildServiceDataFetcher}, which Java generics invariance
 * forces to match the rows method's declared {@code V} exactly). This helper is the
 * implementation the validator and both emit sites share so the cross-product cannot drift.
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

    /**
     * Inverse of {@link #outerRowsReturnType}: recovers the per-key element type {@code V} from a
     * known outer rows-method type. Used when {@link #strictPerKeyType} can't name {@code V} from
     * the schema, a non-built-in scalar leaf (an enum, or an unregistered custom scalar) returns
     * {@code null} there, but the developer's service method already declares the outer
     * {@code Map<K, V>} / {@code List<V>} shape, so {@code V} is the leaf that shape yields. Peeling
     * it (rather than handing back the whole map) keeps {@code ServiceRecordField.elementType()}'s
     * value out of {@code outerRowsReturnType}'s wrapping path, so the rows method's declared type is the flat
     * {@code Map<K, V>} that matches the service contract instead of a doubly-nested
     * {@code Map<K, Map<K, V>>} (R364).
     *
     * <p>Returns {@code null} when {@code outer} isn't the parameterized container the
     * {@code (isMapped, isList)} pair expects (a {@code List} where a {@code Map} is required, a
     * missing list-nesting level, a raw type). Callers that emit ({@code elementType()}) fall back
     * to the whole declared type so a shape validation missed surfaces as the same request-time
     * failure as before; the validator treats {@code null} as a rejection.
     */
    public static TypeName perKeyFromOuter(TypeName outer, ReturnTypeRef returnType, boolean isMapped) {
        boolean isList = returnType.wrapper().isList();
        // Strip the outer container: mapped → Map<K, valuePerKey> (V rides the value arg);
        // positional → List<valuePerKey> (V rides the element arg).
        TypeName valuePerKey = isMapped ? typeArgAt(outer, MAP, 1) : typeArgAt(outer, LIST, 0);
        if (valuePerKey == null) return null;
        // Strip the list-cardinality wrap outerRowsReturnType applies for list-valued fields.
        return isList ? typeArgAt(valuePerKey, LIST, 0) : valuePerKey;
    }

    /**
     * The {@code index}-th type argument of {@code type} when it is a {@link ParameterizedTypeName}
     * over {@code expectedRaw} with enough arguments; {@code null} otherwise. The structural guard
     * lets {@link #perKeyFromOuter} reject a malformed outer shape rather than peel the wrong slot.
     */
    private static TypeName typeArgAt(TypeName type, ClassName expectedRaw, int index) {
        if (type instanceof ParameterizedTypeName p
                && p.rawType().equals(expectedRaw)
                && p.typeArguments().size() > index) {
            return p.typeArguments().get(index);
        }
        return null;
    }
}
