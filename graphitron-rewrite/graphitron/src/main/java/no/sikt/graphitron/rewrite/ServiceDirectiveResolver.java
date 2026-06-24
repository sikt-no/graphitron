package no.sikt.graphitron.rewrite;

import graphql.schema.GraphQLFieldDefinition;
import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.ParameterizedTypeName;
import no.sikt.graphitron.javapoet.TypeName;
import no.sikt.graphitron.rewrite.model.ColumnRef;
import no.sikt.graphitron.rewrite.model.FieldWrapper;
import no.sikt.graphitron.rewrite.model.GraphitronField;
import no.sikt.graphitron.rewrite.model.LoaderRegistration;
import no.sikt.graphitron.rewrite.model.MethodRef;
import no.sikt.graphitron.rewrite.model.ParamSource;
import no.sikt.graphitron.rewrite.model.Rejection;
import no.sikt.graphitron.rewrite.model.ReturnTypeRef;
import no.sikt.graphitron.rewrite.model.RowsMethodShape;
import no.sikt.graphitron.rewrite.model.SourceKey;
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
 * the class-backed-parent DEFERRED rejection for result-parent's Result/Scalar arms).
 *
 * <p>Root vs child is signalled by {@code parentPkColumns}: empty for root (Query / Mutation),
 * non-empty for child @service on a {@code TableType} parent. Child @service on a class-backed
 * parent passes empty (the batch key derives elsewhere).
 *
 * <p>Implementation note: the helpers this resolver calls back into ({@code parseExternalRef},
 * {@code fieldArgumentNames}, {@code parseContextArguments}, {@code buildWrapper},
 * {@code liftToErrorsField}) live as package-private members on {@link FieldBuilder}, shared
 * with the other directive resolvers ({@code @tableMethod}, {@code @externalField},
 * {@code @lookupKey}).
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
    private final InputBeanResolver inputBeans;

    ServiceDirectiveResolver(BuildContext ctx, ServiceCatalog svc, FieldBuilder fb,
                              InputBeanResolver inputBeans) {
        this.ctx = ctx;
        this.svc = svc;
        this.fb = fb;
        this.inputBeans = inputBeans;
    }

    /**
     * Resolves {@code @service} on {@code fieldDef}. Pass {@link List#of()} for
     * {@code parentPkColumns} at root sites (Query / Mutation) and on class-backed parents;
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
            argBindings, new HashSet<>(contextArgs), parentPkColumns, expectedReturnType,
            slotTypes);
        if (result.failed()) {
            return new Resolved.Rejected(result.rejection().prefixedWith("service method could not be resolved — "));
        }
        var beanResult = inputBeans.enrich((MethodRef.Service) result.ref(), fieldDef);
        if (beanResult instanceof InputBeanResolver.Result.Failed f) {
            return new Resolved.Rejected(f.rejection().prefixedWith("service method could not be resolved — "));
        }
        MethodRef method = ((InputBeanResolver.Result.Ok) beanResult).method();

        if (isRoot) {
            String invariant = validateRootInvariants(returnType, method);
            if (invariant != null) {
                return new Resolved.Rejected(Rejection.invalidSchema(invariant));
            }
            String pairMismatch = validateRootListTableBoundReturnPair(returnType, method);
            if (pairMismatch != null) {
                return new Resolved.Rejected(Rejection.structural("service method could not be resolved — " + pairMismatch));
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
     * Parent-table consistency check for the typed-{@code TableRecord} source-shape arm
     * ({@link SourceKey.Wrap.TableRecord}). When the developer declares {@code Set<X>} or
     * {@code List<X>} where {@code X extends TableRecord}, {@code X} must be the parent type's
     * expected record class — otherwise the emitted
     * {@code ((Record) env.getSource()).into(Tables.X)} extraction would silently project the
     * parent's runtime record into a wrong-typed {@code TableRecord}. The other source-shape
     * arms ({@link SourceKey.Wrap.Row} / {@link SourceKey.Wrap.Record}) are unaffected: their
     * key shapes don't carry a typed record class to mismatch.
     */
    private String validateTableRecordSourceParentTable(String parentTypeName, MethodRef method) {
        var sourced = method.params().stream()
            .filter(MethodRef.Param.Sourced.class::isInstance)
            .map(MethodRef.Param.Sourced.class::cast)
            .findFirst()
            .orElse(null);
        if (sourced == null) return null;
        if (!(sourced.wrap() instanceof SourceKey.Wrap.TableRecord tr)) return null;
        var expected = ctx.recordClassForTypeName(parentTypeName).orElse(null);
        if (expected == null) return null;
        ClassName expectedClassName = ClassName.get(expected);
        if (expectedClassName.equals(tr.className())) return null;
        return "method '" + method.methodName() + "' in class '" + method.className()
            + "' takes Sources element type '" + tr.className().simpleName()
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
     *
     * <p>The structural "return type doesn't match the field" check for the
     * {@code TableBoundReturnType} + List arm lives separately in
     * {@link #validateRootListTableBoundReturnPair} so its rejection wears the same
     * {@code "service method could not be resolved — "} prefix as the catalog's strict
     * Single-arm rejection, and so the annotation contract can name it as a distinct
     * producer site.
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
     * Structural-return check for root {@code @service} fields whose resolved return type is a
     * {@link ReturnTypeRef.TableBoundReturnType} with List cardinality. The developer's method
     * may declare either {@code org.jooq.Result<XRecord>} or {@code java.util.List<XRecord>};
     * graphql-java treats both identically (Result extends List), and the emitter reads
     * {@link MethodRef#returnType()} to declare whichever shape the developer chose. Single
     * cardinality is validated strictly inside {@link ServiceCatalog#reflectServiceMethod} via
     * its {@code expectedReturnType} parameter.
     *
     * <p>Returns {@code null} when the resolved return type isn't List-cardinality TableBound
     * (the looser pair only applies there) or when the method matches one of the two acceptable
     * shapes; otherwise returns the rejection reason (caller prefixes with
     * {@code "service method could not be resolved — "} so the wording matches the Single-arm
     * catalog rejection a developer would see for the same field on the Single side).
     */
    private static String validateRootListTableBoundReturnPair(ReturnTypeRef returnType, MethodRef method) {
        if (!(returnType instanceof ReturnTypeRef.TableBoundReturnType tb)) return null;
        if (!returnType.wrapper().isList()) return null;
        ClassName recordCls = tb.table().recordClass();
        TypeName expectedResult = ParameterizedTypeName.get(ClassName.get("org.jooq", "Result"), recordCls);
        TypeName expectedList = ParameterizedTypeName.get(ClassName.get("java.util", "List"), recordCls);
        TypeName actual = method.returnType();
        if (actual.equals(expectedResult) || actual.equals(expectedList)) return null;
        return "method '" + method.methodName() + "' in class '" + method.className()
            + "' must return '" + TypeNames.simple(expectedResult)
            + "' or '" + TypeNames.simple(expectedList)
            + "' to match the field's declared return type — got '" + TypeNames.simple(actual) + "'";
    }

    /**
     * Computes the expected return type that a root {@code @service} method must declare, as a
     * structured javapoet {@link TypeName}. Returns {@code null} when no strict validation is
     * applicable (the caller treats the actual reflection-captured return type as truth).
     *
     * <ul>
     *   <li>{@code TableBoundReturnType} + Single → {@code <schemaPackage>.tables.records.<TableName>Record}</li>
     *   <li>{@code TableBoundReturnType} + List → null (either {@code Result<XRecord>} or
     *       {@code List<XRecord>} is acceptable; the choice is validated post-reflection in
     *       {@link #validateRootListTableBoundReturnPair} so the emitter can declare whichever
     *       shape the developer chose).</li>
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
                yield isList ? null : recordCls;
            }
            case ReturnTypeRef.ResultReturnType r -> {
                // ResultReturnType with a backing class is the class-backed payload shape. The service
                // method must return the SDL payload class directly (universal passthrough); the
                // strict TypeName-equals check happens inside FieldBuilder.buildServiceField,
                // which produces a payload-class-citing diagnostic on mismatch. Return null here
                // so the strict check is skipped at the resolver layer, and let the classifier
                // emit the precise reject.
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
     * for {@code .returns(...)}, so the two cannot drift. For a schema-named leaf (the five spec
     * built-ins, a table-bound or class-backed return) the {@link TypeName#equals} check is a true
     * strict-equality check on the whole shape. For a non-built-in scalar leaf (an enum, or an
     * unregistered custom scalar) the schema cannot name {@code V}, so the leaf is peeled from the
     * developer's declared outer type ({@link RowsMethodShape#perKeyFromOuter}) and fed straight
     * back into the reconstruction (R364, the same resolution the emitter's {@code elementType()}
     * uses). Because the leaf round-trips through the same return type it is then compared against,
     * the {@code equals} check for that branch is effectively a key-type check; the container raw
     * type and list-nesting are gated by {@code perKeyFromOuter} returning non-null, and the leaf
     * itself is accepted as whatever the method yields (the typed-context-value registry, tracked
     * under {@code emit-text-mapped-enum-fields-as-enum-type}, will tighten the leaf later). A
     * shape too malformed to peel is rejected rather than left to miscompile. Returns {@code null}
     * (skip) when the schema carries no derivable shape at all ({@link ReturnTypeRef.ResultReturnType} with no backing class,
     * {@link ReturnTypeRef.PolymorphicReturnType} which is rejected separately), or when no
     * {@link MethodRef.Param.Sourced} parameter is present (validator surfaces that absence).
     */
    private static String validateChildServiceReturnType(ReturnTypeRef returnType, MethodRef method) {
        MethodRef.Param.Sourced sourced = method.params().stream()
            .filter(MethodRef.Param.Sourced.class::isInstance)
            .map(MethodRef.Param.Sourced.class::cast)
            .findFirst()
            .orElse(null);
        if (sourced == null) return null;

        boolean isMapped = sourced.container() == LoaderRegistration.Container.MAPPED_SET;
        TypeName keyElementType = SourceKey.keyElementType(sourced.wrap(), sourced.columns());

        TypeName perKey = RowsMethodShape.strictPerKeyType(returnType);
        if (perKey == null) {
            // Only a non-built-in scalar leaf (an enum, or an unregistered custom scalar) is
            // recoverable: the schema can't name V, but the developer's method declares the outer
            // Map<K, V> / List<V>, so recover V by peeling and re-derive the expected outer type
            // from it (R364, the same leaf resolution the emitter's elementType() uses). This still
            // rejects a wrong key type or a missing list-nesting level; the leaf itself is accepted
            // as whatever the method yields (the typed-context-value registry will tighten it
            // later). A shape too malformed to peel (a List where a Map is required, a raw type) is
            // rejected here rather than left to miscompile on the generated return line. Other
            // null-perKey returns (a backing-less ResultReturnType, a polymorphic type) carry no
            // derivable shape and stay skipped.
            if (!(returnType instanceof ReturnTypeRef.ScalarReturnType)) return null;
            perKey = RowsMethodShape.perKeyFromOuter(method.returnType(), returnType, isMapped);
            if (perKey == null) {
                return "method '" + method.methodName() + "' in class '" + method.className()
                    + "' must return a " + (isMapped ? "'java.util.Map'" : "'java.util.List'")
                    + "-shaped batch result keyed by '" + TypeNames.simple(keyElementType)
                    + "' to match the field's declared return type — got '"
                    + TypeNames.simple(method.returnType()) + "'";
            }
        }
        TypeName expected = RowsMethodShape.outerRowsReturnType(
            perKey, returnType, keyElementType, isMapped);
        if (method.returnType().equals(expected)) return null;

        return "method '" + method.methodName() + "' in class '" + method.className()
            + "' must return '" + TypeNames.simple(expected)
            + "' to match the field's declared return type — got '" + TypeNames.simple(method.returnType()) + "'";
    }
}
