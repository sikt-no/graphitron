package no.sikt.graphitron.rewrite;

import graphql.schema.GraphQLFieldDefinition;
import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.ParameterizedTypeName;
import no.sikt.graphitron.javapoet.TypeName;
import no.sikt.graphitron.rewrite.model.BatchKey;
import no.sikt.graphitron.rewrite.model.ColumnRef;
import no.sikt.graphitron.rewrite.model.FieldWrapper;
import no.sikt.graphitron.rewrite.model.GraphitronField;
import no.sikt.graphitron.rewrite.model.LoadBearingClassifierCheck;
import no.sikt.graphitron.rewrite.model.MethodRef;
import no.sikt.graphitron.rewrite.model.ParamSource;
import no.sikt.graphitron.rewrite.model.Rejection;
import no.sikt.graphitron.rewrite.model.ReturnTypeRef;
import no.sikt.graphitron.rewrite.model.RowsMethodShape;
import no.sikt.graphitron.rewrite.model.TypeNames;

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
        var slotTypes = FieldBuilder.argSlotTypes(fieldDef);
        var argMapping = serviceRef != null ? serviceRef.argMapping() : Map.<String, List<String>>of();
        var argBindingsResult = ArgBindingMap.of(slotTypes, argMapping);
        if (argBindingsResult instanceof ArgBindingMap.Result.UnknownArgRef u) {
            return new Resolved.Rejected(Rejection.structural("service method could not be resolved — @service " + u.message()));
        }
        if (argBindingsResult instanceof ArgBindingMap.Result.PathRejected p) {
            return new Resolved.Rejected(Rejection.structural("service method could not be resolved — @service " + p.message()));
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
            return new Resolved.Rejected(result.rejection().prefixedWith("service method could not be resolved — "));
        }
        MethodRef method = enumMapping.enrichArgExtractions(result.ref(), fieldDef);

        if (isRoot) {
            String invariant = validateRootInvariants(returnType, method);
            if (invariant != null) {
                return new Resolved.Rejected(Rejection.invalidSchema(invariant));
            }
        } else {
            String parentTableMismatch = validateTableRecordSourceParentTable(parentTypeName, method);
            if (parentTableMismatch != null) {
                return new Resolved.Rejected(Rejection.structural(parentTableMismatch));
            }
            String mismatch = validateChildServiceReturnType(returnType, method);
            if (mismatch != null) {
                return new Resolved.Rejected(Rejection.structural(mismatch));
            }
        }

        return projectReturnType(returnType, method, fieldDef, parentTypeName);
    }

    /**
     * Parent-table consistency check for the typed-{@code TableRecord} source-shape arms
     * ({@link BatchKey.TableRecordKeyed} / {@link BatchKey.MappedTableRecordKeyed}). When the
     * developer declares {@code Set<X>} or {@code List<X>} where {@code X extends TableRecord},
     * {@code X} must be the parent type's expected record class — otherwise the emitted
     * {@code ((Record) env.getSource()).into(Tables.X)} extraction would silently project the
     * parent's runtime record into a wrong-typed {@code TableRecord}. Other batch-key arms
     * (RowN / RecordN sources, accessor-keyed @record parents, etc.) are unaffected: their
     * key shapes don't carry a typed record class to mismatch.
     */
    private String validateTableRecordSourceParentTable(String parentTypeName, MethodRef method) {
        var sourced = method.params().stream()
            .filter(MethodRef.Param.Sourced.class::isInstance)
            .map(MethodRef.Param.Sourced.class::cast)
            .findFirst()
            .orElse(null);
        if (sourced == null) return null;
        Class<? extends org.jooq.TableRecord<?>> elementClass = switch (sourced.batchKey()) {
            case BatchKey.TableRecordKeyed trk -> trk.elementClass();
            case BatchKey.MappedTableRecordKeyed mtrk -> mtrk.elementClass();
            default -> null;
        };
        if (elementClass == null) return null;
        var expected = ctx.recordClassForTypeName(parentTypeName).orElse(null);
        if (expected == null || expected.equals(elementClass)) return null;
        return "method '" + method.methodName() + "' in class '" + method.className()
            + "' takes Sources element type '" + elementClass.getSimpleName()
            + "' but the parent type '" + parentTypeName
            + "' is backed by '" + expected.getSimpleName()
            + "' — change the Sources element to '" + expected.getSimpleName()
            + "', or use a Row1/Record1 source-shape if the typed record isn't needed";
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
                    : new Resolved.Rejected(Rejection.deferred("@service returning a polymorphic type is not yet supported", ""));
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
     *   <li>{@code TableBoundReturnType} + Single → {@code <schemaPackage>.tables.records.<TableName>Record}</li>
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
                ClassName recordCls = tb.table().recordClass();
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
     * <p>The construction defers to {@link RowsMethodShape}; the emitter calls the same helper
     * for {@code .returns(...)}, so the two cannot drift. Returns {@code null} (skip) when the
     * per-key element type cannot be derived from the schema
     * ({@link ReturnTypeRef.ResultReturnType} with no backing class, custom scalars / enums
     * until the typed-context-value-registry lands, {@link ReturnTypeRef.PolymorphicReturnType}
     * which is rejected separately), or when no {@link MethodRef.Param.Sourced} parameter is
     * present (validator surfaces that absence).
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
        MethodRef.Param.Sourced sourced = method.params().stream()
            .filter(MethodRef.Param.Sourced.class::isInstance)
            .map(MethodRef.Param.Sourced.class::cast)
            .findFirst()
            .orElse(null);
        if (sourced == null) return null;
        BatchKey.ParentKeyed batchKey = sourced.batchKey();

        TypeName perKey = RowsMethodShape.strictPerKeyType(returnType);
        if (perKey == null) return null;
        TypeName expected = RowsMethodShape.outerRowsReturnType(perKey, returnType, batchKey);
        if (method.returnType().equals(expected)) return null;

        return "method '" + method.methodName() + "' in class '" + method.className()
            + "' must return '" + TypeNames.simple(expected)
            + "' to match the field's declared return type — got '" + TypeNames.simple(method.returnType()) + "'";
    }
}
