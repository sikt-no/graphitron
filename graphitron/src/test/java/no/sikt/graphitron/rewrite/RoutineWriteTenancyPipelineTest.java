package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.common.configuration.TestConfiguration;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pipeline-tier pin on what a routine write's tenancy classifies to. Which connection the
 * generated entry point actually uses is the execution tier's to show.
 */
@PipelineTier
class RoutineWriteTenancyPipelineTest {

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
                schema.field("RentFilmPayload", "errors"))).isInstanceOf(no.sikt.graphitron.rewrite.model.ChildField.ErrorsField.class);
    }
}
