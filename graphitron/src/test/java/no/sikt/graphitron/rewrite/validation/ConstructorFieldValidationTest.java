package no.sikt.graphitron.rewrite.validation;

import no.sikt.graphitron.rewrite.GraphitronSchema;
import no.sikt.graphitron.rewrite.TestSchemaHelper;
import no.sikt.graphitron.rewrite.ValidationError;
import no.sikt.graphitron.rewrite.model.GraphitronField;
import no.sikt.graphitron.rewrite.model.GraphitronField.UnclassifiedField;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.Test;

import static no.sikt.graphitron.rewrite.validation.FieldValidationTestHelper.validate;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * R290 dissolved {@code ConstructorField} as wrong-by-design. A {@code @table} parent whose child
 * returns a record-backed ({@code @service}) result type, with no producer directive on the child to
 * build it, used to classify cleanly as {@code ConstructorField} — materialising the child from the
 * {@code @table} parent's own row, a shape no production schema relies on. That table-and-service
 * clash is now a build-time rejection: the classifier produces an {@code UnclassifiedField} whose
 * rejection {@code GraphitronSchemaValidator} surfaces as a build error. This is the rejection fixture
 * the former {@code "constructor"} corpus example became (it left the classified corpus, which holds
 * successful classifications only).
 */
@PipelineTier
class ConstructorFieldValidationTest {

    private static final String TABLE_PARENT_RECORD_CHILD = """
        type FilmDetails { rating: String }
        type Film @table(name: "film") { details: FilmDetails }
        type Query {
            film: Film
            prodFilmDetails: FilmDetails @service(service: {className: "no.sikt.graphitron.codereferences.dummyreferences.DummyService", method: "makeFilmDetailsRating"})
        }
        """;

    @Test
    void tableParentRecordChild_withoutProducer_classifiesAsUnclassified() {
        GraphitronSchema schema = TestSchemaHelper.buildSchema(TABLE_PARENT_RECORD_CHILD);

        GraphitronField details = schema.field("Film", "details");
        assertThat(details).isInstanceOf(UnclassifiedField.class);
        assertThat(((UnclassifiedField) details).reason())
            .contains("a @table parent cannot construct a record-backed child from its own row");
    }

    @Test
    void tableParentRecordChild_withoutProducer_failsValidation() {
        GraphitronSchema schema = TestSchemaHelper.buildSchema(TABLE_PARENT_RECORD_CHILD);

        assertThat(validate(schema))
            .extracting(ValidationError::message)
            .anyMatch(m -> m.contains("Film.details")
                && m.contains("a @table parent cannot construct a record-backed child from its own row"));
    }
}
