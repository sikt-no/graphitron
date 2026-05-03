package no.sikt.graphitron.rewrite;

import graphql.schema.GraphQLFieldDefinition;
import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.ParameterizedTypeName;
import no.sikt.graphitron.javapoet.TypeName;
import no.sikt.graphitron.rewrite.generators.GeneratorUtils;
import no.sikt.graphitron.rewrite.model.BatchKey;
import no.sikt.graphitron.rewrite.model.ColumnRef;
import no.sikt.graphitron.rewrite.model.FieldWrapper;
import no.sikt.graphitron.rewrite.model.GraphitronField;
import no.sikt.graphitron.rewrite.model.LoadBearingClassifierCheck;
import no.sikt.graphitron.rewrite.model.MethodRef;
import no.sikt.graphitron.rewrite.model.ParamSource;
import no.sikt.graphitron.rewrite.model.Rejection;
import no.sikt.graphitron.rewrite.model.ReturnTypeRef;

import java.util.HashSet;
import java.util.List;
import java.util.Map;

import static no.sikt.graphitron.rewrite.BuildContext.ARG_SERVICE_REF;
import static no.sikt.graphitron.rewrite.BuildContext.DIR_SERVICE;
import static no.sikt.graphitron.rewrite.BuildContext.baseTypeName;

/**
 * Resolves {@code @service} on a field into a sealed {@link Resolved} the caller switches on,
 * absorbing every cross-arm concern that was previously inlined at all four classify sites
 * ({@code classifyQueryField}, {@code classifyMutationField}, {@code classifyChildFieldOnResultType},
 * {@code classifyChildFieldOnTableType}):
 *
 * <ul>
 *   <li>Method lookup, arg-binding parse, return-type classification.</li>
 *   <li>Strict return-type validation against {@link MethodRef} reflection (root only).</li>
 *   <li>Root invariants (Connection wrapper rejection, {@code Sources} parameter rejection).</li>
 *   <li>Errors-channel lift on a polymorphic-of-{@code @error} return type, with the
 *       byte-identical "polymorphic-not-supported" rejection as the fallback.</li>
 * </ul>
 *
 * <p>Each classify arm projects {@link Resolved.Success} into its specific {@code GraphitronField}
 * variant (the variant identity differs across parent contexts: query/mutation/result-parent/
 * table-parent) and handles parent-context-only concerns (e.g. join-path parse for child sites,
 * the @record-typed-parent DEFERRED rejection for result-parent's Result/Scalar arms).
 *
 * <p>Root vs child is signalled by {@code parentPkColumns}: empty for root (Query / Mutation),
 * non-empty for child @service on a {@code TableType} parent. Child @service on a @record-typed
 * parent passes empty (the batch key derives elsewhere).
 *
 * <p>Implementation note: the helpers this resolver calls back into ({@code parseExternalRef},
 * {@code fieldArgumentNames}, {@code parseContextArguments}, {@code buildWrapper},
 * {@code enrichArgExtractions}, {@code liftToErrorsField}) live as package-private members on
 * {@link FieldBuilder}, shared with the other directive resolvers ({@code @tableMethod},
 * {@code @externalField}, {@code @lookupKey}).
 */
final class ServiceDirectiveResolver {

    /**
     * Outcome of {@link #resolve}. Five terminal arms; the caller exhausts them with a switch.
     *
     * <ul>
     *   <li>{@link Success} — sealed sub-interface with one arm per resolved return-type shape
     *       ({@link TableBound}, {@link Result}, {@link Scalar}). Each carries the resolved
     *       {@link MethodRef} and the typed {@link ReturnTypeRef}.</li>
     *   <li>{@link ErrorsLifted} — the polymorphic return type lifted into an
     *       {@code ErrorsField} (or a structural rejection of the lift). The carried
     *       {@link GraphitronField} is the caller's terminal value for this arm.</li>
     *   <li>{@link Rejected} — every error path: directive-parse failure, method-reflection
     *       failure, root-invariants failure, polymorphic-not-supported. Carries the
     *       {@link RejectionKind} and message the caller surfaces verbatim.</li>
     * </ul>
     */
    sealed interface Resolved {
        /** Successful resolution; arms differ by return-type shape. */
        sealed interface Success extends Resolved {
            MethodRef method();
        }
        record TableBound(ReturnTypeRef.TableBoundReturnType returnType, MethodRef method) implements Success {}
        record Result(ReturnTypeRef.ResultReturnType returnType, MethodRef method) implements Success {}
        record Scalar(ReturnTypeRef.ScalarReturnType returnType, MethodRef method) implements Success {}
        /** Polymorphic return type lifted to an {@code ErrorsField} (or rejected by lift rules). */
        record ErrorsLifted(GraphitronField field) implements Resolved {}
        /** Any failed resolution path; caller surfaces as {@code UnclassifiedField}. */
        record Rejected(Rejection rejection) implements Resolved {
            public String message() { return rejection.message(); }
            public RejectionKind kind() { return RejectionKind.of(rejection); }
        }
    }

    private final BuildContext ctx;
    private final ServiceCatalog svc;
    private final FieldBuilder fb;
    private final EnumMappingResolver enumMapping;

    ServiceDirectiveResolver(BuildContext ctx, ServiceCatalog svc, FieldBuilder fb, EnumMappingResolver enumMapping) {
        this.ctx = ctx;
        this.svc = svc;
        this.fb = fb;
        this.enumMapping = enumMapping;
    }

    /**
     * Resolves {@code @service} on {@code fieldDef}. Pass {@link List#of()} for
     * {@code parentPkColumns} at root sites (Query / Mutation) and on @record-typed parents;
     * pass the parent table's primary-key columns for {@code @table}-parent child sites.
     *
     * <p>An empty {@code parentPkColumns} also gates two root-only concerns:
     * <ul>
     *   <li>Strict expected-return-type comparison against the reflected method.</li>
     *   <li>{@link #validateRootInvariants} (Connection rejection, {@code Sources} param rejection).</li>
     * </ul>
     */
    Resolved resolve(String parentTypeName, GraphQLFieldDefinition fieldDef, List<ColumnRef> parentPkColumns) {
        boolean isRoot = parentPkColumns.isEmpty();
        String rawTypeName = baseTypeName(fieldDef);
        String elementTypeName = ctx.isConnectionType(rawTypeName)
            ? ctx.connectionElementTypeName(rawTypeName)
            : rawTypeName;
        ReturnTypeRef returnType = ctx.resolveReturnType(elementTypeName, fb.buildWrapper(fieldDef));

        FieldBuilder.ExternalRef serviceRef = fb.parseExternalRef(parentTypeName, fieldDef, DIR_SERVICE, ARG_SERVICE_REF);
        if (serviceRef != null && serviceRef.lookupError() != null) {
            return new Resolved.Rejected(Rejection.structural("service method could not be resolved — " + serviceRef.lookupError()));
        }
        if (serviceRef != null && serviceRef.argMappingError() != null) {
            return new Resolved.Rejected(Rejection.structural("service method could not be resolved — @service " + serviceRef.argMappingError()));
        }

        List<String> contextArgs = fb.parseContextArguments(fieldDef, DIR_SERVICE);
        var graphqlArgNames = FieldBuilder.fieldArgumentNames(fieldDef);
        var argMapping = serviceRef != null ? serviceRef.argMapping() : Map.<String, String>of();
        var argBindingsResult = ArgBindingMap.of(graphqlArgNames, argMapping);
        if (argBindingsResult instanceof ArgBindingMap.Result.UnknownArgRef u) {
            return new Resolved.Rejected(Rejection.structural("service method could not be resolved — @service " + u.message()));
        }
        var argBindings = ((ArgBindingMap.Result.Ok) argBindingsResult).map();

        // Strict return-type validation applies to root @service fields only. Child @service uses
        // DataLoader-batched semantics where the method takes Sources keys and returns a flat or
        // keyed shape that doesn't directly match the field's return type — that shape is the
        // child-service plan's concern. Root fields hand the value straight to graphql-java, so
        // the framework needs to know its specific shape.
        TypeName expectedReturnType = isRoot ? computeExpectedServiceReturnType(returnType) : null;

        var result = svc.reflectServiceMethod(serviceRef.className(), serviceRef.methodName(),
            argBindings, new HashSet<>(contextArgs), parentPkColumns, expectedReturnType);
        if (result.failed()) {
            return new Resolved.Rejected(Rejection.structural("service method could not be resolved — " + result.failureReason()));
        }
        MethodRef method = enumMapping.enrichArgExtractions(result.ref(), fieldDef);

        if (isRoot) {
            String invariant = validateRootInvariants(returnType, method);
            if (invariant != null) {
                return new Resolved.Rejected(Rejection.invalidSchema(invariant));
            }
        } else {
            String mismatch = validateChildServiceReturnType(returnType, method);
            if (mismatch != null) {
                return new Resolved.Rejected(Rejection.structural(mismatch));
            }
        }

        return projectReturnType(returnType, method, fieldDef, parentTypeName);
    }

    private Resolved projectReturnType(ReturnTypeRef returnType, MethodRef method,
                                       GraphQLFieldDefinition fieldDef, String parentTypeName) {
        return switch (returnType) {
            case ReturnTypeRef.TableBoundReturnType tb -> new Resolved.TableBound(tb, method);
            case ReturnTypeRef.ResultReturnType r -> new Resolved.Result(r, method);
            case ReturnTypeRef.ScalarReturnType s -> new Resolved.Scalar(s, method);
            case ReturnTypeRef.PolymorphicReturnType p -> {
                GraphitronField lifted = fb.liftToErrorsField(fieldDef, parentTypeName, p);
                yield lifted != null
                    ? new Resolved.ErrorsLifted(lifted)
                    : new Resolved.Rejected(Rejection.deferred("@service returning a polymorphic type is not yet supported"));
            }
        };
    }

    /**
     * Shared invariant check for root {@code @service} fields (both Query and Mutation arms).
     * Returns a non-null reason when the resolved return-type/method violates an invariant,
     * {@code null} otherwise.
     *
     * <ul>
     *   <li>§1: Connection return type — root has no pagination context.</li>
     *   <li>§2: {@link ParamSource.Sources} parameter — root has no parent context to batch
     *       against.</li>
     * </ul>
     */
    private static String validateRootInvariants(ReturnTypeRef returnType, MethodRef method) {
        if (returnType.wrapper() instanceof FieldWrapper.Connection) {
            return "@service at the root does not support Connection return types — use [T] or T instead";
        }
        if (method.params().stream().anyMatch(p -> p.source() instanceof ParamSource.Sources)) {
            return "@service at the root does not support List<Row>/List<Record>/List<Object> batch parameters — the root has no parent context to batch against";
        }
        return null;
    }

    /**
     * Computes the expected return type that a root {@code @service} method must declare, as a
     * structured javapoet {@link TypeName}. Returns {@code null} when no strict validation is
     * applicable (the caller treats the actual reflection-captured return type as truth).
     *
     * <ul>
     *   <li>{@code TableBoundReturnType} + Single → {@code <jooqPackage>.tables.records.<TableName>Record}</li>
     *   <li>{@code TableBoundReturnType} + List → {@code org.jooq.Result<<RecordFqcn>>}</li>
     *   <li>{@code ResultReturnType} (with non-null fqClassName) + Single → {@code <fqClassName>}</li>
     *   <li>{@code ResultReturnType} (with non-null fqClassName) + List → {@code java.util.List<<fqClassName>>}</li>
     *   <li>{@code ResultReturnType} (null fqClassName) → null</li>
     *   <li>{@code ScalarReturnType} → null (graphql-java's scalar coercion handles type matching)</li>
     *   <li>{@code PolymorphicReturnType} → null (rejected separately)</li>
     * </ul>
     *
     * <p>Connection-cardinality cases are unreachable here because {@code @service} +
     * {@code Connection} is rejected at root invariants §1 downstream.
     */
    private TypeName computeExpectedServiceReturnType(ReturnTypeRef returnType) {
        // Connection-cardinality is rejected by root invariants §1 downstream of this helper;
        // skip the return-type check here so the §1 message fires (rather than masking it with a
        // less-specific return-type mismatch).
        if (returnType.wrapper() instanceof FieldWrapper.Connection) return null;
        boolean isList = returnType.wrapper().isList();
        return switch (returnType) {
            case ReturnTypeRef.TableBoundReturnType tb -> {
                ClassName recordCls = ClassName.get(
                    ctx.ctx().jooqPackage() + ".tables.records",
                    tb.table().javaClassName() + "Record");
                yield isList
                    ? ParameterizedTypeName.get(ClassName.get("org.jooq", "Result"), recordCls)
                    : recordCls;
            }
            case ReturnTypeRef.ResultReturnType r -> {
                // ResultReturnType with a backing class is the @record-payload shape. The service
                // method may either return the SDL payload class directly (legacy passthrough
                // shape) OR a domain object that fits one of the payload class's canonical-
                // constructor parameters (the "service returns the domain object" shape). The
                // strict TypeName-equals check can't tell those apart; the ResultAssembly
                // resolver in FieldBuilder does. Return null here so the strict check is skipped,
                // and let the carrier classifier emit a precise reject when neither the SDL
                // payload nor any ctor-param shape matches.
                yield null;
            }
            case ReturnTypeRef.ScalarReturnType ignored -> null;
            case ReturnTypeRef.PolymorphicReturnType ignored -> null;
        };
    }

    /**
     * Strict return-type validation for child {@code @service} fields. Mirrors the structural
     * shape {@code TypeFetcherGenerator.buildServiceRowsMethod} produces for the rows method:
     * {@code Map<KeyType, V>} or {@code Map<KeyType, List<V>>} for mapped variants,
     * {@code List<V>} or {@code List<List<V>>} for positional variants. The developer's method
     * return type must equal that expected outer type exactly (per {@link TypeName#equals}); a
     * mismatch is rejected at classify time rather than left to surface as a {@code javac} error
     * on the generated {@code return ServiceClass.method(...)} line.
     *
     * <p>Returns a {@code null} message when the check is skipped: when the per-key element type
     * cannot be derived from the schema ({@link ReturnTypeRef.ResultReturnType} with no backing
     * class, custom scalars / enums until the typed-context-value-registry lands,
     * {@link ReturnTypeRef.PolymorphicReturnType} which is rejected separately), or when no
     * {@link MethodRef.Param.Sourced} parameter is present (validator surfaces that absence).
     */
    @LoadBearingClassifierCheck(
        key = "service-directive-resolver-strict-child-service-return",
        description = "The strict TypeName.equals arm rejects child @service developer methods "
            + "whose declared return type doesn't structurally match the rows-method's "
            + "Map<K, V>/List<List<V>>/List<V> shape derived from (returnType, batchKey). Lets "
            + "buildServiceRowsMethod emit `return ServiceClass.method(...)` without a defensive "
            + "cast or wildcard, and surfaces author errors at classify time rather than as "
            + "javac errors on the generated source.")
    private static String validateChildServiceReturnType(ReturnTypeRef returnType, MethodRef method) {
        BatchKey.ParentKeyed batchKey = method.params().stream()
            .filter(p -> p instanceof MethodRef.Param.Sourced)
            .map(p -> ((MethodRef.Param.Sourced) p).batchKey())
            .findFirst()
            .orElse(null);
        if (batchKey == null) return null;

        TypeName expected = computeExpectedChildServiceReturnType(returnType, batchKey);
        if (expected == null) return null;
        if (method.returnType().equals(expected)) return null;

        return "method '" + method.methodName() + "' in class '" + method.className()
            + "' must return '" + expected
            + "' to match the field's declared return type — got '" + method.returnType() + "'";
    }

    /**
     * Constructs the structurally-expected outer return type for a child {@code @service}
     * developer method, mirroring {@code TypeFetcherGenerator.buildServiceRowsMethod}'s declared
     * return shape per {@code (returnType, batchKey)}. Returns {@code null} when the per-key
     * element type can't be determined from the schema (caller skips the strict check).
     */
    private static TypeName computeExpectedChildServiceReturnType(ReturnTypeRef returnType,
                                                                  BatchKey.ParentKeyed batchKey) {
        TypeName perKey = perKeyType(returnType);
        if (perKey == null) return null;

        boolean isMapped = batchKey instanceof BatchKey.MappedRowKeyed
                        || batchKey instanceof BatchKey.MappedRecordKeyed;
        boolean isList = returnType.wrapper().isList();
        TypeName keysElementType = GeneratorUtils.keyElementType(batchKey);
        ClassName listCls = ClassName.get("java.util", "List");

        TypeName valuePerKey = isList ? ParameterizedTypeName.get(listCls, perKey) : perKey;
        if (isMapped) {
            return ParameterizedTypeName.get(ClassName.get("java.util", "Map"),
                keysElementType, valuePerKey);
        }
        return isList
            ? ParameterizedTypeName.get(listCls, ParameterizedTypeName.get(listCls, perKey))
            : ParameterizedTypeName.get(listCls, perKey);
    }

    /**
     * Per-key element type {@code V} the rows method's {@code Map<KeyType, V>} or
     * {@code List<V>} resolves to, derived from the field's {@link ReturnTypeRef}. Returns
     * {@code null} when the type can't be determined statically — the caller skips the strict
     * check rather than rejecting on incomplete information.
     *
     * <p>Mirrors {@code ChildField.ServiceRecordField#elementType()}'s arms but with stricter
     * fallback: {@code elementType()} falls back to {@code method.returnType()} so the loader
     * has a (possibly wrong) static type for {@code DataLoader<K, V>}, while this returns null
     * to skip the strict check entirely.
     *
     * <ul>
     *   <li>{@link ReturnTypeRef.TableBoundReturnType} → raw {@code org.jooq.Record} (matches
     *       the {@code RECORD} constant {@code TypeFetcherGenerator} passes for
     *       {@code ServiceTableField}'s rows method).</li>
     *   <li>{@link ReturnTypeRef.ResultReturnType} with non-null {@code fqClassName} → the
     *       backing class.</li>
     *   <li>{@link ReturnTypeRef.ScalarReturnType} for one of the standard GraphQL scalars
     *       ({@code String} / {@code Boolean} / {@code Int} / {@code Float} / {@code ID}) →
     *       the corresponding Java class.</li>
     *   <li>Everything else ({@code ResultReturnType} with no backing class, custom scalars,
     *       enums, {@link ReturnTypeRef.PolymorphicReturnType}) → {@code null}.</li>
     * </ul>
     */
    private static TypeName perKeyType(ReturnTypeRef returnType) {
        return switch (returnType) {
            case ReturnTypeRef.TableBoundReturnType ignored -> ClassName.get("org.jooq", "Record");
            case ReturnTypeRef.ResultReturnType r -> r.fqClassName() != null
                ? ClassName.bestGuess(r.fqClassName())
                : null;
            case ReturnTypeRef.ScalarReturnType s -> standardScalarJavaType(s.returnTypeName());
            case ReturnTypeRef.PolymorphicReturnType ignored -> null;
        };
    }

    private static TypeName standardScalarJavaType(String graphqlScalarName) {
        return switch (graphqlScalarName) {
            case "String", "ID" -> ClassName.get(String.class);
            case "Boolean"      -> ClassName.get(Boolean.class);
            case "Int"          -> ClassName.get(Integer.class);
            case "Float"        -> ClassName.get(Double.class);
            // Custom scalars and enums: skip strict check until typed-context-value-registry
            // surfaces a typed Java class (tracked under typed-context-value-registry.md).
            default             -> null;
        };
    }
}
