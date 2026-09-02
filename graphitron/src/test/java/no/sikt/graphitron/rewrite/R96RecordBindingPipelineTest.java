package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.rewrite.model.GraphitronType;
import no.sikt.graphitron.model.diagnostics.Rejection;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pipeline-tier coverage: reflection-driven SDL → backing-class binding through the
 * {@link no.sikt.graphitron.rewrite.RecordBindingResolver}.
 *
 * <p>Binding is reflection-only and {@code @record} is inert for it, so these fixtures carry
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
        // List<FilmRecord>. Carriers unify on JooqTableRecordType: the wrapper binds to the
        // element's table record (the earlier "wrapper does not bind" path is retired), and the inner
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

    @Test
    void multiProducerInput_reachableThroughTheWalk_keepsTypedRejection() {
        // The input-axis twin of the case above, pinned on the walk's argument edges: two
        // @service producers consume ClashInput with different reflected parameter types
        // (FilmRecord via modifyFilmRecord, TestInputBean via runWithInputBean), so the
        // input-axis fold surfaces RecordBindingMultiProducer and surfaceMultiProducerRejections
        // seeds the demotion before the walk. The walk then reaches ClashInput through the
        // argument edge and classifyAndRegister's rejection-first guard re-registers the same
        // payload. The assertion is on the rejection *variant*, not merely UnclassifiedType-ness:
        // a live re-classification would re-demote to a generic structural rejection, which
        // passes a class-only assertion while silently swapping the payload the validator and
        // candidate-hint paths key on.
        var schema = TestSchemaHelper.buildSchema("""
            input ClashInput { title: String }
            type FilmDetails { title: String }
            type Query { x: String }
            type Mutation {
                viaRecord(in: ClashInput): String
                    @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "modifyFilmRecord"})
                viaBean(input: ClashInput): FilmDetails
                    @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "runWithInputBean"})
            }
            """);

        var t = schema.type("ClashInput");
        assertThat(t).isInstanceOf(GraphitronType.UnclassifiedType.class);
        var unc = (GraphitronType.UnclassifiedType) t;
        assertThat(unc.rejection())
            .isInstanceOf(Rejection.AuthorError.RecordBindingMultiProducer.class);
        var mp = (Rejection.AuthorError.RecordBindingMultiProducer) unc.rejection();
        assertThat(mp.sdlTypeName()).isEqualTo("ClashInput");
        assertThat(mp.bindings()).hasSizeGreaterThanOrEqualTo(2);
    }

    @Test
    void malformedArgMappingLeavesTheProbeObservingNoOverrides() {
        // The producer-binding probe reads its overrides off the shared argMapping parse, which
        // fails the whole string rather than skipping the offending entry. So a syntax error
        // anywhere in the string costs the reference every observation, including the ones its
        // well-formed siblings would have grounded. That widening is deliberate: the same string
        // is a build error at the ExternalCodeReference consumer (asserted below), so the
        // reference's observations are moot either way, and salvaging them would mean keeping a
        // second, error-tolerant parser, which is what made argMapping behave differently per
        // directive in the first place.
        //
        // runWithInputBeanRenamed takes `TestInputBean payload`, so only the argMapping override
        // connects the SDL argument `input` to it: with the override observed the input type
        // binds to the bean, without it there is nothing to bind from.
        var bound = TestSchemaHelper.buildSchema("""
            input BeanInput { title: String }
            type Query { x: String }
            type Mutation {
                viaBean(input: BeanInput): String
                    @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub",
                                       method: "runWithInputBeanRenamed",
                                       argMapping: "payload: input"})
            }
            """);
        assertThat(bound.type("BeanInput"))
            .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.type(
                GraphitronType.JavaRecordInputType.class))
            .extracting(GraphitronType.JavaRecordInputType::fqClassName)
            .isEqualTo("no.sikt.graphitron.rewrite.TestInputBean");

        var malformed = TestSchemaHelper.buildSchema("""
            input BeanInput { title: String }
            type Query { x: String }
            type Mutation {
                viaBean(input: BeanInput): String
                    @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub",
                                       method: "runWithInputBeanRenamed",
                                       argMapping: "payload: input, oops"})
            }
            """);
        assertThat(malformed.type("BeanInput"))
            .as("a syntax error anywhere in the string leaves the probe with no overrides at all")
            .isNotInstanceOf(GraphitronType.JavaRecordInputType.class);
        assertThat(((no.sikt.graphitron.rewrite.model.GraphitronField.UnclassifiedField) malformed.field("Mutation", "viaBean")).reason())
            .as("the diagnostic is the ExternalCodeReference consumer's, exactly once")
            .contains("argMapping syntax error");
    }
}
