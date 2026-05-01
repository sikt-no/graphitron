package no.sikt.graphitron.rewrite;

import graphql.schema.GraphQLArgument;
import graphql.schema.GraphQLFieldDefinition;
import no.sikt.graphitron.rewrite.model.CallSiteExtraction;
import no.sikt.graphitron.rewrite.model.ConditionFilter;
import no.sikt.graphitron.rewrite.model.MethodRef;
import no.sikt.graphitron.rewrite.model.ParamSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Resolves the {@code @condition} concern: builds {@link ConditionFilter} / {@link ArgConditionRef}
 * from {@code @condition} directives applied to either a GraphQL field or an individual argument,
 * and rewraps a {@link ConditionFilter} so its {@link ParamSource.Arg} params extract from a
 * nested position inside an outer input-argument {@code Map}. The fourth projection resolver
 * under R6 (Phase 6c), sibling to {@link OrderByResolver} (Phase 5),
 * {@link LookupMappingResolver} (Phase 6a), and {@link PaginationResolver} (Phase 6b).
 *
 * <p>Three responsibilities cluster here, all centred on the {@code @condition} directive's
 * reflection contract:
 *
 * <ul>
 *   <li>{@link #resolveArg} — argument-level {@code @condition}: reflects the condition method
 *       via {@link ServiceCatalog#reflectTableMethod}, binding the argument to its same-named
 *       Java parameter by default and applying any {@code argMapping} overrides.</li>
 *   <li>{@link #resolveField} — field-level {@code @condition}: reflects the same way, but with
 *       all field arguments available as bindings rather than a single arg.</li>
 *   <li>{@link #rewrapForNested} — pure transformation that rebuilds a {@link ConditionFilter}
 *       whose {@link ParamSource.Arg} params need to be extracted from a nested position inside
 *       an outer input-argument {@code Map} (used by {@code walkInputFieldConditions} when
 *       gathering input-field-level {@code @condition} predicates).</li>
 * </ul>
 *
 * <p>Both resolvers return a sealed three-arm result ({@link ArgConditionResult} /
 * {@link FieldConditionResult}) carrying {@code None} (no directive present), {@code Ok}
 * (resolved successfully), or {@code Rejected} (reflection or argMapping failed). This replaces
 * the prior dual-signal pattern (nullable return + errors-list mutation) per R6's pivot to
 * sealed-result resolvers.
 *
 * <p>The enum-related helpers in {@link FieldBuilder} ({@code buildTextEnumMapping},
 * {@code validateEnumFilter}) were grouped with this resolver in the original R6 plan as
 * "enum sub-helpers" but are not lifted here: they serve broader argument-classification needs
 * (called from {@code classifyArgument}, {@code deriveExtraction}, {@code buildLookupBindings},
 * {@code enrichArgExtractions}) rather than {@code @condition} resolution specifically. They
 * stay in {@link FieldBuilder} until a future enum-mapping axis lift in the final mop-up phase.
 */
final class ConditionResolver {

    /**
     * Outcome of {@link #resolveArg}. Three terminal arms.
     *
     * <ul>
     *   <li>{@link None} — the argument carries no {@code @condition} directive.</li>
     *   <li>{@link Ok} — successful resolution; carries the resolved {@link ArgConditionRef}.</li>
     *   <li>{@link Rejected} — argMapping error or reflection failure; carries a single
     *       fully-prefixed message ready for the caller's accumulating errors list.</li>
     * </ul>
     */
    sealed interface ArgConditionResult {
        record None() implements ArgConditionResult {}
        record Ok(ArgConditionRef ref) implements ArgConditionResult {}
        record Rejected(String message) implements ArgConditionResult {}
    }

    /**
     * Outcome of {@link #resolveField}. Three terminal arms; same shape as
     * {@link ArgConditionResult} but the {@code Ok} arm carries a {@link ConditionFilter}
     * directly (no override flag — only argument-level conditions carry that).
     */
    sealed interface FieldConditionResult {
        record None() implements FieldConditionResult {}
        record Ok(ConditionFilter filter) implements FieldConditionResult {}
        record Rejected(String message) implements FieldConditionResult {}
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
     * binding the argument to its same-named Java parameter by default and applying any
     * {@code argMapping} overrides on the {@code @condition} directive's
     * {@code ExternalCodeReference} (R53). Declared {@code contextArguments} go in
     * {@code ctxKeys}. {@code @field(name:)} on the argument is the column-binding axis for the
     * auto-equality path; the two axes coexist on the same slot.
     */
    ArgConditionResult resolveArg(GraphQLArgument arg) {
        var cond = ctx.readConditionDirective(arg);
        if (cond == null) return new ArgConditionResult.None();
        var argName = arg.getName();
        if (cond.argMappingError() != null) {
            return new ArgConditionResult.Rejected(
                "argument '" + argName + "' @condition: " + cond.argMappingError());
        }
        var bindingResult = ArgBindingMap.of(Set.of(argName), cond.argMapping());
        if (bindingResult instanceof ArgBindingMap.Result.UnknownArgRef u) {
            return new ArgConditionResult.Rejected(
                "argument '" + argName + "' @condition: " + u.message());
        }
        var argBindings = ((ArgBindingMap.Result.Ok) bindingResult).map();
        var result = svc.reflectTableMethod(cond.className(), cond.methodName(),
            argBindings, Set.copyOf(cond.contextArguments()), null);
        if (result.failed()) {
            return new ArgConditionResult.Rejected(
                "argument '" + argName + "' @condition: " + result.failureReason());
        }
        var methodRef = result.ref();
        return new ArgConditionResult.Ok(new ArgConditionRef(
            new ConditionFilter(methodRef.className(), methodRef.methodName(), methodRef.params()),
            cond.override()));
    }

    /**
     * Builds a field-level {@link ConditionFilter} from a {@code @condition} directive on the
     * field definition. Reflects via {@link ServiceCatalog#reflectTableMethod}, binding every
     * field argument to its same-named Java parameter by default and applying any
     * {@code argMapping} overrides on the {@code @condition} directive's
     * {@code ExternalCodeReference} (R53). Declared {@code contextArguments} go in
     * {@code ctxKeys}. {@code @field(name:)} on filter arguments is the column-binding axis;
     * the two axes coexist on the same slot.
     */
    FieldConditionResult resolveField(GraphQLFieldDefinition fieldDef) {
        var cond = ctx.readConditionDirective(fieldDef);
        if (cond == null) return new FieldConditionResult.None();
        if (cond.argMappingError() != null) {
            return new FieldConditionResult.Rejected(
                "field '" + fieldDef.getName() + "' @condition: " + cond.argMappingError());
        }
        var bindingResult = ArgBindingMap.of(FieldBuilder.fieldArgumentNames(fieldDef), cond.argMapping());
        if (bindingResult instanceof ArgBindingMap.Result.UnknownArgRef u) {
            return new FieldConditionResult.Rejected(
                "field '" + fieldDef.getName() + "' @condition: " + u.message());
        }
        var argBindings = ((ArgBindingMap.Result.Ok) bindingResult).map();
        var result = svc.reflectTableMethod(cond.className(), cond.methodName(),
            argBindings, Set.copyOf(cond.contextArguments()), null);
        if (result.failed()) {
            return new FieldConditionResult.Rejected(
                "field '" + fieldDef.getName() + "' @condition: " + result.failureReason());
        }
        var methodRef = result.ref();
        return new FieldConditionResult.Ok(
            new ConditionFilter(methodRef.className(), methodRef.methodName(), methodRef.params()));
    }

    /**
     * Rebuilds a {@link ConditionFilter} whose {@link ParamSource.Arg} params need to be
     * extracted from a nested position inside the enclosing input argument {@code Map} rather
     * than from a top-level argument. Each {@code Arg} param's {@link CallSiteExtraction} is
     * replaced with a {@link CallSiteExtraction.NestedInputField} carrying the path down from
     * {@code outerArgName} to the leaf value. {@link ParamSource.Context} params and implicit
     * {@link ParamSource.Table} params are left untouched.
     */
    ConditionFilter rewrapForNested(ConditionFilter src, String outerArgName, List<String> leafPath) {
        var rewritten = new ArrayList<MethodRef.Param>();
        for (var p : src.params()) {
            if (p.source() instanceof ParamSource.Arg arg) {
                rewritten.add(new MethodRef.Param.Typed(p.name(), p.typeName(),
                    new ParamSource.Arg(new CallSiteExtraction.NestedInputField(outerArgName, leafPath),
                        arg.graphqlArgName())));
            } else {
                rewritten.add(p);
            }
        }
        return new ConditionFilter(src.className(), src.methodName(), List.copyOf(rewritten));
    }
}
