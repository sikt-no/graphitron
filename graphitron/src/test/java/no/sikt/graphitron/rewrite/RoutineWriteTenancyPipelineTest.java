package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.common.configuration.TestConfiguration;
import no.sikt.graphitron.plan.EmitPlan;
import no.sikt.graphitron.command.TenantRouting;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import no.sikt.graphitron.model.config.RunContext;

/**
 * Pipeline-tier pin that a routine write's tenancy is the plan's axis rather than a binding read
 * at the emission site: the producer folds each coordinate's classified binding into an
 * acquisition and the relation carries the run's axis. Which connection the generated entry point
 * actually uses is the execution tier's to show.
 */
@PipelineTier
class RoutineWriteTenancyPipelineTest {

    /**
     * The chain-seat write against {@code rental}. Under a {@code film_id} tenant column the rental table carries no tenant scope, so the
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

    /**
     * The routine-write payload counterpart of the tenant-routed DML payload with an errors field:
     * the errors ride the typed {@code Outcome} the entry point returns, never the
     * {@code localContext} slot the tenant hand-down owns. A routine write mints no tenant slot
     * of its own (its only member is the routine call), so under a {@code film_id} tenant column
     * the rental write classifies untenanted and hands nothing down; what this pins is that the
     * binding, the error channel and the errors transport agree on the wrapper.
     */
    @Test
    void aRoutineWritePayloadWithErrorsRidesTheOutcomeTransport() {
        var sdl = """
            type DbErr @error(handlers: [{handler: DATABASE, sqlState: "23503"}]) {
                path: [String!]!
                message: String!
            }
            union RentFilmError = DbErr
            type Rental @table(name: "rental") { rentalId: Int! @field(name: "rental_id") }
            type RentFilmPayload { rental: Rental errors: [RentFilmError] }
            type Query { rental: Rental }
            type Mutation {
              rentFilm(inventoryId: Int!, customerId: Int!): RentFilmPayload
                @routine(name: "rent_film", argMapping: "pInventoryId: inventoryId, pCustomerId: customerId")
            }
            """;
        var ctx = TestConfiguration.testContext().withTenantColumn("film_id");
        var schema = TestSchemaHelper.buildSchema(sdl, ctx);
        assertThat(schema.tenantBindingOf("Mutation", "rentFilm"))
            .isInstanceOf(no.sikt.graphitron.rewrite.model.TenantBinding.Untenanted.DefaultSource.class);
        assertThat(((no.sikt.graphitron.rewrite.model.MutationField.MutationRoutineWriteRecordField)
                schema.field("Mutation", "rentFilm")).errorChannel())
            .get().isInstanceOf(no.sikt.graphitron.rewrite.model.ErrorChannel.Mapped.class);
        assertThat(((no.sikt.graphitron.rewrite.model.ChildField.ErrorsField)
                schema.field("RentFilmPayload", "errors")).transport())
            .isInstanceOf(no.sikt.graphitron.rewrite.model.ChildField.Transport.WrapperArm.class);
    }

    private static EmitPlan planFor(RunContext ctx) {
        return TestSchemaHelper.storeBackedPlan(tmp, SDL, ctx);
    }
}
