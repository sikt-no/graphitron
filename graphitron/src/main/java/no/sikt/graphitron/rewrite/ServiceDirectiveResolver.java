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
 * absorbing the concerns shared by all classify sites (query, mutation, result-parent,
 * table-parent):
 *
 * <ul>
 *   <li>Method lookup, arg-binding parse, return-type classification.</li>
 *   <li>Strict return-type validation against {@link MethodRef} reflection (root only).</li>
 *   <li>Root invariants (Connection wrapper rejection, {@code Sources} parameter rejection).</li>
 *   <li>Errors-channel lift on a polymorphic-of-{@code @error} return type, with
 *       {@link Resolved.Polymorphic} as the non-errors fallback.</li>
 * </ul>
 *
 * <p>Each classify arm projects {@link Resolved.Success} into its parent-context-specific
 * {@code GraphitronField} variant and handles parent-context-only concerns (join-path parse for
 * child sites, the class-backed-parent DEFERRED rejection for result-parent's Result/Scalar arms).
 *
 * <p>Root vs child is signalled by {@code parentPkColumns}; see {@link #resolve}.
 *
 * <p>The helpers this resolver calls back into ({@code parseExternalRef},
 * {@code parseContextArguments}, {@code buildWrapper}, {@code liftToErrorsField}) are
 * package-private members on {@link FieldBuilder}, shared with the other directive resolvers.
 */
final class ServiceDirectiveResolver {

    /**
     * Outcome of {@link #resolve}; the caller exhausts the arms with a switch.
     * {@link Success} arms differ by resolved return-type shape and carry the resolved
     * {@link MethodRef}; {@link ErrorsLifted} and {@link Rejected} are terminal values the
     * caller surfaces directly.
     */
    sealed interface Resolved {
        /** Successful resolution; arms differ by return-type shape. */
        sealed interface Success extends Resolved {
            MethodRef method();
        }
        record TableBound(ReturnTypeRef.TableBoundReturnType returnType, MethodRef method) implements Success {}
        record Result(ReturnTypeRef.ResultReturnType returnType, MethodRef method) implements Success {}
        record Scalar(ReturnTypeRef.ScalarReturnType returnType, MethodRef method) implements Success {}
        /**
         * A multitable polymorphic return. The service hands back a PK-populated
         * {@code TableRecord} per branch; the fetcher dispatches on each returned record's runtime
         * class to pick the participant, then auto-fetches the selected columns by PK. The classify
         * site narrows this to a distinct-table multitable <em>interface</em>: a union return is
         * rejected and a single-table discriminated interface is deferred.
         */
        record Polymorphic(ReturnTypeRef.PolymorphicReturnType returnType, MethodRef method) implements Success {}
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

        // Strict return-type validation applies to root @service fields only: root fields hand the
        // value straight to graphql-java, so the framework must know its exact shape. Child
        // @service uses DataLoader-batched semantics where the method takes Sources keys and
        // returns a flat or keyed shape that doesn't directly match the field's return type;
        // validateChildServiceReturnType owns that shape.
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
     * ({@link SourceKey.Wrap.TableRecord}): the declared {@code Set<X>} / {@code List<X>} element
     * {@code X} must be the parent type's backing record class, otherwise the emitted
     * {@code ((Record) env.getSource()).into(Tables.X)} extraction would silently project the
     * parent's runtime record into a wrong-typed {@code TableRecord}. The
     * {@link SourceKey.Wrap.Row} / {@link SourceKey.Wrap.Record} arms carry no typed record
     * class to mismatch.
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
                    : new Resolved.Polymorphic(p, method);
            }
        };
    }

    /**
     * Invariant check shared by the Query and Mutation root {@code @service} arms; returns the
     * rejection reason, or {@code null}.
     *
     * <p>The structural return-type check for the List-cardinality TableBound arm lives in
     * {@link #validateRootListTableBoundReturnPair} so its rejection wears the same
     * {@code "service method could not be resolved"} prefix as the catalog's strict Single-arm
     * rejection.
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
     * List-cardinality {@link ReturnTypeRef.TableBoundReturnType}; returns the rejection reason,
     * or {@code null}. The method may declare either {@code org.jooq.Result<XRecord>} or
     * {@code java.util.List<XRecord>}: graphql-java treats both identically (Result extends
     * List), and the emitter reads {@link MethodRef#returnType()} to declare whichever shape the
     * developer chose. Single cardinality is validated strictly inside
     * {@link ServiceCatalog#reflectServiceMethod} via its {@code expectedReturnType} parameter.
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
     * The expected return type a root {@code @service} method must declare, as a structured
     * javapoet {@link TypeName}, or {@code null} when no strict validation applies (the caller
     * then treats the reflection-captured return type as truth): List-cardinality TableBound
     * defers to {@link #validateRootListTableBoundReturnPair}, class-backed Result shapes defer
     * to the payload-class check in {@code FieldBuilder.buildServiceField}, and scalar coercion
     * is graphql-java's concern.
     */
    private TypeName computeExpectedServiceReturnType(ReturnTypeRef returnType) {
        // Connection cardinality is rejected by validateRootInvariants downstream of this helper;
        // skip the return-type check so that more specific message fires rather than a
        // return-type mismatch masking it.
        if (returnType.wrapper() instanceof FieldWrapper.Connection) return null;
        boolean isList = returnType.wrapper().isList();
        return switch (returnType) {
            case ReturnTypeRef.TableBoundReturnType tb -> {
                ClassName recordCls = tb.table().recordClass();
                yield isList ? null : recordCls;
            }
            case ReturnTypeRef.ResultReturnType r -> {
                // Class-backed payloads must return the SDL payload class directly; the strict
                // TypeName-equals check lives in FieldBuilder.buildServiceField, which names the
                // payload class in its diagnostic. Skip here so that precise reject fires.
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
     * on the generated {@code return ServiceClass.method(...)} line. The construction defers to
     * {@link RowsMethodShape}; the emitter calls the same helper for {@code .returns(...)}, so
     * the two cannot drift.
     *
     * <p>Returns {@code null} (skip) when the schema carries no derivable shape
     * ({@link ReturnTypeRef.ResultReturnType} with no backing class,
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
            // recoverable: the schema cannot name V, so recover it by peeling the developer's
            // declared outer type (RowsMethodShape.perKeyFromOuter, the same leaf resolution the
            // emitter's elementType() uses) and re-derive the expected outer type from it. This
            // still rejects a wrong key type or a missing list-nesting level; the leaf itself is
            // accepted as whatever the method yields, so the equals check below is effectively a
            // key-type check for this branch. A shape too malformed to peel (a List where a Map is
            // required, a raw type) is rejected here rather than left to miscompile on the
            // generated return line. Other null-perKey returns carry no derivable shape and stay
            // skipped.
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
