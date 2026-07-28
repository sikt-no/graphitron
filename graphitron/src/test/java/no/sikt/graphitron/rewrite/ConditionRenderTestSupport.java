package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.javapoet.TypeSpec;
import no.sikt.graphitron.plan.ConditionCommands;
import no.sikt.graphitron.render.ConditionGlueRenderer;

import java.util.List;

/**
 * Test-side shorthand for the produce-then-render pair the pipeline runs for condition glue:
 * {@link ConditionCommands#produce} commits the rows, {@link ConditionGlueRenderer#render}
 * interprets the committed subset. Tests that used to call the retired shim generator's
 * {@code generate(schema, outputPackage)} call this instead, exercising the same two steps the
 * production pipeline wires together.
 */
public final class ConditionRenderTestSupport {

    private ConditionRenderTestSupport() {}

    public static List<TypeSpec> renderCommittedConditions(GraphitronSchema schema, String outputPackage) {
        var relation = ConditionCommands.produce(schema, outputPackage);
        return ConditionGlueRenderer.render(relation.committedRows(), outputPackage);
    }
}
