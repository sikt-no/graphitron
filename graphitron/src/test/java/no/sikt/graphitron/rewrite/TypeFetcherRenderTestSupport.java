package no.sikt.graphitron.rewrite;

import graphql.schema.GraphQLSchema;
import no.sikt.graphitron.command.KeyProjectionRelation;
import com.palantir.javapoet.TypeSpec;
import no.sikt.graphitron.plan.ConditionCommands;
import no.sikt.graphitron.plan.FetchersPlanner;
import no.sikt.graphitron.plan.LauncherCommands;
import no.sikt.graphitron.emit.RoutineWriteQuery;
import no.sikt.graphitron.plan.TypeUnitRelation;
import no.sikt.graphitron.rewrite.generators.TypeFetcherGenerator;

import java.util.List;

/**
 * Plans what a fetchers emission dispatches on and runs the emitter over it: the orchestration a
 * run does, for a fixture that holds no {@link no.sikt.graphitron.plan.EmitPlan}.
 *
 * <p>Here rather than as an overload on the emitter. An emitter executes commands; it does not
 * call four producers to obtain them. As an overload this put the orchestration inside the thing
 * being orchestrated and made the emitter's main sources depend on the plan layer for a path
 * production never takes, production passing the plan's rows to the canonical method instead.
 *
 * <p>The routine-write rows and the error channels are empty because a fixture holding no store
 * cannot read them. A {@code @routine}-writing coordinate, or a producer catching into an outcome,
 * reaching here therefore fails its drift guard by name rather than emitting something plausible;
 * such a fixture wants the store-backed plan.
 */
public final class TypeFetcherRenderTestSupport {

    private TypeFetcherRenderTestSupport() {}

    /** The {@code *Fetchers} classes a run emits for {@code schema}, planned then rendered. */
    public static List<TypeSpec> generate(GraphitronSchema schema, GraphQLSchema assembled,
                                          String outputPackage) {
        var typeUnits = new TypeUnitRelation(FetchersPlanner.produce(schema, outputPackage));
        return TypeFetcherGenerator.generate(schema, assembled, outputPackage,
            LauncherCommands.produce(schema,
                ConditionCommands.produce(schema, outputPackage), outputPackage),
            typeUnits.fetchers(),
            typeUnits.errorFetchers(),
            RoutineWriteQuery.Rows.empty(),
            KeyProjectionRelation.empty(),
            no.sikt.graphitron.emit.ErrorChannelRelation.empty(),
            no.sikt.graphitron.emit.OutboxQuery.Rows.empty(),
            no.sikt.graphitron.emit.RecordAccessorQuery.Rows.empty());
    }

    /**
     * For a fixture that builds only the model and no assembled schema.
     */
    public static List<TypeSpec> generate(GraphitronSchema schema, String outputPackage) {
        return generate(schema, null, outputPackage);
    }
}
