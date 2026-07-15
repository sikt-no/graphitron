package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.rewrite.model.ChildField;
import no.sikt.graphitron.rewrite.model.MutationField;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R178 step 3 regression pin for the SettKvotesporsmal bug
 * (see {@code roadmap/retire-single-record-carrier-walk.md}'s
 * "reported bug" section). The bug surfaced as classification divergence between two
 * semantically identical schemas: a record-backed payload (bound by reflection on the
 * {@code @service} producer's return type) with one {@code @table}-typed data field, classified
 * differently depending on whether the data field carried an explicit {@code @field(name:)} directive.
 *
 * <p>Before R178 step 3 retired the carrier walk's role in {@code @service}-mutation
 * classification, removing the redundant {@code @field(name: "<sdlFieldName>")} flipped
 * classification: the carrier walk's forbidden-directives loop at
 * {@code BuildContext.classifyCarrierField} hard-rejected the with-{@code @field} form
 * into the standard record-backed-parent path (which worked), while the
 * without-{@code @field} form was admitted into the carrier walk and rejected by the
 * legacy {@code FieldBuilder.registerServiceCarrierDataField}'s strict-return demand
 * citing the inner table's record class. After step 3, both forms classify identically
 * through the unified path; the carrier-walk consultation from the {@code @service}
 * mutation classifier is gone.
 *
 * <p>Two pins:
 * <ol>
 *   <li>Identical-classification pin: both forms produce a {@link ChildField.RecordTableField}
 *       at {@code <Payload>.film} reading via the {@code SinglePayload.film()} accessor on
 *       the record-backed parent. The mutation classifies admit
 *       ({@link MutationField.MutationServiceRecordField}). No
 *       {@link ChildField.RecordTableField} (the carrier-walk-shape permit) is produced.</li>
 *   <li>Diagnostic-wording pin: when the {@code @service} method's reflected return type
 *       doesn't match the payload class, the diagnostic cites the payload class (not the
 *       inner table's record class). The diagnostic family migrates from the carrier walk's
 *       {@code "must return <InnerRecord>"} wording to the legacy-equality check in
 *       {@code FieldBuilder.buildServiceField}'s {@code "must return <PayloadClass>"}
 *       wording.</li>
 * </ol>
 */
@PipelineTier
class SettKvotesporsmalShapeRegressionTest {

    private static final String FILM_TABLE = """
        type Film @table(name: "film") { title: String }
        """;

    /**
     * Pin 1a: the with-{@code @field(name: "film")} form classifies the data field through the
     * standard record-backed-parent path (accessor lookup via {@code film()}). Before R178
     * step 3 this form worked only because the carrier walk's forbidden-directives loop hard-
     * rejected {@code @field} on a non-{@code $source} carrier data field, falling through to
     * the standard path; after step 3 the same outcome holds because no carrier walk is
     * consulted at all.
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
        assertThat(df).isInstanceOf(ChildField.RecordTableField.class);
        // R305: the standard record-parent path (not the carrier re-fetch); its lift is
        // the catalog-FK/accessor read, not the source=target ProducedRecords carrier lift.
        assertThat(((ChildField.RecordTableField) df).lift())
            .isNotInstanceOf(no.sikt.graphitron.rewrite.model.KeyLift.ProducedRecords.class);
    }

    /**
     * Pin 1b: the no-{@code @field}-directive form classifies the data field identically.
     * Before R178 step 3 this form was rejected (the carrier walk admitted it and demanded
     * the inner table's record class as the method's return type); after step 3 it classifies
     * identically to the with-{@code @field} form.
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
        assertThat(df).isInstanceOf(ChildField.RecordTableField.class);
        assertThat(((ChildField.RecordTableField) df).lift())
            .isNotInstanceOf(no.sikt.graphitron.rewrite.model.KeyLift.ProducedRecords.class);
    }

    // R276: classBacked_returnMismatch_diagnosticDoesNotCiteInnerTableRecord was deleted. It
    // pinned the diagnostic for a mismatch between a @record(className)-declared backing and the
    // @service method's reflected return type. Under reflection-only binding the payload's backing
    // *is* the method's reflected return, so that mismatch is structurally impossible and the test
    // has no scenario left to exercise.
}
