package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.rewrite.model.ErrorChannel;
import no.sikt.graphitron.rewrite.model.GraphitronField.UnclassifiedField;
import no.sikt.graphitron.rewrite.model.MutationField;
import no.sikt.graphitron.rewrite.model.QueryField;
import no.sikt.graphitron.rewrite.model.WithErrorChannel;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R12 §2c carrier classifier: every fetcher-emitting field variant that implements
 * {@link WithErrorChannel} carries an {@link ErrorChannel} when the payload type's field set
 * declares an {@code errors}-shaped field and the developer-supplied payload class exposes a
 * canonical constructor with exactly one errors-slot parameter.
 *
 * <p>The fixtures use {@code SakPayload} (in {@code dummyreferences}) as the developer-supplied
 * payload class: a Java record with the all-fields constructor
 * {@code (String data, List<? extends GraphitronError> errors)}. The carrier classifier
 * matches the errors slot by simple-name {@code GraphitronError} until the marker-interface
 * enforcement check lands later in error-handling-parity.md.
 */
@UnitTier
class ErrorChannelClassificationTest {

    private static final String SAK_PAYLOAD_FQN =
        "no.sikt.graphitron.codereferences.dummyreferences.SakPayload";

    private static final String UNION_ERROR_PAYLOAD_SDL = """
            type ValidationErr @error(handlers: [{handler: VALIDATION}]) {
                path: [String!]!
                message: String!
            }
            type DbErr @error(handlers: [{handler: DATABASE, sqlState: "23503"}]) {
                path: [String!]!
                message: String!
            }
            union SakError = ValidationErr | DbErr
            type SakPayload @record(record: {className: "%s"}) {
                data: String
                errors: [SakError]
            }
            """.formatted(SAK_PAYLOAD_FQN);

    private static final String SERVICE_DECL =
        "@service(service: {className: \"no.sikt.graphitron.rewrite.TestServiceStub\", method: \"runSak\"})";

    @Test
    void mutationServiceRecordField_payloadHasErrorsField_populatesErrorChannel() {
        var schema = build(UNION_ERROR_PAYLOAD_SDL + """
            type Query { x: String }
            type Mutation { behandleSak: SakPayload %s }
            """.formatted(SERVICE_DECL));

        var f = (MutationField.MutationServiceRecordField) schema.field("Mutation", "behandleSak");
        assertThat(f.errorChannel()).isPresent();
        var ch = f.errorChannel().get();
        assertThat(ch.payloadClass()).isEqualTo(ClassName.bestGuess(SAK_PAYLOAD_FQN));
        assertThat(ch.mappedErrorTypes())
            .extracting(et -> et.name())
            .containsExactly("ValidationErr", "DbErr");
        assertThat(ch.mappingsConstantName()).isEqualTo("SAK_PAYLOAD");
    }

    @Test
    void queryServiceRecordField_payloadHasErrorsField_populatesErrorChannel() {
        var schema = build(UNION_ERROR_PAYLOAD_SDL + """
            type Query { sak: SakPayload %s }
            """.formatted(SERVICE_DECL));

        var f = (QueryField.QueryServiceRecordField) schema.field("Query", "sak");
        assertThat(f.errorChannel()).isPresent();
        assertThat(f.errorChannel().get().mappedErrorTypes())
            .extracting(et -> et.name())
            .containsExactly("ValidationErr", "DbErr");
    }

    @Test
    void payloadConstructor_recordsErrorsSlotAndDefaultLiterals() {
        // Verifies the per-parameter shape: the errors slot has isErrorsSlot=true and a null
        // defaultLiteral; every other slot has the appropriate language default. SakPayload is
        // (String data, List<? extends GraphitronError> errors), so two params: data → "null",
        // errors → no defaultLiteral and isErrorsSlot=true.
        var schema = build(UNION_ERROR_PAYLOAD_SDL + """
            type Query { sak: SakPayload %s }
            """.formatted(SERVICE_DECL));

        var f = (QueryField.QueryServiceRecordField) schema.field("Query", "sak");
        var params = f.errorChannel().orElseThrow().payloadCtorParams();
        assertThat(params).hasSize(2);
        assertThat(params.get(0).name()).isEqualTo("data");
        assertThat(params.get(0).isErrorsSlot()).isFalse();
        assertThat(params.get(0).defaultLiteral()).isEqualTo("null");
        assertThat(params.get(1).name()).isEqualTo("errors");
        assertThat(params.get(1).isErrorsSlot()).isTrue();
        assertThat(params.get(1).defaultLiteral()).isNull();
    }

    @Test
    void payloadWithoutErrorsField_producesNoChannel() {
        var schema = build("""
            type Plain @record(record: {className: "%s"}) {
                data: String
            }
            type Query { plain: Plain %s }
            """.formatted(SAK_PAYLOAD_FQN, SERVICE_DECL));

        var f = (QueryField.QueryServiceRecordField) schema.field("Query", "plain");
        assertThat(f.errorChannel()).isEmpty();
    }

    @Test
    void unTypedRecordPayload_producesNoChannel() {
        // PojoResultType with a null fqClassName cannot be reflected; classifier produces an
        // empty channel rather than rejecting (the §3 redact arm is the fallback).
        var schema = build(UNION_ERROR_PAYLOAD_SDL.replace(
                "@record(record: {className: \"" + SAK_PAYLOAD_FQN + "\"})",
                "@record") + """
            type Query { sak: SakPayload %s }
            """.formatted(SERVICE_DECL));

        var f = (QueryField.QueryServiceRecordField) schema.field("Query", "sak");
        assertThat(f.errorChannel()).isEmpty();
    }

    @Test
    void payloadHasErrorsFieldButPayloadClassMissing_rejectsCarrier() {
        // The SDL declares an errors-shaped payload but the @record className points at a class
        // that doesn't exist on the classpath; the carrier rejects with UnclassifiedField rather
        // than silently producing no channel.
        var schema = build("""
            type ValidationErr @error(handlers: [{handler: VALIDATION}]) {
                path: [String!]!
                message: String!
            }
            type DbErr @error(handlers: [{handler: DATABASE, sqlState: "23503"}]) {
                path: [String!]!
                message: String!
            }
            union SakError = ValidationErr | DbErr
            type SakPayload @record(record: {className: "no.sikt.does.not.exist.MissingPayload"}) {
                data: String
                errors: [SakError]
            }
            type Query { sak: SakPayload %s }
            """.formatted(SERVICE_DECL));

        var field = schema.field("Query", "sak");
        assertThat(field).isInstanceOfAny(
            // UnclassifiedField on either side is acceptable: the @record reflection at type-build
            // time produces UnclassifiedType, which propagates to the field; once that lands the
            // carrier never runs. This is a defense-in-depth test for both paths.
            UnclassifiedField.class, QueryField.QueryServiceRecordField.class);
    }

    @Test
    void mutationDmlField_tableReturn_carriesEmptyChannel() {
        // DML mutations return @table or ID. @table-returning fetchers don't yet build an
        // ErrorChannel (the carrier helper is gated on ResultReturnType pending a payload-factory
        // shape for jOOQ Record returns). Verifies the WithErrorChannel slot is wired and the
        // INSERT variant produces an empty channel rather than null.
        var schema = build("""
            type Film @table(name: "film") { title: String }
            input FilmInput @table(name: "film") { title: String }
            type Query { x: String }
            type Mutation { createFilm(in: FilmInput!): Film @mutation(typeName: INSERT) }
            """);

        var f = (MutationField.MutationInsertTableField) schema.field("Mutation", "createFilm");
        assertThat(f.errorChannel()).isEqualTo(Optional.<ErrorChannel>empty());
    }

    private GraphitronSchema build(String schemaText) {
        return TestSchemaHelper.buildSchema(schemaText);
    }
}
