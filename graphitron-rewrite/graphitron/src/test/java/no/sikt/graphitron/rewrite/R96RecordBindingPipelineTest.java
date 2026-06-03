package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.rewrite.model.GraphitronType;
import no.sikt.graphitron.rewrite.model.Rejection;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R96 pipeline-tier coverage: reflection-driven SDL → backing-class binding through the
 * {@link no.sikt.graphitron.rewrite.RecordBindingResolver}.
 *
 * <p>Pins the directive-ignored warning's three variants (Matches, Disagrees, Shadowed-by-@table),
 * the multi-producer agreement check, and the additive-invariant property that reachable types
 * carrying {@code @record} stay backed under R96.
 */
@PipelineTier
class R96RecordBindingPipelineTest {

    @Test
    void matches_directiveAndReflectionAgree_emitsRedundantWarning() {
        var schema = TestSchemaHelper.buildSchema("""
            type FilmDetails @record(record: {className: "no.sikt.graphitron.rewrite.test.jooq.tables.records.FilmRecord"}) {
                title: String
            }
            type Query {
                film: FilmDetails
                    @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "getFilm"})
            }
            """);

        var t = schema.type("FilmDetails");
        assertThat(t).isInstanceOf(GraphitronType.JooqTableRecordType.class);
        assertThat(((GraphitronType.JooqTableRecordType) t).fqClassName())
            .isEqualTo("no.sikt.graphitron.rewrite.test.jooq.tables.records.FilmRecord");

        assertThat(schema.warnings())
            .extracting(BuildWarning::message)
            .anyMatch(m -> m.contains("FilmDetails")
                && m.contains("The directive is redundant; remove it"));
    }

    @Test
    void disagrees_directiveLiesAboutClass_reflectionWinsAndWarns() {
        var schema = TestSchemaHelper.buildSchema("""
            type FilmDetails @record(record: {className: "no.sikt.graphitron.rewrite.test.jooq.tables.records.FilmRecord"}) {
                title: String
            }
            type Query {
                language: FilmDetails
                    @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "getLanguage"})
            }
            """);

        var t = schema.type("FilmDetails");
        // Reflection-derived class wins over the directive's claim.
        assertThat(t).isInstanceOf(GraphitronType.JooqTableRecordType.class);
        assertThat(((GraphitronType.JooqTableRecordType) t).fqClassName())
            .isEqualTo("no.sikt.graphitron.rewrite.test.jooq.tables.records.LanguageRecord");

        assertThat(schema.warnings())
            .extracting(BuildWarning::message)
            .anyMatch(m -> m.contains("FilmDetails")
                && m.contains("derives a different backing class")
                && m.contains("LanguageRecord"));
    }

    @Test
    void shadowedByTable_inputWithBothDirectives_emitsShadowedVariant() {
        var schema = TestSchemaHelper.buildSchema("""
            input FilmInput
                @table(name: "film")
                @record(record: {className: "no.sikt.graphitron.rewrite.test.jooq.tables.records.FilmRecord"})
            { id: ID }
            type Query { x: String }
            """);

        // The directive-ignored Shadowed-by-@table variant fires for the input.
        assertThat(schema.warnings())
            .extracting(BuildWarning::message)
            .anyMatch(m -> m.contains("FilmInput")
                && m.contains("carries both @table and")
                && m.contains("@record")
                && m.contains("the @record directive is ignored"));
    }

    @Test
    void unreachable_recordTypeIsIgnored_classifiesAsPlainObject() {
        // R276: @record is deprecated and ignored; binding is reflection-only with no directive
        // fallback. A type carrying @record but reached by no producer has no backing class to
        // bind to, so it classifies as a PlainObjectType (the directive supplies nothing). The
        // directive-ignored warning does not fire here (the type isn't "reachable" under the
        // walker's reachability predicate). This pins that the removed className-fallback stays
        // removed.
        var schema = TestSchemaHelper.buildSchema("""
            type FilmDetails @record(record: {className: "no.sikt.graphitron.codereferences.dummyreferences.DummyRecord"}) {
                title: String
            }
            type Query { x: String }
            """);

        assertThat(schema.type("FilmDetails"))
            .isInstanceOf(GraphitronType.PlainObjectType.class);
    }

    @Test
    void plainCarrier_serviceReturnDoesNotBindWrapperType_R75CarrierPathPreserved() {
        // FilmListPayload is a plain SDL Object (no @record) returned by a @service mutation.
        // R96's walker does NOT bind the carrier — the producer feeds the inner data field,
        // not the wrapper. The R75 Phase 1 promotion to PojoResultType.NoBacking is preserved.
        var schema = TestSchemaHelper.buildSchema("""
            type Film @table(name: "film") { title: String }
            type FilmListPayload { films: [Film!] }
            type Query { x: String }
            type Mutation {
                runFilms: FilmListPayload
                    @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "getFilmsAsList"})
            }
            """);

        assertThat(schema.type("FilmListPayload"))
            .isInstanceOf(GraphitronType.PojoResultType.NoBacking.class);
    }

    @Test
    void multiProducer_disagreement_surfacesAsTypedRejection() {
        // Two @service producers reach FilmDetails; one returns FilmRecord, the other returns
        // LanguageRecord. The producer-agreement check surfaces RecordBindingMultiProducer and
        // FilmDetails demotes to UnclassifiedType.
        var schema = TestSchemaHelper.buildSchema("""
            type FilmDetails @record(record: {className: "no.sikt.graphitron.rewrite.test.jooq.tables.records.FilmRecord"}) {
                title: String
            }
            type Query {
                viaFilm: FilmDetails
                    @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "getFilm"})
                viaLanguage: FilmDetails
                    @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "getLanguage"})
            }
            """);

        var t = schema.type("FilmDetails");
        assertThat(t).isInstanceOf(GraphitronType.UnclassifiedType.class);
        var unc = (GraphitronType.UnclassifiedType) t;
        assertThat(unc.rejection())
            .isInstanceOf(Rejection.AuthorError.RecordBindingMultiProducer.class);
        var mp = (Rejection.AuthorError.RecordBindingMultiProducer) unc.rejection();
        assertThat(mp.sdlTypeName()).isEqualTo("FilmDetails");
        assertThat(mp.bindings()).hasSizeGreaterThanOrEqualTo(2);
        assertThat(mp.message())
            .contains("FilmDetails")
            .contains("FilmRecord")
            .contains("LanguageRecord");
    }
}
