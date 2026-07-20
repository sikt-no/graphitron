package no.sikt.graphitron.rewrite.validation;

import no.sikt.graphitron.rewrite.TestSchemaHelper;
import no.sikt.graphitron.rewrite.ValidationError;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.Test;

import static no.sikt.graphitron.rewrite.validation.FieldValidationTestHelper.validate;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Validator-tier coverage for the three near-misses of the {@code @service} record-composite
 * payload carrier. The {@code ClassifiedCorpus} pins the positive classification only (a successful
 * classification); the build-time rejections are asserted here, in the per-shape {@code *ValidationTest}
 * style ({@code MixedSourceNestingReachValidationTest}), so the admitted shape and its near-misses are pinned
 * together rather than the rejections riding as unpinned prose.
 *
 * <p>Per <em>Validator mirrors classifier invariants</em>, each near-miss surfaces through an existing
 * recognizer rather than a new bespoke predicate kept complementary by hope: the mismatched-producer
 * disagreement through {@code RecordBindingResolver}'s per-type fold
 * ({@code RecordBindingMultiProducer}); the unresolvable {@code @field}-mapped child through the
 * record-backed accessor-resolution rejection; and the re-levelled cardinality mismatch through the
 * producing {@code @service} field's {@code checkServiceReturnMatchesPayload}.
 */
@PipelineTier
class ServiceRecordCompositeCarrierValidationTest {

    private static final String TABLES = """
        type Film @table(name: "film") { title: String }
        type Actor @table(name: "actor") { firstName: String @field(name: "first_name") }
        type DbErr @error(handlers: [{handler: DATABASE}]) { path: [String!]!  message: String! }
        union CreateError = DbErr
        """;

    /**
     * (a) Mismatched producer: the composite element does not bind to one reflected return element
     * because two producers disagree on its backing class. {@code createFilms} (the carrier) binds
     * {@code CreateFilmsResult} to the composite via the data-field element; {@code directResult} binds
     * the same SDL type to a different reflected class. The per-type fold rejects the disagreement.
     */
    @Test
    void mismatchedProducer_disagreeingBackingClasses_rejects() {
        var schema = TestSchemaHelper.buildSchema(TABLES + """
            type CreateFilmsResult {
                film: Film! @field(name: "filmRecord")
                actors: [Actor] @field(name: "actorRecords")
            }
            type CreateFilmsPayload { results: [CreateFilmsResult]  errors: [CreateError] }
            type Query {
                directResult: CreateFilmsResult
                    @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "getDetails"})
            }
            type Mutation {
                createFilms: CreateFilmsPayload
                    @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "createFilmsWithActors"})
            }
            """);

        assertThat(validate(schema))
            .extracting(ValidationError::message)
            .anyMatch(m -> m.contains("CreateFilmsResult")
                && m.contains("disagreeing reflected backing classes from multiple producers"));
    }

    /**
     * (b) The composite element binds, but a {@code @field}-mapped child is neither {@code @table}-backed
     * nor a resolvable accessor on the composite. The record-backed accessor resolution rejects the
     * child with the accessor-mismatch diagnostic.
     */
    @Test
    void fieldMappedChild_noMatchingAccessor_rejects() {
        var schema = TestSchemaHelper.buildSchema(TABLES + """
            type CreateFilmsResult {
                film: Film! @field(name: "filmRecord")
                actors: [Actor] @field(name: "actorRecords")
                bogus: String @field(name: "doesNotExist")
            }
            type CreateFilmsPayload { results: [CreateFilmsResult]  errors: [CreateError] }
            type Query { x: String }
            type Mutation {
                createFilms: CreateFilmsPayload
                    @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "createFilmsWithActors"})
            }
            """);

        assertThat(validate(schema))
            .extracting(ValidationError::message)
            .anyMatch(m -> m.contains("CreateFilmsResult.bogus")
                && m.contains("no accessor")
                && m.contains("doesNotExist"));
    }

    /**
     * (c) Cardinality mismatch at the re-levelled comparison: a single data field on the payload while
     * the producer returns {@code List<composite>}. The producing {@code @service} field's
     * re-levelled return-shape check names the single-vs-list mismatch precisely (rather than the
     * element being left unbound and the payload dangling with a generic message).
     */
    @Test
    void cardinalityMismatch_singleDataFieldListReturn_rejects() {
        var schema = TestSchemaHelper.buildSchema(TABLES + """
            type CreateFilmsResult {
                film: Film! @field(name: "filmRecord")
                actors: [Actor] @field(name: "actorRecords")
            }
            type CreateFilmPayload { result: CreateFilmsResult  errors: [CreateError] }
            type Query { x: String }
            type Mutation {
                createFilm: CreateFilmPayload
                    @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "createFilmsWithActors"})
            }
            """);

        assertThat(validate(schema))
            .extracting(ValidationError::message)
            .anyMatch(m -> m.contains("Mutation.createFilm")
                && m.contains("must return 'no.sikt.graphitron.rewrite.TestFilmWithActorsDto'")
                && m.contains("java.util.List<no.sikt.graphitron.rewrite.TestFilmWithActorsDto>"));
    }
}
