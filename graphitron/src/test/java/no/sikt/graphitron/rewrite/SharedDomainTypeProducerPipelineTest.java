package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.rewrite.model.ChildField;
import no.sikt.graphitron.rewrite.model.DomainReturnType;
import no.sikt.graphitron.rewrite.model.GraphitronType;
import no.sikt.graphitron.rewrite.model.MutationField;
import no.sikt.graphitron.rewrite.model.OutputField;
import no.sikt.graphitron.rewrite.model.QueryField;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pipeline-tier coverage for the multi-producer source-type check over a <em>shared</em>
 * class-backed SDL type: one value type produced by a {@code @service} (batched child or root)
 * and read as a component of a record-backed parent. Both producers put the same Java object at
 * {@code env.getSource()}, and the check must see that.
 *
 * <p>The assertions are positive rather than absence-of-diagnostic: a fixture broken in some
 * other way would classify to nothing and satisfy "no rejection fired" vacuously. Each case
 * therefore pins the coordinate's leaf, the shared type's verdict, and the producers' equal
 * {@link DomainReturnType} claims.
 */
@PipelineTier
class SharedDomainTypeProducerPipelineTest {

    private static final String STUB = "no.sikt.graphitron.rewrite.TestServiceStub";
    private static final String FIXTURES =
        "no.sikt.graphitron.codereferences.dummyreferences.SharedValueTypeFixtures";

    @Test
    void batchedServiceProducerBesideRecordComponentRead_agree() {
        var schema = TestSchemaHelper.buildSchema("""
            type Translations { nb: String  en: String }
            type Film @table(name: "film") {
                title: String
                translations: Translations
                    @service(service: {className: "%s", method: "sharedTranslationsByFilm"})
            }
            type FilmSummary {
                translations: Translations
                note: String
            }
            type Query {
                films: [Film]
                summary: FilmSummary
                    @service(service: {className: "%s", method: "sharedFilmSummary"})
            }
            """.formatted(STUB, STUB));

        assertThat(schema.type("Translations"))
            .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.type(GraphitronType.JavaRecordType.class))
            .extracting(GraphitronType.JavaRecordType::fqClassName)
            .isEqualTo(FIXTURES + "$Translations");

        var batched = schema.field("Film", "translations");
        assertThat(batched).isInstanceOf(ChildField.ServiceRecordField.class);
        var read = schema.field("FilmSummary", "translations");
        assertThat(read).isInstanceOf(ChildField.RecordReadField.class);
        assertThat(((ChildField.RecordReadField) read).locator())
            .isInstanceOf(no.sikt.graphitron.rewrite.model.ValueLocator.JavaAccessor.class);

        assertSameClaim(batched, read, FIXTURES + ".Translations");
        assertNoDisagreement(schema);
    }

    @Test
    void rootServiceProducerBesideRecordComponentRead_agree() {
        // The same defect with no batching involved: the fix is not specific to @service loaders.
        var schema = TestSchemaHelper.buildSchema("""
            type Translations { nb: String  en: String }
            type FilmSummary {
                translations: Translations
                note: String
            }
            type Query {
                translations: Translations
                    @service(service: {className: "%s", method: "sharedTranslations"})
                summary: FilmSummary
                    @service(service: {className: "%s", method: "sharedFilmSummary"})
            }
            """.formatted(STUB, STUB));

        var root = schema.field("Query", "translations");
        assertThat(root).isInstanceOf(QueryField.QueryServiceRecordField.class);
        var read = schema.field("FilmSummary", "translations");
        assertThat(read).isInstanceOf(ChildField.RecordReadField.class);

        assertSameClaim(root, read, FIXTURES + ".Translations");
        assertNoDisagreement(schema);
    }

    @Test
    void listShapedRecordComponentRead_claimsTheElementBackingClass() {
        // The claim is the element's backing class at every SDL wrapper depth, not the wrapper's
        // own class. Nothing vets the accessor's element class here (FieldBuilder erases a
        // list-shaped SDL return to raw java.util.List before the accessor check), so this pins
        // the known gap as a known one rather than an untested one.
        var schema = TestSchemaHelper.buildSchema("""
            type Translations { nb: String  en: String }
            type TranslationsList {
                translations: [Translations]
                note: String
            }
            type Query {
                translations: Translations
                    @service(service: {className: "%s", method: "sharedTranslations"})
                list: TranslationsList
                    @service(service: {className: "%s", method: "sharedTranslationsList"})
            }
            """.formatted(STUB, STUB));

        var root = schema.field("Query", "translations");
        var read = schema.field("TranslationsList", "translations");
        assertThat(read).isInstanceOf(ChildField.RecordReadField.class);
        assertThat(((ChildField.RecordReadField) read).returnType().wrapper().isList()).isTrue();

        assertSameClaim(root, read, FIXTURES + ".Translations");
        assertNoDisagreement(schema);
    }

    @Test
    void nestedBackingClass_compositeAndComponentRead_spellTheClassOnce() {
        // The composite carrier answers through the single backing-class mint, so its nested class
        // is spelled Outer.Nested rather than ClassName.bestGuess's Outer$Nested; the component
        // read derives the same spelling from the same fact.
        var schema = TestSchemaHelper.buildSchema("""
            type Film @table(name: "film") { title: String }
            type Actor @table(name: "actor") { firstName: String @field(name: "first_name") }
            type NestedResult {
                film: Film! @field(name: "filmRecord")
                actors: [Actor] @field(name: "actorRecords")
            }
            type NestedPayload { results: [NestedResult] }
            type NestedHolder {
                composite: NestedResult
                note: String
            }
            type Query {
                holder: NestedHolder
                    @service(service: {className: "%s", method: "sharedNestedCompositeHolder"})
            }
            type Mutation {
                createNested: NestedPayload
                    @service(service: {className: "%s", method: "sharedNestedComposites"})
            }
            """.formatted(STUB, STUB));

        var composite = schema.field("NestedPayload", "results");
        assertThat(composite).isInstanceOf(ChildField.RecordCompositeField.class);
        var read = schema.field("NestedHolder", "composite");
        assertThat(read).isInstanceOf(ChildField.RecordReadField.class);

        assertSameClaim(composite, read, FIXTURES + ".NestedComposite");
        assertNoDisagreement(schema);
    }

    @Test
    void rootServiceTwins_overOneClassBackedPayload_answerTheSameClaim() {
        // The arm-level half of the same defect: the query twin answered Plain and the mutation
        // twin TableRecord for one payload class, so the two could never agree.
        var schema = TestSchemaHelper.buildSchema("""
            type Translations { nb: String  en: String }
            type SharedPayload {
                status: String
                translations: Translations
            }
            type Query {
                payload: SharedPayload
                    @service(service: {className: "%s", method: "sharedPayload"})
            }
            type Mutation {
                writePayload: SharedPayload
                    @service(service: {className: "%s", method: "sharedPayload"})
            }
            """.formatted(STUB, STUB));

        var query = schema.field("Query", "payload");
        assertThat(query).isInstanceOf(QueryField.QueryServiceRecordField.class);
        var mutation = schema.field("Mutation", "writePayload");
        assertThat(mutation).isInstanceOf(MutationField.MutationServiceRecordField.class);

        assertSameClaim(query, mutation, FIXTURES + ".SharedPayload");
        assertNoDisagreement(schema);
    }

    @Test
    void rootServiceTwins_overAnUnbackedCarrierPayload_bothClaimTheTableRecord() {
        // The carrier population: no reflected backing class, but a resolved table, so both twins
        // hand a typed FilmRecord down. A factory default of "no claim" on the query side would
        // silently drop a producer that really does put a typed record at env.getSource().
        var schema = TestSchemaHelper.buildSchema("""
            type Film @table(name: "film") { title: String }
            type FilmListPayload { films: [Film!] }
            type Query {
                readFilms: FilmListPayload
                    @service(service: {className: "%s", method: "getFilmsAsList"})
            }
            type Mutation {
                runFilms: FilmListPayload
                    @service(service: {className: "%s", method: "getFilmsAsList"})
            }
            """.formatted(STUB, STUB));

        var query = (OutputField) schema.field("Query", "readFilms");
        var mutation = (OutputField) schema.field("Mutation", "runFilms");
        assertThat(query.domainReturnType())
            .isInstanceOf(DomainReturnType.TableRecord.class)
            .isEqualTo(mutation.domainReturnType());
        assertThat(((DomainReturnType.TableRecord) query.domainReturnType()).recordClass().simpleName())
            .isEqualTo("FilmRecord");
        assertNoDisagreement(schema);
    }

    @Test
    void rootServiceTwins_overAReflectedJooqTableRecord_bothClaimTheTableRecord() {
        // The sub-population that separates the table-resolved rule from an fqClassName-keyed one:
        // FilmDetails carries a reflected backing class *and* a resolved table. Keyed on the null,
        // both twins would mint Plain(FilmRecord), which agrees but falsifies Plain's "no jOOQ
        // surface" contract and demotes the arm.
        var schema = TestSchemaHelper.buildSchema("""
            type FilmDetails { title: String }
            type Query {
                film: FilmDetails
                    @service(service: {className: "%s", method: "getFilm"})
            }
            type Mutation {
                writeFilm: FilmDetails
                    @service(service: {className: "%s", method: "runFilm"})
            }
            """.formatted(STUB, STUB));

        assertThat(schema.type("FilmDetails")).isInstanceOf(GraphitronType.JooqTableRecordType.class);
        var query = (OutputField) schema.field("Query", "film");
        var mutation = (OutputField) schema.field("Mutation", "writeFilm");
        assertThat(query.domainReturnType())
            .isEqualTo(new DomainReturnType.TableRecord(
                no.sikt.graphitron.javapoet.ClassName.get(
                    "no.sikt.graphitron.rewrite.test.jooq.tables.records", "FilmRecord")))
            .isEqualTo(mutation.domainReturnType());
        assertNoDisagreement(schema);
    }

    /**
     * {@code ReturnTypeRef.ScalarReturnType} is not a synonym for "SDL scalar": an object type
     * that classified as a nesting type falls through to that arm and <em>is</em> grouped by the
     * conflict reduction. Both {@code @service} producers over such a type therefore keep deriving
     * a real claim; an implementer reading the arm as "never grouped" and collapsing it to
     * no-claim would drop live producers out of the comparison with nothing failing.
     *
     * <p>The pair genuinely disagrees here (the nesting producer hands down a generic jOOQ
     * {@code Record}, the service hands down a {@code String}), so the rejection firing is the
     * assertion that the claims are still behaviour-bearing.
     */
    @Test
    void ungroundedObjectTypeReturnedByService_stillClaims() {
        var schema = TestSchemaHelper.buildSchema("""
            type Film @table(name: "film") {
                title: String
                info: FilmInfo
                detail: FilmInfo
                    @service(service: {className: "%s", method: "sharedInfoByFilm"})
            }
            type FilmInfo { title: String }
            type Query {
                films: [Film]
                info: FilmInfo
                    @service(service: {className: "%s", method: "get"})
            }
            """.formatted(STUB, STUB));

        assertThat(schema.type("FilmInfo")).isInstanceOf(GraphitronType.NestingType.class);

        var root = schema.field("Query", "info");
        assertThat(root).isInstanceOf(QueryField.QueryServiceRecordField.class);
        assertThat(((QueryField.QueryServiceRecordField) root).returnType())
            .isInstanceOf(no.sikt.graphitron.rewrite.model.ReturnTypeRef.ScalarReturnType.class);
        assertThat(((OutputField) root).domainReturnType())
            .isEqualTo(new DomainReturnType.Plain(
                no.sikt.graphitron.javapoet.ClassName.get(String.class)));

        var child = schema.field("Film", "detail");
        assertThat(child).isInstanceOf(ChildField.ServiceRecordField.class);
        assertThat(((OutputField) child).domainReturnType())
            .as("the per-key V the loader is typed with, peeled off the rows method's outer Map")
            .isEqualTo(new DomainReturnType.Plain(
                no.sikt.graphitron.javapoet.ClassName.get(String.class)));

        assertThat(TestSchemaHelper.diagnosticMessages(schema))
            .as("the nesting producer's generic Record really does disagree with a String")
            .contains("disagreeing env.getSource() Java domain types");
    }

    /** Both producers of {@code sdlType} state the same backing-class claim. */
    private static void assertSameClaim(
            no.sikt.graphitron.rewrite.model.GraphitronField a,
            no.sikt.graphitron.rewrite.model.GraphitronField b,
            String expectedCanonicalClass) {
        var expected = new DomainReturnType.Plain(
            no.sikt.graphitron.javapoet.ClassName.bestGuess(expectedCanonicalClass));
        assertThat(((OutputField) a).domainReturnType())
            .as("producer claim")
            .isEqualTo(expected)
            .isEqualTo(((OutputField) b).domainReturnType());
    }

    private static void assertNoDisagreement(no.sikt.graphitron.rewrite.GraphitronSchema schema) {
        assertThat(TestSchemaHelper.diagnosticMessages(schema))
            .doesNotContain("disagreeing env.getSource() Java domain types");
    }
}
