package no.sikt.graphitron.rewrite.walker;

import graphql.schema.GraphQLFieldDefinition;
import graphql.schema.GraphQLInputObjectType;
import graphql.schema.GraphQLType;
import graphql.schema.GraphQLTypeUtil;
import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.ParameterizedTypeName;
import no.sikt.graphitron.javapoet.TypeName;
import no.sikt.graphitron.model.catalog.SchemaCoordinateSyntax;
import no.sikt.graphitron.render.CatalogRefs;
import no.sikt.graphitron.rewrite.PathExpr;
import no.sikt.graphitron.rewrite.model.ArgPath;
import no.sikt.graphitron.rewrite.model.CallSiteExtraction;
import no.sikt.graphitron.rewrite.model.MappingEntry;
import no.sikt.graphitron.rewrite.model.MethodRef;
import no.sikt.graphitron.rewrite.model.ParamSource;
import no.sikt.graphitron.model.diagnostics.Rejection;
import no.sikt.graphitron.rewrite.model.ServiceMethodCall;
import no.sikt.graphitron.model.diagnostics.ServiceMethodCallError;
import no.sikt.graphitron.rewrite.model.ValueShape;
import no.sikt.graphitron.rewrite.model.WalkerResult;

import java.util.ArrayList;
import java.util.List;

/**
 * Walker that produces a {@link ServiceMethodCall} carrier from an already-resolved
 * {@link MethodRef.Service}, the intermediate produced upstream by
 * {@code ServiceDirectiveResolver}/{@code ServiceCatalog}; this class translates that
 * shape and does no reflection of its own.
 *
 * <h3>Stage layout</h3>
 *
 * <ol>
 *   <li>Project each {@link MethodRef.Param} onto a {@link MappingEntry} arm
 *       ({@link MappingEntry.FromDsl}/{@link MappingEntry.FromContext}/{@link MappingEntry.FromArg}).
 *   <li>Derive each {@link MappingEntry.FromArg}'s {@link ValueShape} from the
 *       {@link ParamSource.Arg} payload's {@link CallSiteExtraction} arm.
 *   <li>Enforce the cross-round invariants: a method round may carry at most one
 *       {@link MappingEntry.FromDsl}; a constructor round may not carry a
 *       {@link MappingEntry.FromArg}.
 *   <li>Wrap into {@link ServiceMethodCall.Static} or {@link ServiceMethodCall.Instance}
 *       based on {@link MethodRef.Service#callShape()}.
 * </ol>
 */
public final class ServiceMethodCallWalker {

    private static final ClassName DSL_CONTEXT = ClassName.get("org.jooq", "DSLContext");

    /**
     * Translates a resolved {@link MethodRef.Service} into a {@link ServiceMethodCall}.
     * {@code parentTypeName} and {@code fieldDef} name the service field; they are read only to
     * stamp each {@link ValueShape.Scalar} leaf with the schema coordinate of the SDL slot it
     * reads ({@link ValueShape.Scalar#definition()}). Returns {@link WalkerResult.Ok} on success
     * or {@link WalkerResult.Err} carrying typed {@link ServiceMethodCallError} arms.
     */
    public WalkerResult<ServiceMethodCall> walk(String parentTypeName, GraphQLFieldDefinition fieldDef,
                                                MethodRef.Service method) {
        List<Rejection.AuthorError> errors = new ArrayList<>();
        var site = new Site(parentTypeName, fieldDef);

        List<MappingEntry> methodArgs = new ArrayList<>();
        int dslSlotsInMethod = 0;
        for (MethodRef.Param p : method.params()) {
            if (!(p instanceof MethodRef.Param.Typed typed)) {
                // Param.Sourced (DataLoader batch keys) does not appear on root @service paths.
                continue;
            }
            MappingEntry entry = projectParam(typed, method, site, errors);
            if (entry == null) {
                continue;
            }
            if (entry instanceof MappingEntry.FromDsl) {
                dslSlotsInMethod++;
            }
            methodArgs.add(entry);
        }

        if (dslSlotsInMethod > 1) {
            errors.add(new ServiceMethodCallError.MultipleDslContextSlots(
                method.className(), ServiceMethodCallError.Round.METHOD));
        }

        ServiceMethodCall carrier = switch (method.callShape()) {
            case MethodRef.CallShape.Static s ->
                new ServiceMethodCall.Static(
                    method.className(), method.methodName(), methodArgs, method.returnType());
            case MethodRef.CallShape.InstanceWithDslHolder holder ->
                new ServiceMethodCall.Instance(
                    method.className(),
                    ctorArgs(holder, method, site, errors),
                    method.methodName(),
                    methodArgs,
                    method.returnType());
        };

        if (!errors.isEmpty()) {
            return new WalkerResult.Err<>(errors);
        }
        return new WalkerResult.Ok<>(carrier);
    }

    /**
     * Projects the holder constructor's resolved parameter sources onto {@code ctorArgs}
     * entries. Each {@link MethodRef.Param} is a {@link ParamSource.DslContext} or
     * {@link ParamSource.Context} by construction (the producer's {@code resolveInstanceHolder}
     * only binds those), so the cross-round invariant forbidding {@link MappingEntry.FromArg}
     * in {@code ctorArgs} holds structurally. More than one {@code DSLContext} slot raises
     * {@link ServiceMethodCallError.MultipleDslContextSlots} under the {@code CTOR} round.
     */
    private List<MappingEntry> ctorArgs(
        MethodRef.CallShape.InstanceWithDslHolder holder,
        MethodRef.Service method,
        Site site,
        List<Rejection.AuthorError> errors
    ) {
        List<MappingEntry> entries = new ArrayList<>();
        int dslSlots = 0;
        for (MethodRef.Param p : holder.ctorParams()) {
            if (!(p instanceof MethodRef.Param.Typed typed)) {
                continue;
            }
            MappingEntry entry = projectParam(typed, method, site, errors);
            if (entry == null) {
                continue;
            }
            if (entry instanceof MappingEntry.FromDsl) {
                dslSlots++;
            }
            entries.add(entry);
        }
        if (dslSlots > 1) {
            errors.add(new ServiceMethodCallError.MultipleDslContextSlots(
                method.className(), ServiceMethodCallError.Round.CTOR));
        }
        return entries;
    }

    private MappingEntry projectParam(
        MethodRef.Param.Typed param,
        MethodRef.Service method,
        Site site,
        List<Rejection.AuthorError> errors
    ) {
        return switch (param.source()) {
            case ParamSource.DslContext ignored -> new MappingEntry.FromDsl();
            case ParamSource.Context ignored ->
                new MappingEntry.FromContext(param.name(), param.javaType(), param.name());
            case ParamSource.Arg arg -> new MappingEntry.FromArg(param.name(),
                deriveValueShape(arg, param.javaType(), site, errors));
            case ParamSource.SessionHandle ignored ->
                new MappingEntry.FromSessionHandle(param.name(), param.javaType());
            // Table / SourceTable / SourceColumn / Sources / SessionSeam / SessionTenant don't appear on root
            // sync @service permits. Defensive fallback: skip and let upstream rejection surface.
            case ParamSource.Table ignored -> null;
            case ParamSource.SourceTable ignored -> null;
            case ParamSource.SourceColumn ignored -> null;
            case ParamSource.Sources ignored -> null;
            case ParamSource.SessionSeam ignored -> null;
            case ParamSource.SessionTenant ignored -> null;
        };
    }

    private ValueShape deriveValueShape(
        ParamSource.Arg arg,
        TypeName javaType,
        Site site,
        List<Rejection.AuthorError> errors
    ) {
        ArgPath path = toArgPath(arg.path());
        CallSiteExtraction extraction = arg.extraction();

        // Unwrap NestedInputField: its leaf carries the actual scalar transform and the path
        // already encodes the multi-segment descent.
        if (extraction instanceof CallSiteExtraction.NestedInputField nested) {
            CallSiteExtraction leaf = nested.leaf();
            if (isLeaf(leaf)) {
                return new ValueShape.Scalar(javaType, path, leaf, site.definitionOf(path));
            }
            // A non-leaf inside a NestedInputField at @service is not produced by the
            // existing classifier; record as parameter-unbindable and fall through with a Direct.
            errors.add(new ServiceMethodCallError.ParameterUnbindable(
                arg.path().headName(),
                List.of(),
                "nested input-field with non-leaf transform"));
            return new ValueShape.Scalar(javaType, path, new CallSiteExtraction.Direct(),
                site.definitionOf(path));
        }

        if (extraction instanceof CallSiteExtraction.InputBean bean) {
            ValueShape shape = inputBeanToValueShape(bean, path);
            if (isListType(javaType, bean.beanClass())) {
                return new ValueShape.ListOf(path, shape);
            }
            return shape;
        }

        // A jOOQ TableRecord param (singular or List<…>). The carrier holds the column / identity
        // bindings; JooqRecordInput is a path-carrying leaf (no per-field children), so it carries its
        // own sdlPath. Cardinality is handled exactly as the InputBean arm above: read list-ness from
        // the Java type alone (sound because InputBeanResolver's :elt.list() != sdl.list() parity check
        // already aligned the Java type with the SDL arg) and wrap the element in the existing ListOf.
        if (extraction instanceof CallSiteExtraction.JooqRecord jr) {
            ValueShape shape = new ValueShape.JooqRecordInput(jr, path);
            if (isListType(javaType, CatalogRefs.recordClass(jr.table()))) {
                return new ValueShape.ListOf(path, shape);
            }
            return shape;
        }

        if (isLeaf(extraction)) {
            return new ValueShape.Scalar(javaType, path, extraction, site.definitionOf(path));
        }

        // ContextArg at @service Arg slot is not produced by the current resolver; defensive.
        errors.add(new ServiceMethodCallError.ParameterUnbindable(
            arg.path().headName(),
            List.of(),
            "unexpected extraction shape " + extraction.getClass().getSimpleName()));
        return new ValueShape.Scalar(javaType, path, new CallSiteExtraction.Direct(),
            site.definitionOf(path));
    }

    private ValueShape inputBeanToValueShape(CallSiteExtraction.InputBean bean, ArgPath path) {
        List<ValueShape.FieldBinding> fields = new ArrayList<>(bean.fields().size());
        for (CallSiteExtraction.FieldBinding fb : bean.fields()) {
            // Fold the whole access path onto the ArgPath, so a leaf hoisted out of a grouping
            // input addresses the same wire position it occupies on the client's request. Group
            // segments are never list-lifting: a list-shaped grouping input is rejected at
            // classification, and the leaf's own list-ness keeps riding fb.list() into the ListOf
            // wrap below.
            ArgPath leafPath = path;
            for (String segment : fb.accessPath()) {
                leafPath = leafPath.append(segment);
            }
            ValueShape childShape = fieldBindingShape(fb, leafPath);
            fields.add(new ValueShape.FieldBinding(fb.accessPath(), fb.javaFieldName(), childShape,
                fb.definition()));
        }
        return switch (bean.target()) {
            case RECORD -> new ValueShape.RecordInput(bean.beanClass(), fields);
            case JAVA_BEAN -> new ValueShape.JavaBeanInput(bean.beanClass(), fields);
        };
    }

    private ValueShape fieldBindingShape(CallSiteExtraction.FieldBinding fb, ArgPath path) {
        CallSiteExtraction leaf = fb.leaf();
        // The polymorphic leaf's own admitted slot type rather than the declared name: the member is
        // declared as a supertype, which a signature spells with its generic argument
        // (org.jooq.UpdatableRecord<?>), and the admitted type is that supertype's raw class, which
        // is what the container helper returns.
        TypeName elementType =
            leaf instanceof CallSiteExtraction.NodeIdDecodePolymorphicRecord poly
                ? poly.slotType().typeName()
                : ClassName.bestGuess(fb.javaElementTypeName());

        ValueShape inner;
        if (leaf instanceof CallSiteExtraction.InputBean nestedBean) {
            inner = inputBeanToValueShape(nestedBean, path);
        } else if (leaf instanceof CallSiteExtraction.NodeIdDecodeRecord
                || leaf instanceof CallSiteExtraction.NodeIdDecodePolymorphicRecord) {
            // A jOOQ-record member decoded from @nodeId, at one node type or over a container's
            // members. Carry the leaf through unchanged so the create<Bean> helper emits the decode
            // call; downgrading to Direct would pass the wire String where a record is expected and
            // throw ClassCastException at runtime.
            inner = new ValueShape.Scalar(elementType, path, leaf, fb.definition());
        } else if (isLeaf(leaf)) {
            inner = new ValueShape.Scalar(elementType, path, leaf, fb.definition());
        } else {
            // Non-leaf, non-bean shapes are validator-rejected upstream; fall back to Direct.
            inner = new ValueShape.Scalar(elementType, path, new CallSiteExtraction.Direct(),
                fb.definition());
        }

        if (fb.list()) {
            return new ValueShape.ListOf(path, inner);
        }
        return inner;
    }

    /**
     * Whether an extraction turns one wire value into one Java value, which is what a
     * {@link ValueShape.Scalar} carries. The two record-decode leaves qualify at a parameter even
     * though their result is a record: one opaque id arrives and one record leaves, and the per-field
     * bindings a composite shape would carry are exactly what a decoded key tuple does not have.
     * That holds for the polymorphic leaf too, which one id in and one record out describes just as
     * well; which record is the wire value's answer, not the shape's.
     */
    private static boolean isLeaf(CallSiteExtraction extraction) {
        return extraction instanceof CallSiteExtraction.Direct
            || extraction instanceof CallSiteExtraction.EnumValueOf
            || extraction instanceof CallSiteExtraction.JooqConvert
            || extraction instanceof CallSiteExtraction.NodeIdDecodeKeys
            || extraction instanceof CallSiteExtraction.NodeIdDecodeRecord
            || extraction instanceof CallSiteExtraction.NodeIdDecodePolymorphicRecord;
    }

    private static boolean isListType(TypeName javaType, ClassName elementClass) {
        if (javaType instanceof ParameterizedTypeName ptn) {
            return ptn.rawType().equals(ClassName.get(List.class))
                && ptn.typeArguments().size() == 1
                && ptn.typeArguments().getFirst().equals(elementClass);
        }
        return false;
    }

    /**
     * The service field being walked, as the definition coordinates of its leaves need it: the
     * deepest declared SDL slot the leaf's path reaches. A top-level argument's coordinate is
     * {@code Type.field(arg:)}; a leaf an argument mapping reaches inside a nested input is the
     * input field its last segment names, found by walking the argument's input type down the
     * path. A mapping that continues past the SDL (a key column projected out of a decoded id)
     * reads a projection of the last declared slot, so that slot is its definition; whether such a
     * path is legal is the resolver's verdict, not this walk's. A bean member's leaf does not come
     * here: the resolver stamped its coordinate on the {@link CallSiteExtraction.FieldBinding}
     * where it held the input field.
     */
    private record Site(String parentTypeName, GraphQLFieldDefinition fieldDef) {

        String definitionOf(ArgPath path) {
            String definition = SchemaCoordinateSyntax.ofArgument(parentTypeName, fieldDef.getName(),
                path.outerArgName());
            var argument = fieldDef.getArgument(path.outerArgName());
            if (argument == null) {
                return definition;
            }
            GraphQLType type = GraphQLTypeUtil.unwrapAll(argument.getType());
            for (var segment : path.deeperSegments()) {
                if (!(type instanceof GraphQLInputObjectType input)
                        || input.getField(segment.name()) == null) {
                    break;
                }
                definition = SchemaCoordinateSyntax.ofField(input.getName(), segment.name());
                type = GraphQLTypeUtil.unwrapAll(input.getField(segment.name()).getType());
            }
            return definition;
        }
    }

    private static ArgPath toArgPath(PathExpr expr) {
        // PathExpr is recursive (Head / Step); flatten into outerArg + deeper segments. Each
        // Step's liftsList flag carries forward so the emitter can dispatch between Map.get
        // descent and stream/map element-wise descent at each depth.
        List<ArgPath.Segment> segments = new ArrayList<>();
        PathExpr cursor = expr;
        while (cursor instanceof PathExpr.Step step) {
            segments.addFirst(new ArgPath.Segment(step.fieldName(), step.liftsList()));
            cursor = step.parent();
        }
        if (cursor instanceof PathExpr.Head head) {
            return new ArgPath(head.name(), segments);
        }
        throw new IllegalStateException("unexpected PathExpr root: " + cursor);
    }
}
