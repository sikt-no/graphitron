package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.javapoet.TypeSpec;
import no.sikt.graphitron.plan.ConditionCommands;
import no.sikt.graphitron.plan.ProjectionCommands;
import no.sikt.graphitron.render.ProjectionUnitRenderer;

import java.util.List;

/**
 * Test-side shorthand for the produce-then-render pair the pipeline runs for projection units:
 * {@link ProjectionCommands#produce} mints the rows (consuming the condition relation for the
 * glue refs, exactly as {@code EmitPlan.produce} wires it), {@link ProjectionUnitRenderer#render}
 * interprets them. Tests that used to call the retired type-class generator's
 * {@code generate(schema, outputPackage)} call this instead; note the returned specs include the
 * anchor-prefixed nested units and per-coordinate pivot units alongside the anchor classes.
 */
public final class ProjectionRenderTestSupport {

    private ProjectionRenderTestSupport() {}

    public static List<TypeSpec> renderProjections(GraphitronSchema schema, String outputPackage) {
        var conditions = ConditionCommands.produce(schema, outputPackage);
        var relation = ProjectionCommands.produce(schema, conditions, outputPackage);
        return ProjectionUnitRenderer.render(relation.rows(), outputPackage);
    }

    /** The single unit spec named {@code simpleName}, or a failed assertion if absent. */
    public static TypeSpec unit(GraphitronSchema schema, String outputPackage, String simpleName) {
        return renderProjections(schema, outputPackage).stream()
            .filter(t -> t.name().equals(simpleName))
            .findFirst()
            .orElseThrow(() -> new AssertionError(
                "no projection unit named '" + simpleName + "' was rendered"));
    }
}
