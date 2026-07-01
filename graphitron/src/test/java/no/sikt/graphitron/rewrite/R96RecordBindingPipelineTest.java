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
 * <p>R307: binding is reflection-only and {@code @record} is inert for it, so these fixtures carry
 * no applied {@code @record}; the directive-ignored warning's three variants, its suppression, and
 * the reachability gate live in {@link RecordDirectiveIgnoredWarningTest}. What stays here is the
 * binding behaviour itself: a producer's reflected return type grounds the SDL type, list carriers
 * unify on the element's table record, and a multi-producer disagreement surfaces a typed rejection.
 */
@PipelineTier
class R96RecordBindingPipelineTest {

    @Test
    void singleServiceReturn_bindsToJooqTableRecord() {
        var schema = TestSchemaHelper.buildSchema("""
            type FilmDetails {
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
    }

    @Test
    void serviceListCarrier_bindsWrapperToJooqTableRecord() {
        // FilmListPayload is a plain SDL Object returned by a @service mutation whose method returns
        // List<FilmRecord>. R276 unifies carriers on JooqTableRecordType: the wrapper binds to the
        // element's table record (the R75 "wrapper does not bind" path is retired), and the inner
        // data field reads off it through the standard record-backed path.
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
            .isInstanceOf(GraphitronType.JooqTableRecordType.class);
    }

    @Test
    void multiProducer_disagreement_surfacesAsTypedRejection() {
        // Two @service producers reach FilmDetails; one returns FilmRecord, the other returns
        // LanguageRecord. The producer-agreement check surfaces RecordBindingMultiProducer and
        // FilmDetails demotes to UnclassifiedType.
        var schema = TestSchemaHelper.buildSchema("""
            type FilmDetails {
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
