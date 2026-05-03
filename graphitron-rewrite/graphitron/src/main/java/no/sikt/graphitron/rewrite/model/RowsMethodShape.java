package no.sikt.graphitron.rewrite.model;

import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.ParameterizedTypeName;
import no.sikt.graphitron.javapoet.TypeName;

/**
 * Pure structural derivation of the {@code @service} rows-method's outer return type and
 * per-key element type {@code V} from the field's {@link ReturnTypeRef} and {@link BatchKey}.
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

    private static final ClassName RECORD = ClassName.get("org.jooq", "Record");
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
     *   <li>{@link ReturnTypeRef.TableBoundReturnType} → raw {@code org.jooq.Record}.</li>
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
            case ReturnTypeRef.TableBoundReturnType ignored -> RECORD;
            case ReturnTypeRef.ResultReturnType r -> r.fqClassName() != null
                ? ClassName.bestGuess(r.fqClassName())
                : null;
            case ReturnTypeRef.ScalarReturnType s -> standardScalarJavaType(s.returnTypeName());
            case ReturnTypeRef.PolymorphicReturnType ignored -> null;
        };
    }

    /**
     * Java type for one of the five standard GraphQL scalars; {@code null} for custom
     * scalars and enums (until the typed-context-value-registry surfaces a typed Java
     * class).
     */
    public static TypeName standardScalarJavaType(String graphqlScalarName) {
        return switch (graphqlScalarName) {
            case "String", "ID" -> ClassName.get(String.class);
            case "Boolean"      -> ClassName.get(Boolean.class);
            case "Int"          -> ClassName.get(Integer.class);
            case "Float"        -> ClassName.get(Double.class);
            default             -> null;
        };
    }

    /**
     * Builds the structurally-expected outer return type of the rows method from a known
     * {@code perKey} value, the field's return type wrapper, and the batch-key variant.
     * Validator and emitter both call this; only the {@code perKey} input differs (validator
     * passes {@link #strictPerKeyType}, emitter passes the field-known {@code V}).
     */
    public static TypeName outerRowsReturnType(TypeName perKey, ReturnTypeRef returnType, BatchKey.ParentKeyed batchKey) {
        boolean isMapped = batchKey instanceof BatchKey.MappedRowKeyed
                        || batchKey instanceof BatchKey.MappedRecordKeyed;
        boolean isList = returnType.wrapper().isList();
        TypeName valuePerKey = isList ? ParameterizedTypeName.get(LIST, perKey) : perKey;
        if (isMapped) {
            return ParameterizedTypeName.get(MAP, batchKey.keyElementType(), valuePerKey);
        }
        return isList
            ? ParameterizedTypeName.get(LIST, ParameterizedTypeName.get(LIST, perKey))
            : ParameterizedTypeName.get(LIST, perKey);
    }
}
