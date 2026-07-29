package no.sikt.graphitron.rewrite.generators;

import no.sikt.graphitron.command.UnitMethodRef;
import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.CodeBlock;
import no.sikt.graphitron.plan.GeneratedUnits;
import no.sikt.graphitron.rewrite.model.WhereFilter;

import java.util.List;

/**
 * The one-line condition glue call every WHERE consumer emits:
 * {@code <Parent>Conditions.<field>Condition(<alias>, <argsMap>[, env])}. Class and method names
 * derive through {@link GeneratedUnits}, the same formula the condition producer mints, so a
 * call site and the rendered glue cannot disagree without one of them abandoning the shared
 * vocabulary; the {@code env} argument is appended exactly when the coordinate's bindings read
 * the request context ({@link WhereFilter#anyReadRequestContext}), the row-grained fact the glue
 * renderer forks its signature on.
 *
 * <p>{@code argsMapExpr} is the caller's one contribution: {@code env.getArguments()} where the
 * enclosing environment is the field's own (root fetchers, rows methods, polymorphic branches),
 * {@code <sf>.getArguments()} at the inline {@code $project} sites where {@code env} belongs to an
 * ancestor. Both expose the same coerced map. The {@code env} literal is uniform: every emitting
 * host has the request environment in scope under that name, and context is request-global, so
 * the ancestor env at inline sites serves it correctly.
 */
public final class ConditionGlueCall {

    private ConditionGlueCall() {}

    /** The glue call for an ordinary coordinate, named through {@code conditionMethod}. */
    public static CodeBlock expression(String parentTypeName, String fieldName, List<WhereFilter> filters,
            String tableAlias, CodeBlock argsMapExpr, String outputPackage) {
        return expression(new GeneratedUnits(outputPackage).conditionMethod(parentTypeName, fieldName),
            filters, tableAlias, argsMapExpr);
    }

    /** The glue call against a caller-derived ref (participant and facet-fragment methods). */
    public static CodeBlock expression(UnitMethodRef glue, List<WhereFilter> filters,
            String tableAlias, CodeBlock argsMapExpr) {
        var call = CodeBlock.builder().add("$T.$L($L, $L",
            ClassName.get(glue.owner().packageName(), glue.owner().simpleName()),
            glue.methodName(), tableAlias, argsMapExpr);
        if (WhereFilter.anyReadRequestContext(filters)) {
            call.add(", env");
        }
        return call.add(")").build();
    }
}
