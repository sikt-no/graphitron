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
 * Carrier classifier: every fetcher-emitting field variant that implements
 * {@link WithErrorChannel} carries an {@link ErrorChannel} when the payload type's field set
 * declares an {@code errors}-shaped field and the developer-supplied payload class exposes a
 * canonical constructor with exactly one errors-slot parameter.
 *
 * <p>The fixtures use {@code SakPayload} (in {@code dummyreferences}) as the developer-supplied
 * payload class: a Java record with the all-fields constructor
 * {@code (String data, List<Object> errors)}. The carrier classifier matches the errors slot by
 * channel-typed structural match: the parameter is the unique parameterised
 * List/Iterable/Collection whose element-type upper bound is a supertype of every channel
 * {@code @error} class. The {@code Object} element bound matches the source-direct dispatch
 * contract: the per-fetcher catch arm and the wrapper's pre-execution Jakarta validation step
 * push raw {@code Throwable}s and {@code GraphQLError}s into the list at runtime, so the slot
 * must admit both unrelated bounds.
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
        var ch = (no.sikt.graphitron.rewrite.model.ErrorChannel.PayloadClass) f.errorChannel().get();
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
        // Verifies the slot resolution: errorsSlot points at the errors-typed parameter (Phase 1
        // produces ErrorsSlot.CtorParameterIndex); defaultedSlots covers every other slot with
        // its language-default literal. SakPayload is (String data, List<?> errors), so the
        // errors slot's ctor index is 1 and defaultedSlots holds one entry for "data" → "null".
        var schema = build(UNION_ERROR_PAYLOAD_SDL + """
            type Query { sak: SakPayload %s }
            """.formatted(SERVICE_DECL));

        var f = (QueryField.QueryServiceRecordField) schema.field("Query", "sak");
        var ch = (no.sikt.graphitron.rewrite.model.ErrorChannel.PayloadClass) f.errorChannel().orElseThrow();
        assertThat(ch.errorsSlot())
            .isEqualTo(new no.sikt.graphitron.rewrite.model.ErrorsSlot.CtorParameterIndex(1));
        assertThat(ch.defaultedSlots()).hasSize(1);
        var dataSlot = ch.defaultedSlots().get(0);
        assertThat(dataSlot.index()).isEqualTo(0);
        assertThat(dataSlot.name()).isEqualTo("data");
        assertThat(dataSlot.defaultLiteral()).isEqualTo("null");
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
    void unTypedRecordPayload_producesChannelFromReflectedProducer() {
        // R96: the @record directive's className no longer drives binding. Even when @record
        // carries no className, the @service producer's reflected return type grounds the
        // backing class, so the payload class IS reflectable and the error channel populates.
        // (Pre-R96 behaviour: without an explicit className the type would be NoBacking and
        // produce an empty channel; R96 corrects this by reading the producer's return type.)
        var schema = build(UNION_ERROR_PAYLOAD_SDL.replace(
                "@record(record: {className: \"" + SAK_PAYLOAD_FQN + "\"})",
                "@record") + """
            type Query { sak: SakPayload %s }
            """.formatted(SERVICE_DECL));

        var f = (QueryField.QueryServiceRecordField) schema.field("Query", "sak");
        assertThat(f.errorChannel()).isPresent();
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
    void rule7_multipleValidationHandlersInSameChannel_rejectsCarrier() {
        // Two @error types each carrying {handler: VALIDATION} in the same union → channel
        // has two validation fan-out targets, which violates Rule 7. Surfaces as
        // UnclassifiedField on the carrier (not on either @error type itself).
        var schema = build("""
            type ValidationA @error(handlers: [{handler: VALIDATION}]) {
                path: [String!]!
                message: String!
            }
            type ValidationB @error(handlers: [{handler: VALIDATION}]) {
                path: [String!]!
                message: String!
            }
            union SakError = ValidationA | ValidationB
            type SakPayload @record(record: {className: "%s"}) {
                data: String
                errors: [SakError]
            }
            type Query { sak: SakPayload %s }
            """.formatted(SAK_PAYLOAD_FQN, SERVICE_DECL));

        var field = schema.field("Query", "sak");
        assertThat(field).isInstanceOf(UnclassifiedField.class);
        var u = (UnclassifiedField) field;
        assertThat(u.reason())
            .contains("more than one {handler: VALIDATION}")
            .contains("ValidationA")
            .contains("ValidationB");
    }

    @Test
    void validationCoexistsWithBroadExceptionHandler_isAccepted() {
        // §5 retire of rule 9: VALIDATION runs as a wrapper pre-execution step and never
        // reaches the dispatcher, so a coexisting broad ExceptionHandler is no longer a
        // shadowing risk. The runtime arms each have their own source path: validation
        // violations come back from Validator.validate ahead of the body call, and any
        // post-body throw flows through the dispatch arm matched by the GENERIC handler.
        var schema = build("""
            type ValidationErr @error(handlers: [{handler: VALIDATION}]) {
                path: [String!]!
                message: String!
            }
            type RuntimeErr @error(handlers: [{handler: GENERIC, className: "java.lang.RuntimeException"}]) {
                path: [String!]!
                message: String!
            }
            union SakError = ValidationErr | RuntimeErr
            type SakPayload @record(record: {className: "%s"}) {
                data: String
                errors: [SakError]
            }
            type Query { sak: SakPayload %s }
            """.formatted(SAK_PAYLOAD_FQN, SERVICE_DECL));

        var f = (QueryField.QueryServiceRecordField) schema.field("Query", "sak");
        assertThat(f.errorChannel()).isPresent();
        assertThat(f.errorChannel().get().mappedErrorTypes())
            .extracting(et -> et.name())
            .containsExactly("ValidationErr", "RuntimeErr");
    }

    @Test
    void validationCoexistsWithNarrowExceptionHandler_isAccepted() {
        // VALIDATION + a narrow ExceptionHandler is fine; both source paths are independent
        // (wrapper pre-step for validation, dispatcher source-order match for IAE).
        var schema = build("""
            type ValidationErr @error(handlers: [{handler: VALIDATION}]) {
                path: [String!]!
                message: String!
            }
            type ArgErr @error(handlers: [{handler: GENERIC, className: "java.lang.IllegalArgumentException"}]) {
                path: [String!]!
                message: String!
            }
            union SakError = ValidationErr | ArgErr
            type SakPayload @record(record: {className: "%s"}) {
                data: String
                errors: [SakError]
            }
            type Query { sak: SakPayload %s }
            """.formatted(SAK_PAYLOAD_FQN, SERVICE_DECL));

        var f = (QueryField.QueryServiceRecordField) schema.field("Query", "sak");
        assertThat(f.errorChannel()).isPresent();
        assertThat(f.errorChannel().get().mappedErrorTypes())
            .extracting(et -> et.name())
            .containsExactly("ValidationErr", "ArgErr");
    }

    @Test
    void rule8_duplicateExceptionHandlersAcrossTypes_rejectsCarrier() {
        // Two @error types in the same channel each declare {handler: GENERIC, className:
        // "java.lang.RuntimeException"} with no matches. Identical (variant, criteria) tuples;
        // the second is unreachable at dispatch.
        var schema = build("""
            type RuntimeA @error(handlers: [{handler: GENERIC, className: "java.lang.RuntimeException"}]) {
                path: [String!]!
                message: String!
            }
            type RuntimeB @error(handlers: [{handler: GENERIC, className: "java.lang.RuntimeException"}]) {
                path: [String!]!
                message: String!
            }
            union SakError = RuntimeA | RuntimeB
            type SakPayload @record(record: {className: "%s"}) {
                data: String
                errors: [SakError]
            }
            type Query { sak: SakPayload %s }
            """.formatted(SAK_PAYLOAD_FQN, SERVICE_DECL));

        var field = schema.field("Query", "sak");
        assertThat(field).isInstanceOf(UnclassifiedField.class);
        var u = (UnclassifiedField) field;
        assertThat(u.reason())
            .contains("identical match-criteria")
            .contains("RuntimeA")
            .contains("RuntimeB")
            .contains("java.lang.RuntimeException");
    }

    @Test
    void rule8_duplicateExceptionHandlersWithinSameType_rejectsCarrier() {
        // Two ExceptionHandler entries on the same @error type's handlers array, identical
        // criteria. A duplicate within a single @error is rejected the same way as one
        // spanning two types.
        var schema = build("""
            type DupHandlers @error(handlers: [
                {handler: GENERIC, className: "java.lang.RuntimeException"},
                {handler: GENERIC, className: "java.lang.RuntimeException"}
            ]) {
                path: [String!]!
                message: String!
            }
            union SakError = DupHandlers
            type SakPayload @record(record: {className: "%s"}) {
                data: String
                errors: [SakError]
            }
            type Query { sak: SakPayload %s }
            """.formatted(SAK_PAYLOAD_FQN, SERVICE_DECL));

        var field = schema.field("Query", "sak");
        assertThat(field).isInstanceOf(UnclassifiedField.class);
        var u = (UnclassifiedField) field;
        assertThat(u.reason())
            .contains("identical match-criteria")
            .contains("DupHandlers")
            .contains("java.lang.RuntimeException");
    }

    @Test
    void rule8_duplicateSqlStateHandlers_rejectsCarrier() {
        // Two @error types each declare {handler: DATABASE, sqlState: "23503"} → identical
        // SqlStateHandler tuples. The second is unreachable.
        var schema = build("""
            type FkA @error(handlers: [{handler: DATABASE, sqlState: "23503"}]) {
                path: [String!]!
                message: String!
            }
            type FkB @error(handlers: [{handler: DATABASE, sqlState: "23503"}]) {
                path: [String!]!
                message: String!
            }
            union SakError = FkA | FkB
            type SakPayload @record(record: {className: "%s"}) {
                data: String
                errors: [SakError]
            }
            type Query { sak: SakPayload %s }
            """.formatted(SAK_PAYLOAD_FQN, SERVICE_DECL));

        var field = schema.field("Query", "sak");
        assertThat(field).isInstanceOf(UnclassifiedField.class);
        var u = (UnclassifiedField) field;
        assertThat(u.reason())
            .contains("identical match-criteria")
            .contains("FkA")
            .contains("FkB")
            .contains("23503");
    }

    @Test
    void rule8_duplicateVendorCodeHandlers_rejectsCarrier() {
        // Two @error types each declare {handler: DATABASE, code: "1"} → identical
        // VendorCodeHandler tuples.
        var schema = build("""
            type OraA @error(handlers: [{handler: DATABASE, code: "1"}]) {
                path: [String!]!
                message: String!
            }
            type OraB @error(handlers: [{handler: DATABASE, code: "1"}]) {
                path: [String!]!
                message: String!
            }
            union SakError = OraA | OraB
            type SakPayload @record(record: {className: "%s"}) {
                data: String
                errors: [SakError]
            }
            type Query { sak: SakPayload %s }
            """.formatted(SAK_PAYLOAD_FQN, SERVICE_DECL));

        var field = schema.field("Query", "sak");
        assertThat(field).isInstanceOf(UnclassifiedField.class);
        var u = (UnclassifiedField) field;
        assertThat(u.reason())
            .contains("identical match-criteria")
            .contains("OraA")
            .contains("OraB");
    }

    @Test
    void rule8_crossVariantOverlapIsAccepted() {
        // ExceptionHandler(SQLException) and SqlStateHandler("23503") discriminate on different
        // fields. They may both match the same SQLException; §3 source-order picks the first.
        // This is the canonical "specific arm before fallback" pattern and must NOT be rejected.
        var schema = build("""
            type FkErr @error(handlers: [{handler: DATABASE, sqlState: "23503"}]) {
                path: [String!]!
                message: String!
            }
            type DbErr @error(handlers: [{handler: GENERIC, className: "java.sql.SQLException"}]) {
                path: [String!]!
                message: String!
            }
            union SakError = FkErr | DbErr
            type SakPayload @record(record: {className: "%s"}) {
                data: String
                errors: [SakError]
            }
            type Query { sak: SakPayload %s }
            """.formatted(SAK_PAYLOAD_FQN, SERVICE_DECL));

        var f = (QueryField.QueryServiceRecordField) schema.field("Query", "sak");
        assertThat(f.errorChannel()).isPresent();
        assertThat(f.errorChannel().get().mappedErrorTypes())
            .extracting(et -> et.name())
            .containsExactly("FkErr", "DbErr");
    }

    @Test
    void rule8_distinctMatchesValuesDoNotCollide() {
        // Two ExceptionHandler entries with the same className but different `matches` substrings
        // are NOT a duplicate: tuple equality treats absent matches as distinct from any present
        // matches, and two present-matches values discriminate on the substring filter. Both
        // arms are reachable (different incoming messages select different mappings).
        var schema = build("""
            type WithMatches @error(handlers: [
                {handler: GENERIC, className: "java.lang.IllegalArgumentException", matches: "foo"}
            ]) {
                path: [String!]!
                message: String!
            }
            type WithoutMatches @error(handlers: [
                {handler: GENERIC, className: "java.lang.IllegalArgumentException"}
            ]) {
                path: [String!]!
                message: String!
            }
            union SakError = WithMatches | WithoutMatches
            type SakPayload @record(record: {className: "%s"}) {
                data: String
                errors: [SakError]
            }
            type Query { sak: SakPayload %s }
            """.formatted(SAK_PAYLOAD_FQN, SERVICE_DECL));

        var f = (QueryField.QueryServiceRecordField) schema.field("Query", "sak");
        assertThat(f.errorChannel()).isPresent();
        assertThat(f.errorChannel().get().mappedErrorTypes())
            .extracting(et -> et.name())
            .containsExactly("WithMatches", "WithoutMatches");
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

    @Test
    void payloadWithMultipleConstructors_canonicalCtorIsSelectedByArity() {
        // MultiCtorSakPayload declares two constructors: a no-arg one (defaulting fields) and the
        // all-fields (String, List<?>) constructor that mirrors SakPayload's shape. The carrier
        // classifier must pick the all-fields constructor by parameter count matching the SDL
        // field count (2) rather than rejecting on the presence of multiple constructors.
        String multiCtorPayloadFqn =
            "no.sikt.graphitron.codereferences.dummyreferences.MultiCtorSakPayload";
        String multiCtorServiceDecl =
            "@service(service: {className: \"no.sikt.graphitron.rewrite.TestServiceStub\","
                + " method: \"runMultiCtorSak\"})";
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
            type SakPayload @record(record: {className: "%s"}) {
                data: String
                errors: [SakError]
            }
            type Query { sak: SakPayload %s }
            """.formatted(multiCtorPayloadFqn, multiCtorServiceDecl));

        var f = (QueryField.QueryServiceRecordField) schema.field("Query", "sak");
        assertThat(f.errorChannel()).isPresent();
        var ch = (no.sikt.graphitron.rewrite.model.ErrorChannel.PayloadClass) f.errorChannel().get();
        assertThat(ch.errorsSlot())
            .isEqualTo(new no.sikt.graphitron.rewrite.model.ErrorsSlot.CtorParameterIndex(1));
        assertThat(ch.defaultedSlots()).hasSize(1);
        assertThat(ch.defaultedSlots().get(0).name()).isEqualTo("data");
    }

    @Test
    void extraField_missingAccessorOnGenericSourceClass_rejectsCarrier() {
        // An @error type with a field beyond path/message classifies cleanly (rule 6 relaxed),
        // but the carrier's per-(channel, @error type, handler) accessor check rejects when the
        // handler's source class can't populate the field. IllegalArgumentException has no
        // getSeverity() / severity() / public field, so the carrier surfaces UnclassifiedField.
        var schema = build("""
            enum Severity { LOW HIGH }
            type RichErr @error(handlers: [{handler: GENERIC, className: "java.lang.IllegalArgumentException"}]) {
                path: [String!]!
                message: String!
                severity: Severity!
            }
            union SakError = RichErr
            type SakPayload @record(record: {className: "%s"}) {
                data: String
                errors: [SakError]
            }
            type Query { sak: SakPayload %s }
            """.formatted(SAK_PAYLOAD_FQN, SERVICE_DECL));

        var field = schema.field("Query", "sak");
        assertThat(field).isInstanceOf(UnclassifiedField.class);
        var u = (UnclassifiedField) field;
        assertThat(u.reason())
            .contains("RichErr")
            .contains("severity")
            .contains("java.lang.IllegalArgumentException");
    }

    @Test
    void extraField_pathAndMessageStaySynthesized_noAccessorCheckOnThem() {
        // path and message are populated by per-@error-type synthesised DataFetchers, not by the
        // source class's accessors. The accessor check exempts them so an @error type with only
        // path + message classifies cleanly even when the handler's source class lacks getPath().
        // (IllegalArgumentException has neither getPath() nor a path() / public path field.)
        var schema = build("""
            type SimpleErr @error(handlers: [{handler: GENERIC, className: "java.lang.IllegalArgumentException"}]) {
                path: [String!]!
                message: String!
            }
            union SakError = SimpleErr
            type SakPayload @record(record: {className: "%s"}) {
                data: String
                errors: [SakError]
            }
            type Query { sak: SakPayload %s }
            """.formatted(SAK_PAYLOAD_FQN, SERVICE_DECL));

        var f = (QueryField.QueryServiceRecordField) schema.field("Query", "sak");
        assertThat(f.errorChannel()).isPresent();
        assertThat(f.errorChannel().get().mappedErrorTypes())
            .extracting(et -> et.name())
            .containsExactly("SimpleErr");
    }

    // ===== Carrier-walk LocalContext binding (R12 Phase C) =====

    private static final String CARRIER_WALK_ERROR_SDL = """
            type SimpleErr @error(handlers: [{handler: GENERIC, className: "java.lang.RuntimeException"}]) {
                path: [String!]!
                message: String!
            }
            union CarrierError = SimpleErr
            type Film @table(name: "film") { title: String }
            input FilmInput @table(name: "film") { title: String }
            """;

    @Test
    void carrierWalkErrorsField_classifiesAsErrorsFieldWithLocalContextTransport() {
        // R12 Phase C: a plain SDL Object carrier (PojoResultType.NoBacking after promotion)
        // with an errors-shaped field admits at the carrier walk. The errors field classifies
        // as ChildField.ErrorsField carrying Transport.LocalContext (the discriminator FetcherEmitter
        // reads at emit time). MutationBulkDmlRecordField still classifies as today; R161 wires
        // the channel onto the mutation field's Optional<ErrorChannel> slot.
        var schema = TestSchemaHelper.buildSchema(CARRIER_WALK_ERROR_SDL + """
            type FilmPayload { films: [Film!] errors: [CarrierError!] }
            type Query { x: String }
            type Mutation { createFilm(in: [FilmInput!]!): FilmPayload @mutation(typeName: INSERT) }
            """);

        var errorsField = schema.field("FilmPayload", "errors");
        assertThat(errorsField).isInstanceOf(no.sikt.graphitron.rewrite.model.ChildField.ErrorsField.class);
        var ef = (no.sikt.graphitron.rewrite.model.ChildField.ErrorsField) errorsField;
        assertThat(ef.transport())
            .isInstanceOf(no.sikt.graphitron.rewrite.model.ChildField.Transport.LocalContext.class);
        assertThat(ef.errorTypes()).extracting(et -> et.name()).containsExactly("SimpleErr");
    }

    @Test
    void carrierWalkErrorsField_rejectsMultipleValidationHandlers() {
        // Rule 7 (§1): a carrier with two VALIDATION-handler @error types in its errors channel
        // is rejected at the carrier walk with the offending channel named (NoPermit surfaces
        // through the unified walk as Rejected).
        var schema = TestSchemaHelper.buildSchema("""
            type V1 @error(handlers: [{handler: VALIDATION}]) { path: [String!]! message: String! }
            type V2 @error(handlers: [{handler: VALIDATION}]) { path: [String!]! message: String! }
            type Film @table(name: "film") { title: String }
            input FilmInput @table(name: "film") { title: String }
            union DoubleVal = V1 | V2
            type FilmPayload { films: [Film!] errors: [DoubleVal!] }
            type Query { x: String }
            type Mutation { createFilm(in: [FilmInput!]!): FilmPayload @mutation(typeName: INSERT) }
            """);

        var mutField = schema.field("Mutation", "createFilm");
        assertThat(mutField).isInstanceOf(UnclassifiedField.class);
        var reason = ((UnclassifiedField) mutField).rejection().message();
        assertThat(reason).contains("errors-shaped carrier field 'errors'", "more than one", "VALIDATION");
    }

    private GraphitronSchema build(String schemaText) {
        return TestSchemaHelper.buildSchema(schemaText);
    }
}
