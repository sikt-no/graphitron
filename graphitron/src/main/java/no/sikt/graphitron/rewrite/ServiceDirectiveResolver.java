package no.sikt.graphitron.rewrite;

import graphql.schema.GraphQLFieldDefinition;
import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.ParameterizedTypeName;
import no.sikt.graphitron.javapoet.TypeName;
import no.sikt.graphitron.rewrite.model.ColumnRef;
import no.sikt.graphitron.rewrite.model.FieldWrapper;
import no.sikt.graphitron.rewrite.model.GraphitronField;
import no.sikt.graphitron.rewrite.model.GraphitronType;
import no.sikt.graphitron.rewrite.model.LoaderRegistration;
import no.sikt.graphitron.rewrite.model.MethodRef;
import no.sikt.graphitron.rewrite.model.ReflectionError;
import no.sikt.graphitron.rewrite.model.Rejection;
import no.sikt.graphitron.rewrite.model.ReturnTypeRef;
import no.sikt.graphitron.rewrite.model.RowsMethodShape;
import no.sikt.graphitron.rewrite.model.ServiceMethodCallError;
import no.sikt.graphitron.rewrite.model.SourceKey;
import no.sikt.graphitron.rewrite.model.TableRef;
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
 * table-parent).
 *
 * <p>{@link #resolve} runs three phases in order, and the order is the contract. The SDL is
 * the contract and the Java signature is fitted to it, so a defect in the field's shape is
 * reported before a defect in the coordinate's ability to host the signature, which is reported
 * before a defect in the signature's fit, which is reported before a defect in name binding:
 *
 * <ul>
 *   <li><b>Decode</b> ({@link ServiceCatalog#decodeServiceMethod}) reflects the method into a
 *       {@link ServiceCatalog.ServiceSignature}, plus the claim reduction that turns each
 *       parameter into a {@link ServiceCatalog.ParamRole}. Neither reads the coordinate.</li>
 *   <li><b>Classify</b> ({@link #classify}) decides every rejection expressible from the
 *       signature fact, the {@link ParentContext} and the field's SDL shape, before any
 *       parameter binds.</li>
 *   <li><b>Bind</b> ({@link ServiceCatalog#bindServiceMethod}) extracts and mints, raising only
 *       binding-level rejections.</li>
 * </ul>
 *
 * <p>Post-bind, {@code InputBeanResolver.enrich}, {@link #validateChildServiceReturnType} and
 * {@link #projectReturnType} run in that order. The child return-shape check is signature-level,
 * not coordinate-level, so it stays behind binding.
 *
 * <p>Each classify arm of {@link FieldBuilder} projects {@link Resolved.Success} into its
 * parent-context-specific {@code GraphitronField} variant and handles payload and parent-arm
 * semantics only; the resolver owns coordinate and signature verdicts.
 *
 * <p>The helpers this resolver calls back into ({@code parseExternalRef},
 * {@code parseContextArguments}, {@code buildWrapper}, {@code liftToErrorsField}) are
 * package-private members on {@link FieldBuilder}, shared with the other directive resolvers.
 */
final class ServiceDirectiveResolver {

    /**
     * Which coordinate hosts the {@code @service} field. The question, not the answer: the arms
     * carry what the coordinate <em>is</em>, and the classify phase reads a verdict off it.
     *
     * <p>This replaces a key-columns list whose emptiness doubled as the root signal. Two of the
     * three coordinates were indistinguishable under that encoding, root sites and record-backed
     * parents both arriving as an empty list, so a record-parent verdict could not be decided by
     * a resolver that could not see the coordinate. Splitting coordinate identity from the key
     * columns is the point; a third marker alongside the list would have kept the fusion and
     * added a representable-but-illegal combination.
     */
    sealed interface ParentContext {
        /** Query / Mutation root: no parent context. */
        record Root() implements ParentContext {}
        /** Child of a {@code @table}-backed parent; the table carries the PK columns and the name. */
        record TableParent(TableRef table) implements ParentContext {}
        /** Child of a class-backed (record / POJO) parent, carrying the parent's resolved type. */
        record RecordParent(GraphitronType.ResultType parentType) implements ParentContext {}
    }

    /**
     * Which return-type validation regime a coordinate is under. A derived axis rather than a
     * {@code Root || RecordParent} disjunction spelled at each read: an exhaustive switch means
     * a new coordinate is a compile error, and moving a coordinate between regimes is a one-arm
     * change at one seat.
     */
    enum Regime {
        /** The field hands its value straight to graphql-java, so the shape must match exactly. */
        STRICT_ROOT,
        /** DataLoader-batched: the method takes keys and returns a keyed or flat batch shape. */
        BATCHED_CHILD
    }

    /**
     * The regime a coordinate is under. Record-backed parents currently share the root
     * return-type regime: their children are not yet batched, so the strict comparison and the
     * Connection rejection still apply to them.
     */
    private static Regime regimeOf(ParentContext parent) {
        return switch (parent) {
            case ParentContext.Root ignored -> Regime.STRICT_ROOT;
            case ParentContext.RecordParent ignored -> Regime.STRICT_ROOT;
            case ParentContext.TableParent ignored -> Regime.BATCHED_CHILD;
        };
    }

    /** The batch key the coordinate supplies: the parent table's primary key, or nothing. */
    private static List<ColumnRef> parentPkColumnsOf(ParentContext parent) {
        return switch (parent) {
            case ParentContext.Root ignored -> List.of();
            case ParentContext.RecordParent ignored -> List.of();
            case ParentContext.TableParent tp -> tp.table().primaryKeyColumns();
        };
    }

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
     * Resolves {@code @service} on {@code fieldDef} at the coordinate {@code parent} names.
     *
     * <p>The phase order (decode, claim reduction, classify, bind) is the item this method
     * exists to guarantee: a coordinate-level verdict is decided before any parameter binds, so
     * it can no longer be masked by a parameter declared before the one it concerns.
     */
    Resolved resolve(String parentTypeName, GraphQLFieldDefinition fieldDef, ParentContext parent) {
        String rawTypeName = baseTypeName(fieldDef);
        String elementTypeName = ctx.isConnectionType(rawTypeName)
            ? ctx.connectionElementTypeName(rawTypeName)
            : rawTypeName;
        ReturnTypeRef returnType = ctx.resolveReturnType(elementTypeName, fb.buildWrapper(fieldDef));

        FieldBuilder.ExternalRef serviceRef = fb.parseExternalRef(fieldDef, DIR_SERVICE, ARG_SERVICE_REF);
        if (serviceRef != null && serviceRef.argMappingError() != null) {
            return new Resolved.Rejected(Rejection.structural("service method could not be resolved — @service " + serviceRef.argMappingError()));
        }

        List<String> contextArgs = fb.parseContextArguments(fieldDef, DIR_SERVICE);
        var slotTypes = FieldBuilder.argSlotTypes(fieldDef);
        var argMapping = serviceRef != null ? serviceRef.argMapping() : Map.<String, List<String>>of();
        var argBindingsResult = ArgBindingMap.of(slotTypes, argMapping);
        if (argBindingsResult instanceof ArgBindingMap.Result.Failure f) {
            return new Resolved.Rejected(Rejection.structural("service method could not be resolved — @service " + f.message()));
        }
        var argBindings = ((ArgBindingMap.Result.Ok) argBindingsResult).map();
        var ctxKeys = new HashSet<>(contextArgs);

        var decoded = svc.decodeServiceMethod(serviceRef.className(), serviceRef.methodName(), ctxKeys);
        if (decoded.failed()) {
            return new Resolved.Rejected(decoded.rejection().prefixedWith("service method could not be resolved — "));
        }
        var signature = decoded.signature();
        var claims = svc.reduceClaims(signature, argBindings, ctxKeys, slotTypes);

        // The errors lift is pure over SDL, so the probe moves ahead of binding together with the
        // child-polymorphic deferral it guards; a lifting return proceeds to bind and projects as
        // ErrorsLifted, unchanged in outcome.
        GraphitronField lifted = returnType instanceof ReturnTypeRef.PolymorphicReturnType p
            ? fb.liftToErrorsField(fieldDef, parentTypeName, p)
            : null;

        Rejection coordinateVerdict = classify(parentTypeName, parent, returnType, signature, claims, lifted);
        if (coordinateVerdict != null) {
            return new Resolved.Rejected(coordinateVerdict);
        }

        var result = svc.bindServiceMethod(signature, claims, argBindings, ctxKeys,
            parentPkColumnsOf(parent), slotTypes);
        if (result.failed()) {
            return new Resolved.Rejected(result.rejection().prefixedWith("service method could not be resolved — "));
        }
        var beanResult = inputBeans.enrich((MethodRef.Service) result.ref(), fieldDef);
        if (beanResult instanceof InputBeanResolver.Result.Failed f) {
            return new Resolved.Rejected(f.rejection().prefixedWith("service method could not be resolved — "));
        }
        MethodRef method = ((InputBeanResolver.Result.Ok) beanResult).method();

        if (regimeOf(parent) == Regime.BATCHED_CHILD) {
            String mismatch = validateChildServiceReturnType(returnType, method);
            if (mismatch != null) {
                return new Resolved.Rejected(Rejection.structural(mismatch));
            }
        }

        return projectReturnType(returnType, method, lifted);
    }

    /**
     * The classify phase: every rejection expressible from the decoded signature, the coordinate
     * and the field's SDL shape, decided before a single parameter binds. Returns the winning
     * rejection, or {@code null} when the coordinate can host the signature.
     *
     * <p>The arms are ordered by what they are about, not by what is cheap to compute: field
     * shape, then coordinate, then signature fit. Everything below this method's return is
     * binding.
     */
    private Rejection classify(String parentTypeName, ParentContext parent, ReturnTypeRef returnType,
            ServiceCatalog.ServiceSignature signature, ServiceCatalog.ClaimedParams claims,
            GraphitronField lifted) {
        Regime regime = regimeOf(parent);

        // A polymorphic return that does not lift to an errors channel is supported on root
        // @service fields only. The deferral is hoisted here so it outranks binding and the join-
        // path parse both child classify sites run between resolve() and their switch.
        if (returnType instanceof ReturnTypeRef.PolymorphicReturnType
                && lifted == null
                && !(parent instanceof ParentContext.Root)) {
            return Rejection.deferred(
                "child @service returning a polymorphic type (interface/union) is not yet supported"
                + " — route (a) restores it on root @service fields only");
        }

        // Field shape outranks everything below: a Connection return is rejected even when a
        // parameter is also misnamed and even when the signature also declares a batch-shaped one.
        if (regime == Regime.STRICT_ROOT && returnType.wrapper() instanceof FieldWrapper.Connection) {
            return Rejection.invalidSchema(
                "@service at the root does not support Connection return types — use [T] or T instead");
        }

        // The coordinate's answer to a SOURCES-shaped parameter.
        Rejection sourcesVerdict = classifySourcesCoordinate(parentTypeName, parent, returnType, signature, claims);
        if (sourcesVerdict != null) return sourcesVerdict;

        // Signature fit, last before binding: the two halves of one fact, reunited. Single
        // cardinality compares the whole return type; the List-cardinality TableBound arm accepts
        // either jOOQ Result or java.util.List of the record class.
        if (regime == Regime.STRICT_ROOT) {
            TypeName expected = computeExpectedServiceReturnType(returnType);
            if (expected != null && !signature.returnType().equals(expected)) {
                return new ReflectionError.ReturnTypeMismatch(signature.className(), signature.methodName(),
                    TypeNames.simple(expected), TypeNames.simple(signature.returnType()))
                    .prefixedWith("service method could not be resolved — ");
            }
            String pairMismatch = validateRootListTableBoundReturnPair(returnType, signature);
            if (pairMismatch != null) {
                return Rejection.structural("service method could not be resolved — " + pairMismatch);
            }
        }
        return null;
    }

    /**
     * The coordinate's answer to every {@link ServiceCatalog.ParamRole.SourcesCandidate} the
     * claim reduction carried. One arm per coordinate; the answer never depends on where the
     * candidate sits in the declaration order, which is the declaration-order dependence this
     * phase split removes.
     */
    private Rejection classifySourcesCoordinate(String parentTypeName, ParentContext parent,
            ReturnTypeRef returnType, ServiceCatalog.ServiceSignature signature,
            ServiceCatalog.ClaimedParams claims) {
        ServiceCatalog.DecodedParam firstCandidate = null;
        ServiceCatalog.SourcesShape firstShape = null;
        for (int i = 0; i < signature.params().size(); i++) {
            if (claims.roleOf(i) instanceof ServiceCatalog.ParamRole.SourcesCandidate c) {
                firstCandidate = signature.params().get(i);
                firstShape = c.shape();
                break;
            }
        }
        switch (parent) {
            case ParentContext.Root ignored -> {
                if (firstShape == null) return null;
                // List<XRecord> at root is the canonical InputBeanResolver input-bean shape, not a
                // coordinate claim; binding owns it and its arg-mismatch fallback. Only the
                // anonymous-key wraps are answered here.
                if (firstShape.wrap() instanceof SourceKey.Wrap.TableRecord) return null;
                return Rejection.structural("@service at the root does not support "
                    + "List<Row>/List<Record> batch parameters — the root "
                    + "has no parent context to batch against")
                    .prefixedWith("service method could not be resolved — ");
            }
            case ParentContext.TableParent tp -> {
                if (firstShape == null) return null;
                if (tp.table().primaryKeyColumns().isEmpty()) {
                    return new ServiceMethodCallError.SourcesOnPkLessParent(
                        firstCandidate.displayName(), signature.methodName(),
                        parentTypeName, tp.table().tableName())
                        .prefixedWith("service method could not be resolved — ");
                }
                String elementMismatch = validateTableRecordSourceParentTable(
                    parentTypeName, signature, firstShape);
                return elementMismatch == null ? null : Rejection.structural(elementMismatch);
            }
            case ParentContext.RecordParent ignored -> {
                // Two triggers, and the second is not redundant. A batch-shaped signature is the
                // case this arm exists for, but a Result or Scalar resolved return type has to
                // reject here with no candidate in sight: that pairing is unsupported at this
                // coordinate whatever the signature says, and gating on candidacy alone would
                // silently make a Sources-less record-parent @service legal. A TableBound return
                // with no candidate keeps classifying.
                if (firstShape == null
                        && !(returnType instanceof ReturnTypeRef.ResultReturnType)
                        && !(returnType instanceof ReturnTypeRef.ScalarReturnType)) {
                    return null;
                }
                return Rejection.deferred(
                    "@service on a record-backed parent is not yet supported; the batch key "
                    + "must be lifted through the parent chain to the rooted @table");
            }
        }
    }

    /**
     * Parent-table consistency check for the typed-{@code TableRecord} source-shape arm
     * ({@link SourceKey.Wrap.TableRecord}): the declared {@code Set<X>} / {@code List<X>} element
     * {@code X} must be the parent type's backing record class, otherwise the emitted extraction
     * would set the parent's key columns on a record of an unrelated table, which does not even
     * declare them. The {@link SourceKey.Wrap.Row} / {@link SourceKey.Wrap.Record} arms carry no
     * typed record class to mismatch.
     */
    private String validateTableRecordSourceParentTable(String parentTypeName,
            ServiceCatalog.ServiceSignature signature, ServiceCatalog.SourcesShape shape) {
        if (!(shape.wrap() instanceof SourceKey.Wrap.TableRecord tr)) return null;
        var expected = ctx.recordClassForTypeName(parentTypeName).orElse(null);
        if (expected == null) return null;
        ClassName expectedClassName = ClassName.get(expected);
        if (expectedClassName.equals(tr.className())) return null;
        return "method '" + signature.methodName() + "' in class '" + signature.className()
            + "' takes Sources element type '" + tr.className().simpleName()
            + "' but the parent type '" + parentTypeName
            + "' is backed by '" + expected.getSimpleName()
            + "' — change the Sources element to '" + expected.getSimpleName()
            + "', or use a Row1/Record1 source-shape if the typed record isn't needed";
    }

    private Resolved projectReturnType(ReturnTypeRef returnType, MethodRef method,
                                       GraphitronField lifted) {
        return switch (returnType) {
            case ReturnTypeRef.TableBoundReturnType tb -> new Resolved.TableBound(tb, method);
            case ReturnTypeRef.ResultReturnType r -> new Resolved.Result(r, method);
            case ReturnTypeRef.ScalarReturnType s -> new Resolved.Scalar(s, method);
            case ReturnTypeRef.PolymorphicReturnType p -> lifted != null
                ? new Resolved.ErrorsLifted(lifted)
                : new Resolved.Polymorphic(p, method);
        };
    }

    /**
     * Structural-return check for root {@code @service} fields whose resolved return type is a
     * List-cardinality {@link ReturnTypeRef.TableBoundReturnType}; returns the rejection reason,
     * or {@code null}. The method may declare either {@code org.jooq.Result<XRecord>} or
     * {@code java.util.List<XRecord>}: graphql-java treats both identically (Result extends
     * List), and the emitter reads {@link MethodRef#returnType()} to declare whichever shape the
     * developer chose. Single cardinality is validated by the strict comparison beside this
     * check in {@link #classify}; the two are arms of one fact and run in one place.
     */
    private static String validateRootListTableBoundReturnPair(ReturnTypeRef returnType,
            ServiceCatalog.ServiceSignature signature) {
        if (!(returnType instanceof ReturnTypeRef.TableBoundReturnType tb)) return null;
        if (!returnType.wrapper().isList()) return null;
        ClassName recordCls = tb.table().recordClass();
        TypeName expectedResult = ParameterizedTypeName.get(ClassName.get("org.jooq", "Result"), recordCls);
        TypeName expectedList = ParameterizedTypeName.get(ClassName.get("java.util", "List"), recordCls);
        TypeName actual = signature.returnType();
        if (actual.equals(expectedResult) || actual.equals(expectedList)) return null;
        return "method '" + signature.methodName() + "' in class '" + signature.className()
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
        // Connection cardinality is rejected by the field-shape arm above this check; skip the
        // return-type check so that more specific message fires rather than a return-type
        // mismatch masking it.
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
     * shape the rendered rows method carries:
     * {@code Map<KeyType, V>} or {@code Map<KeyType, List<V>>} for mapped variants,
     * {@code List<V>} or {@code List<List<V>>} for positional variants. The developer's method
     * return type must equal that expected outer type exactly (per {@link TypeName#equals}); a
     * mismatch is rejected at classify time rather than left to surface as a {@code javac} error
     * on the generated {@code return ServiceClass.method(...)} line. The construction defers to
     * {@link RowsMethodShape}; this acceptance is what lets the launcher renderer's delegate
     * arm declare the developer method's own return type verbatim, so the two cannot drift.
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
