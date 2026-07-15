package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.rewrite.model.ChildField;
import no.sikt.graphitron.rewrite.model.GraphitronField.UnclassifiedField;
import no.sikt.graphitron.rewrite.model.GraphitronType;
import no.sikt.graphitron.rewrite.model.MutationField;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R159 pipeline-tier coverage for the {@code @field(name: "$source")} sigil on the
 * payload data field. SDL → classified model assertions (admit, type-mismatch
 * reject, unknown-sigil reject, bare-name regression, model-shape regression, and the
 * non-carrier-site regression that the sigil-aware arm does not silently rewire the
 * non-carrier paths to learn about sigils) plus validator-surface assertions on the
 * three rejection cases — confirming the canonical {@link FieldSourceSigil} messages
 * propagate end-to-end through {@link UnclassifiedField} to
 * {@link GraphitronSchemaValidator}'s {@link ValidationError} output.
 */
@PipelineTier
class FieldSourceSigilPipelineTest {

    private static List<ValidationError> validate(GraphitronSchema schema) {
        return new GraphitronSchemaValidator().validate(schema);
    }

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
     * {@code @service}-backed mutation. The directive admits as a no-op confirmation of the
     * implicit binding the SDL element type already produces; the mutation classifies as
     * {@link MutationField.MutationServiceRecordField} and the data field classifies as
     * {@link ChildField.BatchedTableField}.
     */
    @Test
    void sourceSigil_serviceCarrier_typeMatches_admits() {
        var schema = TestSchemaHelper.buildSchema(FILM_PAYLOAD_WITH_SOURCE);

        var mutField = schema.field("Mutation", "runFilms");
        assertThat(mutField).isInstanceOf(MutationField.MutationServiceRecordField.class);

        // R276: the carrier binds to its element's JooqTableRecordType; the data
        // field classifies as SingleRecordTableField (the @field(name: "$source") directive is a
        // no-op confirmation of that binding).
        assertThat(schema.type("FilmListPayload"))
            .isInstanceOf(GraphitronType.JooqTableRecordType.class);
        var dataField = schema.field("FilmListPayload", "films");
        assertThat(dataField).isInstanceOf(ChildField.BatchedTableField.class);
        var srtf = (ChildField.BatchedTableField) dataField;
        assertThat(srtf.returnType().table().tableName()).isEqualTo("film");
    }

    /**
     * Model-shape regression: the same carrier without {@code @field(name: "$source")} produces
     * a byte-identical {@link ChildField.BatchedTableField} on the data field. R159 reshapes
     * no existing fixture.
     */
    @Test
    void sourceSigil_modelShape_identicalWithAndWithoutDirective() {
        var withDirective = TestSchemaHelper.buildSchema(FILM_PAYLOAD_WITH_SOURCE);
        var withoutDirective = TestSchemaHelper.buildSchema(FILM_PAYLOAD_NO_DIRECTIVE);

        var w = (ChildField.BatchedTableField) withDirective.field("FilmListPayload", "films");
        var n = (ChildField.BatchedTableField) withoutDirective.field("FilmListPayload", "films");
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

        // Validator-surface coverage: the classifier's rejection propagates through
        // UnclassifiedField to GraphitronSchemaValidator's ValidationError output.
        assertThat(validate(schema))
            .extracting(ValidationError::message)
            .anyMatch(m -> m.contains(FieldSourceSigil.UPSTREAM_ROOT_LITERAL)
                && m.contains("getLanguages")
                && m.contains("LanguageRecord")
                && m.contains("FilmRecord"));
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

        // Validator-surface coverage: the canonical unknown-sigil message reaches
        // ValidationReport.errors() verbatim.
        assertThat(validate(schema))
            .extracting(ValidationError::message)
            .anyMatch(m -> m.contains(FieldSourceSigil.unknownSigilMessage("$bogus")));
    }

    /**
     * Bare-name pin under R178: {@code @field(name: "X")} (any non-{@code $}-prefixed value) at
     * the carrier data field admits identically to the no-{@code @field} form. R178 retired the
     * carrier walk's forbidden-directives HardReject (the SettKvotesporsmal bug's mechanism);
     * the bare-name value is a no-op at the data-field site.
     */
    @Test
    void sourceSigil_bareNameAtCarrier_admitsUnderR178() {
        var schema = TestSchemaHelper.buildSchema("""
            type Film @table(name: "film") { title: String }
            input FilmInput @table(name: "film") { title: String }
            type FilmPayload { films: [Film!] @field(name: "films_alias") }
            type Query { x: String }
            type Mutation { createFilms(in: [FilmInput!]!): FilmPayload @mutation(typeName: INSERT) }
            """);

        var mutField = schema.field("Mutation", "createFilms");
        assertThat(mutField).isInstanceOf(MutationField.MutationBulkDmlRecordField.class);
    }

    /**
     * Non-carrier-site regression: {@code @field(name: "$source")} on a regular
     * record-backed field continues to surface today's
     * {@code Rejection.accessorMismatch} on the literal string {@code $source}. R159 does not
     * silently rewire the non-carrier paths to learn about sigils.
     */
    @Test
    void sourceSigil_atNonCarrierRecordSite_surfacesAccessorMismatch() {
        var schema = TestSchemaHelper.buildSchema("""
            type Foo {
                renamed: String @field(name: "$source")
            }
            type Query {
                foo: Foo @service(service: {className: "no.sikt.graphitron.codereferences.dummyreferences.DummyService", method: "makeDummyRecord"})
            }
            """);
        var field = schema.field("Foo", "renamed");
        assertThat(field).isInstanceOf(UnclassifiedField.class);
        var reason = ((UnclassifiedField) field).rejection().message();
        // The classifier doesn't strip the leading '$'; it goes through ClassAccessorResolver
        // looking for an accessor named "$source", finds none, and surfaces an accessor-mismatch.
        // The literal '$source' string must remain attempt-token in the surfaced reason.
        assertThat(reason).contains("$source");

        // Validator-surface coverage: the non-carrier path's existing accessor-mismatch
        // rejection (today's text, NOT FieldSourceSigil.sourceSigilNotDefinedHereMessage)
        // reaches ValidationReport.errors() unchanged. R159 intentionally leaves the
        // non-carrier paths structurally identical to today.
        assertThat(validate(schema))
            .extracting(ValidationError::message)
            .anyMatch(m -> m.contains("Foo.renamed") && m.contains(FieldSourceSigil.UPSTREAM_ROOT_LITERAL));
    }
}
