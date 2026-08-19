package no.sikt.graphitron.rewrite;

import graphql.schema.GraphQLArgument;
import graphql.schema.GraphQLFieldDefinition;
import no.sikt.graphitron.rewrite.model.CallSiteExtraction;
import no.sikt.graphitron.rewrite.model.ConditionFilter;
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
     */
    ConditionFilter rewrapForNested(ConditionFilter src, String outerArgName, List<String> leafPath) {
        var rewritten = new ArrayList<MethodRef.Param>();
        for (var p : src.params()) {
            if (p instanceof MethodRef.Param.Typed typed && p.source() instanceof ParamSource.Arg arg) {
                rewritten.add(new MethodRef.Param.Typed(typed.name(), typed.typeName(), typed.javaType(),
                    new ParamSource.Arg(
                        new CallSiteExtraction.NestedInputField(outerArgName, nestedPath(leafPath, arg.path())),
                        arg.path())));
            } else {
                rewritten.add(p);
            }
        }
        return new ConditionFilter(src.className(), src.methodName(), List.copyOf(rewritten));
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
