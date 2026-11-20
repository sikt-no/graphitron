package no.sikt.graphitron.rewrite.validation;

import no.sikt.graphitron.rewrite.GraphitronSchema;
import no.sikt.graphitron.rewrite.TestSchemaHelper;
import no.sikt.graphitron.model.diagnostics.ValidationError;
import no.sikt.graphitron.rewrite.model.GraphitronField;
import no.sikt.graphitron.rewrite.model.GraphitronField.UnclassifiedField;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.Test;

import static no.sikt.graphitron.rewrite.validation.FieldValidationTestHelper.validate;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Mixed-source nesting reach negatives. A directiveless type reached both as a nesting projection of a
 * {@code @table} parent and as a producer-backed result now classifies and validates when every field is
 * readable in both shapes; this test pins the two ways coexistence still fails.
 *
 * <p>The former table-and-service clash this test's predecessor asserted (the dissolved
 * {@code ConstructorField}: a {@code @table} parent embedding a record-backed child) is now the
 * <em>positive</em> mixed-source case (a plain value type freely reused across source shapes, served by a
 * run-time {@code source instanceof Record} dispatch). The rejection re-lands, narrowed, on two genuine
 * per-arm failures: a child readable in only one shape, and the jOOQ-record-carrier shape-set combination.
 */
@PipelineTier
class MixedSourceNestingReachValidationTest {

    // FilmDetails is reached both ways; `rating` resolves on both arms, but `description` is a film column
    // with no accessor on the backing FilmDetailsRating record — readable only as a column. The result-axis
    // own visit rejects it through the existing accessor path.
    private static final String CHILD_READABLE_ONLY_AS_COLUMN = """
        type FilmDetails { rating: String description: String }
        type Film @table(name: "film") { details: FilmDetails }
        type Query {
            film: Film
            prodFilmDetails: FilmDetails @service(service: {className: "no.sikt.graphitron.codereferences.dummyreferences.DummyService", method: "makeFilmDetailsRating"})
        }
        """;

    // FilmThing is reached both as a nesting projection of @table Film and as an @externalField result
    // whose Field carries a jOOQ TableRecord, so it binds to a JooqTableRecordType (a JooqRecordCarrier).
    // Both arms would be Record reads with independently derived read names: unsupported in v1, rejected by
    // the reified shape-set rule rather than a fused type-level check.
    private static final String JOOQ_RECORD_CARRIER_NESTING_MIX = """
        type FilmThing { title: String }
        type Film @table(name: "film") { thing: FilmThing }
        type Inventory @table(name: "inventory") {
            filmRef: FilmThing @externalField(reference: {className: "no.sikt.graphitron.rewrite.test.extensions.InventoryExtensions"})
        }
        type Query {
            film: Film
            inventory: Inventory
        }
        """;

    @Test
    void childReadableOnlyAsColumn_rejectsViaAccessorPath() {
        GraphitronSchema schema = TestSchemaHelper.buildSchema(CHILD_READABLE_ONLY_AS_COLUMN);

        GraphitronField description = schema.field("FilmDetails", "description");
        assertThat(description).isInstanceOf(UnclassifiedField.class);
        assertThat(validate(schema))
            .extracting(ValidationError::message)
            .anyMatch(m -> m.contains("FilmDetails.description"));
    }

    @Test
    void jooqRecordCarrierNestingMix_rejectsViaShapeSetRule() {
        GraphitronSchema schema = TestSchemaHelper.buildSchema(JOOQ_RECORD_CARRIER_NESTING_MIX);

        assertThat(validate(schema))
            .extracting(ValidationError::message)
            .anyMatch(m -> m.contains("FilmThing.title") && m.contains("combination no fetcher serves"));
    }
}
