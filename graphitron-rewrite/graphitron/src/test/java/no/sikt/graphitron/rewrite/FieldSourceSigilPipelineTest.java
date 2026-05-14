package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.rewrite.model.ChildField;
import no.sikt.graphitron.rewrite.model.GraphitronField.UnclassifiedField;
import no.sikt.graphitron.rewrite.model.GraphitronType;
import no.sikt.graphitron.rewrite.model.MutationField;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R159 pipeline-tier coverage for the {@code @field(name: "$source")} sigil on the
 * carrier-payload data field. SDL → classified model assertions: admit, type-mismatch
 * reject, unknown-sigil reject, bare-name regression, model-shape regression, and the
 * non-carrier-site regression that the sigil-aware arm does not silently rewire the
 * non-carrier paths to learn about sigils.
 */
@PipelineTier
class FieldSourceSigilPipelineTest {

    private static final String FILM_PAYLOAD_WITH_SOURCE = """
        type Film @table(name: "film") { title: String }
        type FilmListPayload { films: [Film!] @field(name: "$source") }
        type Query { x: String }
        type Mutation {
            runFilms: FilmListPayload
                @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "getFilmsAsList"})
        }
        """;

    private static final String FILM_PAYLOAD_NO_DIRECTIVE = """
        type Film @table(name: "film") { title: String }
        type FilmListPayload { films: [Film!] }
        type Query { x: String }
        type Mutation {
            runFilms: FilmListPayload
                @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "getFilmsAsList"})
        }
        """;

    /**
     * R158 admit case: {@code @field(name: "$source")} on the carrier data field of a
     * {@code @service}-backed mutation. The carrier walk admits the directive (it is a no-op
     * confirmation of the implicit binding the SDL element type already produces); the
     * mutation classifies as {@link MutationField.MutationServiceRecordField} and the data
     * field classifies as {@link ChildField.SingleRecordTableField}.
     */
    @Test
    void sourceSigil_serviceCarrier_typeMatches_admits() {
        var schema = TestSchemaHelper.buildSchema(FILM_PAYLOAD_WITH_SOURCE);

        var mutField = schema.field("Mutation", "runFilms");
        assertThat(mutField).isInstanceOf(MutationField.MutationServiceRecordField.class);

        // The carrier promotes to PojoResultType.NoBacking and the data field classifies as
        // SingleRecordTableField (the existing R75 Phase 1 shape; the @field(name: "$source")
        // directive is a no-op confirmation of that binding).
        assertThat(schema.type("FilmListPayload"))
            .isInstanceOf(GraphitronType.PojoResultType.NoBacking.class);
        var dataField = schema.field("FilmListPayload", "films");
        assertThat(dataField).isInstanceOf(ChildField.SingleRecordTableField.class);
        var srtf = (ChildField.SingleRecordTableField) dataField;
        assertThat(srtf.returnType().table().tableName()).isEqualTo("film");
    }

    /**
     * Model-shape regression: the same carrier without {@code @field(name: "$source")} produces
     * a byte-identical {@link ChildField.SingleRecordTableField} on the data field. R159 reshapes
     * no existing fixture.
     */
    @Test
    void sourceSigil_modelShape_identicalWithAndWithoutDirective() {
        var withDirective = TestSchemaHelper.buildSchema(FILM_PAYLOAD_WITH_SOURCE);
        var withoutDirective = TestSchemaHelper.buildSchema(FILM_PAYLOAD_NO_DIRECTIVE);

        var w = (ChildField.SingleRecordTableField) withDirective.field("FilmListPayload", "films");
        var n = (ChildField.SingleRecordTableField) withoutDirective.field("FilmListPayload", "films");
        assertThat(w.returnType()).isEqualTo(n.returnType());
        assertThat(w.sourceKey()).isEqualTo(n.sourceKey());
    }

    /**
     * Type-mismatch case: producer's reflected return type ({@code Result<LanguageRecord>}) does
     * not match the SDL element's backing class ({@code FilmRecord}). The check at the carrier
     * data field site (with {@code $source} admitted) rejects the mutation; the canonical
     * {@link FieldSourceSigil#typeMismatchMessage} surfaces via {@code UnclassifiedField}.
     */
    @Test
    void sourceSigil_serviceCarrier_typeMismatches_rejects() {
        var schema = TestSchemaHelper.buildSchema("""
            type Film @table(name: "film") { title: String }
            type FilmListPayload { films: [Film!] @field(name: "$source") }
            type Query { x: String }
            type Mutation {
                runFilmsWrongType: FilmListPayload
                    @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "getLanguages"})
            }
            """);

        var mutField = schema.field("Mutation", "runFilmsWrongType");
        assertThat(mutField).isInstanceOf(UnclassifiedField.class);
        var reason = ((UnclassifiedField) mutField).rejection().message();
        assertThat(reason)
            .contains(FieldSourceSigil.UPSTREAM_ROOT_LITERAL)
            .contains("getLanguages")
            .contains("LanguageRecord")
            .contains("FilmRecord");
    }

    /**
     * Unknown-sigil case: {@code @field(name: "$bogus")} at the carrier data field rejects with
     * the canonical {@link FieldSourceSigil#unknownSigilMessage} message, BEFORE the existing
     * forbidden-directive {@code HardReject} fires. Runs on the {@code @mutation}-DML path
     * where the rejection surfaces through the established
     * {@link MutationField.MutationBulkDmlRecordField} classifier route.
     */
    @Test
    void sourceSigil_unknownSigilAtCarrier_rejectsWithUnknownSigilMessage() {
        var schema = TestSchemaHelper.buildSchema("""
            type Film @table(name: "film") { title: String }
            input FilmInput @table(name: "film") { title: String }
            type FilmPayload { films: [Film!] @field(name: "$bogus") }
            type Query { x: String }
            type Mutation { createFilms(in: [FilmInput!]!): FilmPayload @mutation(typeName: INSERT) }
            """);

        var mutField = schema.field("Mutation", "createFilms");
        assertThat(mutField).isInstanceOf(UnclassifiedField.class);
        var reason = ((UnclassifiedField) mutField).rejection().message();
        assertThat(reason)
            .contains("Unknown sigil")
            .contains("$bogus")
            .contains(FieldSourceSigil.UPSTREAM_ROOT_LITERAL);
        // The new parse-time arm fires before the forbidden-directive HardReject.
        assertThat(reason).doesNotContain("carries '@field'");
    }

    /**
     * Bare-name regression: {@code @field(name: "X")} (any non-{@code $}-prefixed value) at the
     * carrier data field continues to {@code HardReject} with the existing forbidden-directive
     * message. The sigil-aware arm does not relax the long-standing rejection for non-sigil
     * values.
     */
    @Test
    void sourceSigil_bareNameAtCarrier_continuesToHardRejectAsForbidden() {
        var schema = TestSchemaHelper.buildSchema("""
            type Film @table(name: "film") { title: String }
            input FilmInput @table(name: "film") { title: String }
            type FilmPayload { films: [Film!] @field(name: "films_alias") }
            type Query { x: String }
            type Mutation { createFilms(in: [FilmInput!]!): FilmPayload @mutation(typeName: INSERT) }
            """);

        var mutField = schema.field("Mutation", "createFilms");
        assertThat(mutField).isInstanceOf(UnclassifiedField.class);
        var reason = ((UnclassifiedField) mutField).rejection().message();
        assertThat(reason).contains("carries '@field'");
    }

    /**
     * Non-carrier-site regression: {@code @field(name: "$source")} on a regular
     * {@code @record}-backed field continues to surface today's
     * {@code Rejection.accessorMismatch} on the literal string {@code $source}. R159 does not
     * silently rewire the non-carrier paths to learn about sigils.
     */
    @Test
    void sourceSigil_atNonCarrierRecordSite_surfacesAccessorMismatch() {
        var schema = TestSchemaHelper.buildSchema("""
            type Foo @record(record: {className: "no.sikt.graphitron.codereferences.dummyreferences.DummyRecord"}) {
                renamed: String @field(name: "$source")
            }
            type Query { foo: Foo }
            """);
        var field = schema.field("Foo", "renamed");
        assertThat(field).isInstanceOf(UnclassifiedField.class);
        var reason = ((UnclassifiedField) field).rejection().message();
        // The classifier doesn't strip the leading '$'; it goes through ClassAccessorResolver
        // looking for an accessor named "$source", finds none, and surfaces an accessor-mismatch.
        // The literal '$source' string must remain attempt-token in the surfaced reason.
        assertThat(reason).contains("$source");
    }
}
