package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.rewrite.model.ChildField;
import no.sikt.graphitron.rewrite.model.MutationField;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression pin (the SettKvotesporsmal shape): a record-backed {@code @service} mutation
 * payload (bound by reflection on the producer's return type) with one {@code @table}-typed
 * data field classifies identically whether or not the data field carries a redundant
 * {@code @field(name: "<sdlFieldName>")} directive.
 *
 * <p>Both forms produce a {@link ChildField.BatchedTableField} at {@code Payload.film}
 * reading via the payload record's {@code film()} accessor, the mutation classifies as
 * {@link MutationField.MutationServiceRecordField}, and neither form's lift is the
 * source=target {@code ProducedRecords} carrier lift.
 */
@PipelineTier
class SettKvotesporsmalShapeRegressionTest {

    private static final String FILM_TABLE = """
        type Film @table(name: "film") { title: String }
        """;

    /**
     * The with-{@code @field(name: "film")} form classifies the data field through the
     * standard record-backed-parent path (accessor lookup via {@code film()}).
     */
    @Test
    void withExplicitFieldDirective_classifiesThroughStandardRecordParentPath() {
        var schema = TestSchemaHelper.buildSchema(FILM_TABLE + """
            type Payload {
                film: Film! @field(name: "film")
            }
            type Query { x: String }
            type Mutation {
                doIt: Payload
                    @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "runPassthroughPayload"})
            }
            """);

        var mut = schema.field("Mutation", "doIt");
        assertThat(mut).isInstanceOf(MutationField.MutationServiceRecordField.class);

        var df = schema.field("Payload", "film");
        assertThat(df).isInstanceOf(ChildField.BatchedTableField.class);
        // Standard record-parent path: the lift is the catalog-FK/accessor read,
        // not the source=target ProducedRecords carrier lift.
        assertThat(((ChildField.BatchedTableField) df).lift())
            .isNotInstanceOf(no.sikt.graphitron.rewrite.model.KeyLift.ProducedRecords.class);
    }

    /**
     * The no-{@code @field}-directive form classifies the data field identically to the
     * with-{@code @field} form.
     */
    @Test
    void withoutFieldDirective_classifiesIdenticallyToExplicitForm() {
        var schema = TestSchemaHelper.buildSchema(FILM_TABLE + """
            type Payload {
                film: Film!
            }
            type Query { x: String }
            type Mutation {
                doIt: Payload
                    @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "runPassthroughPayload"})
            }
            """);

        var mut = schema.field("Mutation", "doIt");
        assertThat(mut).isInstanceOf(MutationField.MutationServiceRecordField.class);

        var df = schema.field("Payload", "film");
        assertThat(df).isInstanceOf(ChildField.BatchedTableField.class);
        assertThat(((ChildField.BatchedTableField) df).lift())
            .isNotInstanceOf(no.sikt.graphitron.rewrite.model.KeyLift.ProducedRecords.class);
    }
}
