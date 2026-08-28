package no.sikt.graphitron.rewrite;

import graphql.schema.GraphQLArgument;
import graphql.schema.GraphQLFieldDefinition;
import no.sikt.graphitron.javapoet.TypeName;
import no.sikt.graphitron.rewrite.model.CallSiteExtraction;
import no.sikt.graphitron.rewrite.model.ConditionFilter;
import no.sikt.graphitron.rewrite.model.HelperRef;
import no.sikt.graphitron.rewrite.model.MethodRef;
import no.sikt.graphitron.rewrite.model.ParamSource;
import no.sikt.graphitron.rewrite.model.Rejection;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Resolves the {@code @condition} concern: builds {@link ConditionFilter} / {@link ArgConditionRef}
 * from {@code @condition} directives on a GraphQL field or an individual argument, and rewraps a
 * {@link ConditionFilter} whose {@link ParamSource.Arg} params extract from a nested position
 * inside an outer input-argument {@code Map}. Sibling to {@link OrderByResolver},
 * {@link LookupMappingResolver}, and {@link PaginationResolver}.
 */
final class ConditionResolver {

    /**
     * Outcome of {@link #resolveArg}. {@link Rejected} carries a single fully-prefixed message
     * ready for the caller's accumulating errors list.
     */
    sealed interface ArgConditionResult {
        record None() implements ArgConditionResult {}
        record Ok(ArgConditionRef ref) implements ArgConditionResult {}
        record Rejected(Rejection rejection) implements ArgConditionResult {
            public String message() { return rejection.message(); }
        }
    }

    /**
     * Outcome of {@link #resolveField}. Same shape as {@link ArgConditionResult}, but the
     * {@code Ok} arm carries a {@link ConditionFilter} directly; only argument-level
     * conditions carry an override flag.
     */
    sealed interface FieldConditionResult {
        record None() implements FieldConditionResult {}
        record Ok(ConditionFilter filter) implements FieldConditionResult {}
        record Rejected(Rejection rejection) implements FieldConditionResult {
            public String message() { return rejection.message(); }
        }
    }

    private final BuildContext ctx;
    private final ServiceCatalog svc;

    ConditionResolver(BuildContext ctx, ServiceCatalog svc) {
        this.ctx = ctx;
        this.svc = svc;
    }

    /**
     * Builds an {@link ArgConditionRef} from a {@code @condition} directive on one GraphQL
     * argument. Reflects the condition method via {@link ServiceCatalog#reflectTableMethod},
     * binding the argument to its same-named Java parameter by default; {@code argMapping}
     * overrides that binding. {@code @field(name:)} on the argument is the column-binding axis
     * for the auto-equality path; the two axes coexist on the same slot.
     */
    ArgConditionResult resolveArg(GraphQLArgument arg) {
        var cond = ctx.readConditionDirective(arg);
        if (cond == null) return new ArgConditionResult.None();
        var argName = arg.getName();
        if (cond.argMappingError() != null) {
            return new ArgConditionResult.Rejected(Rejection.structural("argument '" + argName + "' @condition: " + cond.argMappingError()));
        }
        var bindingResult = ArgBindingMap.of(java.util.Map.of(argName, arg.getType()),
            cond.argMapping());
        if (bindingResult instanceof ArgBindingMap.Result.Failure f) {
            return new ArgConditionResult.Rejected(Rejection.structural("argument '" + argName + "' @condition: " + f.message()));
        }
        var argBindings = ((ArgBindingMap.Result.Ok) bindingResult).map();
        var slotTypes = java.util.Map.of(argName, arg.getType());
        var result = svc.reflectTableMethod(cond.className(), cond.methodName(),
            argBindings, Set.copyOf(cond.contextArguments()), slotTypes);
        if (result.failed()) {
            return new ArgConditionResult.Rejected(result.rejection().prefixedWith("argument '" + argName + "' @condition: "));
        }
        var methodRef = result.ref();
        return new ArgConditionResult.Ok(new ArgConditionRef(
            new ConditionFilter(methodRef.className(), methodRef.methodName(), methodRef.params()),
            cond.override()));
    }

    /**
     * Builds a field-level {@link ConditionFilter} from a {@code @condition} directive on the
     * field definition. Same reflection contract as {@link #resolveArg}, but every field
     * argument is available as a binding.
     */
    FieldConditionResult resolveField(GraphQLFieldDefinition fieldDef) {
        var cond = ctx.readConditionDirective(fieldDef);
        if (cond == null) return new FieldConditionResult.None();
        if (cond.argMappingError() != null) {
            return new FieldConditionResult.Rejected(Rejection.structural("field '" + fieldDef.getName() + "' @condition: " + cond.argMappingError()));
        }
        var bindingResult = ArgBindingMap.of(FieldBuilder.argSlotTypes(fieldDef), cond.argMapping());
        if (bindingResult instanceof ArgBindingMap.Result.Failure f) {
            return new FieldConditionResult.Rejected(Rejection.structural("field '" + fieldDef.getName() + "' @condition: " + f.message()));
        }
        var argBindings = ((ArgBindingMap.Result.Ok) bindingResult).map();
        var result = svc.reflectTableMethod(cond.className(), cond.methodName(),
            argBindings, Set.copyOf(cond.contextArguments()),
            FieldBuilder.argSlotTypes(fieldDef));
        if (result.failed()) {
            return new FieldConditionResult.Rejected(result.rejection().prefixedWith("field '" + fieldDef.getName() + "' @condition: "));
        }
        var methodRef = result.ref();
        return new FieldConditionResult.Ok(
            new ConditionFilter(methodRef.className(), methodRef.methodName(), methodRef.params()));
    }

    /**
     * Rebuilds a {@link ConditionFilter} so its {@link ParamSource.Arg} params extract from a
     * nested position inside the enclosing input-argument {@code Map}: each {@code Arg} param's
     * {@link CallSiteExtraction} becomes a {@link CallSiteExtraction.NestedInputField} carrying
     * the path down from {@code outerArgName} to the leaf value. Other param sources pass
     * through unchanged.
     *
     * <p>The parameter's own extraction rides along as the wrapper's leaf rather than being
     * discarded for the {@link CallSiteExtraction.Direct} default: the rewrap says <em>where</em>
     * the value is found and the leaf says what to do with it once found, so replacing the leaf
     * would drop the transform the classifier installed (a {@code @nodeId} slot's decode, a jOOQ
     * enum's {@code valueOf}). The three-argument constructor refuses the one non-composable shape,
     * a {@link CallSiteExtraction.NestedInputField} leaf, which is the descent already expressed.
     */
    ConditionFilter rewrapForNested(ConditionFilter src, String outerArgName, List<String> leafPath) {
        var rewritten = new ArrayList<MethodRef.Param>();
        for (var p : src.params()) {
            if (p instanceof MethodRef.Param.Typed typed && p.source() instanceof ParamSource.Arg arg) {
                rewritten.add(new MethodRef.Param.Typed(typed.name(), typed.typeName(), typed.javaType(),
                    new ParamSource.Arg(
                        new CallSiteExtraction.NestedInputField(outerArgName,
                            nestedPath(leafPath, arg.path()), nestedLeaf(arg.extraction())),
                        arg.path())));
            } else {
                rewritten.add(p);
            }
        }
        return new ConditionFilter(src.className(), src.methodName(), List.copyOf(rewritten));
    }

    /**
     * The leaf transform a rewrap carries down. A parameter whose extraction is already a
     * {@link CallSiteExtraction.NestedInputField} has expressed a descent of its own, which the
     * wrapper cannot nest; that shape reaches here only from a re-rewrap, and the outer descent is
     * the one that reads from the enclosing argument, so the inner wrapper's own leaf carries
     * forward. A context binding stays bare: {@link ParamSource.Context} params are not rewrapped at
     * all, so the arm exists only to state that a context leaf is never traversed to.
     */
    private static CallSiteExtraction nestedLeaf(CallSiteExtraction extraction) {
        return extraction instanceof CallSiteExtraction.NestedInputField nested
            ? nested.leaf()
            : extraction;
    }

    /**
     * Outcome of {@link #installNodeIdDecode}: the condition with the decode installed, or the
     * declared-type refusal.
     */
    sealed interface DecodeInstall {
        record Ok(ConditionFilter filter) implements DecodeInstall {}
        record Rejected(Rejection rejection) implements DecodeInstall {}
    }

    /**
     * Installs the {@code @nodeId} decode on every parameter of {@code filter} bound to the whole of
     * {@code slotName}, and refuses a parameter whose declared Java type is not the decoded key's.
     *
     * <p>This is the site the contract is stated at: a {@code @nodeId} slot's value is decoded before
     * it leaves the generated glue, so the authored method receives the typed key rather than the
     * wire string. {@link ServiceCatalog#legacyArgExtraction} stays the declared-type rule for
     * everything else; the override is installed here, where the slot is known to carry
     * {@code @nodeId} and which node type it names, rather than by widening that shared static.
     *
     * <p>Keyed on the slot rather than on the directive site, which is what makes the rule one rule.
     * Three sites bind the same slots and all three route through here: the slot's own
     * {@code @condition} at an argument and at an input field, and a field-level {@code @condition}
     * binding one of its field's {@code @nodeId} arguments. That last one shares a glue method with
     * the implicit predicate over the same wire value, so leaving it on the declared-type rule would
     * put two contradictory readings of one argument in one emitted method.
     *
     * <p>Only a bare (single-segment) binding is a whole-slot binding. A dotted
     * {@code argMapping} path descends <em>into</em> the decoded identity to read one of its key
     * columns, which the projection rail already serves at the column grain; leaving those alone is
     * what keeps the two mechanisms from racing for one parameter. The one shape neither rail covers
     * is a field-level {@code argMapping} descending to a {@code @nodeId} <em>input field</em>
     * ({@code "p: filter.languageId"}): the path is dotted, so it is not a whole-slot binding here,
     * and its last segment names an input field rather than a key column, so the projection rail
     * does not claim it either. Such a parameter still receives the wire string.
     *
     * <p>The refusal exists because the contract's only other enforcer is the consumer's javac
     * inside emitted glue, a failure with no line back to the SDL. It names the coordinate, the
     * declared type and the required type, so the remedy is the message.
     */
    /**
     * Whether any parameter of {@code filter} binds the whole of {@code slotName}: the same predicate
     * {@link #installNodeIdDecode} installs on, exposed so a caller can ask the question without
     * performing the install. A field-level {@code @condition} sees every argument of its field and
     * binds some subset of them, so "does this condition bind that slot" is a real question there
     * where at the slot's own directive it is answered by the directive's placement.
     */
    static boolean bindsWholeSlot(ConditionFilter filter, String slotName) {
        return filter.params().stream().anyMatch(p ->
            p.source() instanceof ParamSource.Arg arg
                && arg.path().isHead()
                && arg.path().headName().equals(slotName));
    }

    static DecodeInstall installNodeIdDecode(ConditionFilter filter, String coordinate, String slotName,
                                             HelperRef.Decode decode, boolean list) {
        TypeName required = decode.decodedKeyType(list);
        var rewritten = new ArrayList<MethodRef.Param>();
        for (var p : filter.params()) {
            if (!(p instanceof MethodRef.Param.Typed typed)
                    || !(p.source() instanceof ParamSource.Arg arg)
                    || !arg.path().isHead()
                    || !arg.path().headName().equals(slotName)) {
                rewritten.add(p);
                continue;
            }
            if (!typed.javaType().equals(required)) {
                return new DecodeInstall.Rejected(Rejection.structural(
                    coordinate + ": parameter '" + typed.name() + "' of condition method '"
                    + filter.methodName() + "' binds a @nodeId slot, whose value is decoded"
                    + " before it reaches your method. Declare it '" + required + "' (the decoded key"
                    + " of node type '" + decode.nodeTypeName() + "'); it is declared '"
                    + typed.javaType() + "'."));
            }
            rewritten.add(new MethodRef.Param.Typed(typed.name(), typed.typeName(), typed.javaType(),
                new ParamSource.Arg(new CallSiteExtraction.ThrowOnMismatch(decode), arg.path())));
        }
        return new DecodeInstall.Ok(
            new ConditionFilter(filter.className(), filter.methodName(), List.copyOf(rewritten)));
    }

    /**
     * Builds the full Map-traversal path for a rewrapped condition argument: {@code leafPath}
     * (the walk's path from the outer argument down to the input field carrying the
     * {@code @condition}) followed by the parameter's own descent into that field's value, i.e.
     * the segments of {@code argPath} after its head. The head segment names the input field
     * itself, which is already the last element of {@code leafPath}, so it is dropped. A
     * multi-segment {@code argPath} (an explicit {@code argMapping: "p: field.sub"} or a depth-1
     * binding inferred by name) appends the descent so the emitted
     * {@link CallSiteExtraction.NestedInputField} reads the nested value rather than casting the
     * whole wrapper {@code Map} to the leaf type.
     */
    private static List<String> nestedPath(List<String> leafPath, PathExpr argPath) {
        var segments = argPath.segments();
        if (segments.size() == 1) {
            return leafPath;
        }
        var full = new ArrayList<>(leafPath);
        for (int i = 1; i < segments.size(); i++) {
            full.add(segments.get(i).name());
        }
        return full;
    }
}
