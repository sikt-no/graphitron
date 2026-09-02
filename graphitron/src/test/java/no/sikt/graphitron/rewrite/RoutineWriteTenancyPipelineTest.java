package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.common.configuration.TestConfiguration;
import no.sikt.graphitron.javapoet.TypeSpec;
import no.sikt.graphitron.plan.EmitPlan;
import no.sikt.graphitron.command.TenantRouting;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.Test;

import static no.sikt.graphitron.common.configuration.TestConfiguration.DEFAULT_OUTPUT_PACKAGE;
import static org.assertj.core.api.Assertions.assertThat;
import no.sikt.graphitron.model.config.RunContext;

/**
 * Pipeline-tier pin that a routine-write entry point acquires its connection from the plan's
 * tenancy axis rather than from a binding read at the emission site. The fetcher shell no longer
 * resolves anything about tenancy: the producer folds each coordinate's classified binding into
 * an acquisition, the relation carries the run's axis, and the renderer is a total function over
 * the two.
 *
 * <p>What this tier adds over the per-arm unit tests on the fragments is the join: that the
 * producer's fold and the renderer's arms meet on a real classified schema, and that the emitted
 * declaration still drags its {@code graphitronContext} helper onto the class when it reads one.
 * A renderer that emits the call without the helper compiles here and fails at the consumer's
 * javac, which is the bug class the request-context seam exists to close.
 */
@PipelineTier
class RoutineWriteTenancyPipelineTest {

    /**
     * The chain-seat write against {@code rental}, the same fixture the two-step shape is pinned
     * on. Under a {@code film_id} tenant column the rental table carries no tenant scope, so the
     * coordinate classifies untenanted and acquires the default source.
     */
    private static final String SDL = """
        type Rental @table(name: "rental") { rentalId: Int! @field(name: "rental_id") }
        type Query { rental: Rental }
        type Mutation {
          rentFilm(inventoryId: Int!, customerId: Int!): [Rental!]!
            @routine(name: "rent_film", argMapping: "pInventoryId: inventoryId, pCustomerId: customerId")
            @reference(path: [{table: "rental"}])
        }
        """;

    @org.junit.jupiter.api.io.TempDir
    static java.nio.file.Path tmp;

    @Test
    void aSingleTenantRunDeclaresTheRequestContextsConnectionAndCarriesItsHelper() {
        var fetchers = mutationFetchers(TestConfiguration.testContext());

        assertThat(body(fetchers))
            .contains("org.jooq.DSLContext dsl = graphitronContext(env).getDslContext(env);");
        assertThat(fetchers.methodSpecs().stream().map(m -> m.name()))
            .as("the emitted request-context read drags its own helper onto the class")
            .contains("graphitronContext");
    }

    @Test
    void aMultiTenantRunRoutesAnUntenantedWriteThroughTheGeneratedCarrier() {
        assertThat(body(mutationFetchers(
                TestConfiguration.testContext().withTenantColumn("film_id"))))
            .as("the rental table carries no film_id, so the write is global reference data")
            .contains("org.jooq.DSLContext dsl = " + DEFAULT_OUTPUT_PACKAGE
                + ".schema.TenantConnections.dslDefault(env);")
            .doesNotContain("getDslContext(env)");
    }

    /**
     * The axis is the relation's, not the row's: a single-tenant run states the absence once
     * instead of stamping an arm onto every coordinate, which is the same line the classifier's
     * binding index draws when it stays empty rather than filling with untenanted arms.
     */
    @Test
    void theRunGrainAxisRidesTheRelation() {
        assertThat(planFor(TestConfiguration.testContext()).routineWrites().tenancy())
            .isInstanceOf(TenantRouting.Unrouted.class);

        var routed = planFor(TestConfiguration.testContext().withTenantColumn("film_id"))
            .routineWrites().tenancy();
        assertThat(routed).isInstanceOf(TenantRouting.Routed.class);
        assertThat(((TenantRouting.Routed) routed).byCoordinate().keySet())
            .as("every row this relation holds is an entry point that declares a connection")
            .singleElement()
            .hasToString("Mutation.rentFilm");
    }

    private static EmitPlan planFor(RunContext ctx) {
        return TestSchemaHelper.storeBackedPlan(tmp, SDL, ctx);
    }

    private static TypeSpec mutationFetchers(RunContext ctx) {
        return TestSchemaHelper.storeBackedFetchers(tmp, SDL, ctx).stream()
            .filter(t -> t.name().equals("MutationFetchers"))
            .findFirst()
            .orElseThrow();
    }

    private static String body(TypeSpec fetchers) {
        return fetchers.methodSpecs().stream()
            .filter(m -> m.name().equals("rentFilm"))
            .findFirst()
            .orElseThrow()
            .code()
            .toString();
    }
}
