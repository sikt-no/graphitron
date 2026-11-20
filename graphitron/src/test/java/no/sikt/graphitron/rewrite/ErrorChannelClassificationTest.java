package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.rewrite.model.ChildField;
import no.sikt.graphitron.rewrite.model.ErrorChannel;
import no.sikt.graphitron.rewrite.model.GraphitronField.UnclassifiedField;
import no.sikt.graphitron.rewrite.model.MutationField;
import no.sikt.graphitron.rewrite.model.QueryField;
import no.sikt.graphitron.rewrite.model.WithErrorChannel;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * Carrier classifier: every fetcher-emitting field variant that implements
 * {@link WithErrorChannel} carries an {@link ErrorChannel} when the payload type's field set
 * declares an {@code errors}-shaped field: a nullable list of a union or an interface whose every
 * member is {@code @error}, or of one {@code @error} type itself. The channel rides the {@code Outcome} wrapper; no payload class is
 * constructed. The channel rules are exercised through a payload-returning DML {@code @mutation}
 * ({@code DML_SAK}); the cases whose subject is the {@code @service} outcome path itself (the
 * handler accessor check, child producers, a backing class's own errors member) use
 * {@code SakPayload} (in {@code dummyreferences}) as the payload a service returns.
 */
@UnitTier
class ErrorChannelClassificationTest {

    private static final String SERVICE_DECL =
        "@service(service: {className: \"no.sikt.graphitron.rewrite.TestServiceStub\", method: \"runSak\"})";

    /**
     * The producer the channel rules are exercised through: a single-row DML UPDATE returning
     * {@code SakPayload}, whose one data field is a {@code Film}. Each test declares its own
     * {@code SakPayload} and {@code @error} types ahead of it.
     */
    private static final String DML_SAK = """
            type Film @table(name: "film") { title: String }
            input FilmInput { filmId: Int! @field(name: "film_id") title: String @field(name: "title") }
            type Query { x: String }
            type Mutation { sak(in: FilmInput!): SakPayload @mutation(typeName: UPDATE, table: "film") }
            """;

    private static final String UNION_ERROR_DML_PAYLOAD_SDL = """
            type ValidationErr @error(handlers: [{handler: VALIDATION}]) {
                path: [String!]!
                message: String!
            }
            type DbErr @error(handlers: [{handler: DATABASE, sqlState: "23503"}]) {
                path: [String!]!
                message: String!
            }
            union SakError = ValidationErr | DbErr
            type SakPayload {
                film: Film
                errors: [SakError]
            }
            """;

    @Test
    void dmlPayloadHasErrorsField_populatesErrorChannel() {
        var schema = build(UNION_ERROR_DML_PAYLOAD_SDL + DML_SAK);

        var channel = channelOf(schema);
        // The channel rides the Outcome wrapper as ErrorChannel.Mapped; no developer payload
        // class is constructed on the error path.
        assertThat(channel).get().isInstanceOf(ErrorChannel.Mapped.class);
        assertThat(channel.get().mappedErrorTypes())
            .extracting(et -> et.name())
            .containsExactly("ValidationErr", "DbErr");
    }

    @Test
    void payloadWithoutErrorsField_producesNoChannel() {
        var schema = build("""
            type SakPayload {
                film: Film
            }
            """ + DML_SAK);

        assertThat(channelOf(schema)).isEmpty();
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
            type SakPayload {
                film: Film
                errors: [SakError]
            }
            """ + DML_SAK);

        var field = schema.field("Mutation", "sak");
        assertThat(field).isInstanceOf(UnclassifiedField.class);
        var u = (UnclassifiedField) field;
        assertThat(u.reason())
            .contains("more than one {handler: VALIDATION}")
            .contains("ValidationA")
            .contains("ValidationB");
    }

    @Test
    void nonNullableSuccessProjectionField_rejectsCarrier() {
        // A success-projection (data) field declared non-null with no empty value (a single
        // value here) would resolve null on the ErrorList
        // arm, which raises NonNullableFieldWasNullError and bubbles the null up to the outcome
        // field, dropping the sibling errors field (the silent errors-drop the wrapper transport
        // exists to prevent). The classifier rejects such an outcome type at classify time, before
        // the wrapper is ever emitted. Surfaces as UnclassifiedField on the carrier (the
        // detectStructuralDmlErrorChannel rule violation), not on the payload type.
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
            type SakPayload {
                film: Film!
                errors: [SakError]
            }
            """ + DML_SAK);

        var field = schema.field("Mutation", "sak");
        assertThat(field).isInstanceOf(UnclassifiedField.class);
        var u = (UnclassifiedField) field;
        assertThat(u.reason())
            .contains("non-null success-projection field 'film'")
            .contains("must be nullable");
    }

    private static final String SAK_ERRORS_SDL = """
            type ValidationErr @error(handlers: [{handler: VALIDATION}]) {
                path: [String!]!
                message: String!
            }
            union SakError = ValidationErr
            """;

    @Test
    void nonNullListSuccessProjectionField_isAccepted() {
        // A non-null plain list answers [] on the ErrorList arm, so it has a value to give there and
        // the carrier classifies; the list's element nullability does not matter. A list data field
        // pairs with a bulk input, so the producer is a bulk DML INSERT.
        for (var dataType : java.util.List.of("[Film!]!", "[Film]!")) {
            var schema = build(SAK_ERRORS_SDL + """
                type Film @table(name: "film") { title: String }
                input FilmInput { title: String }
                type SakPayload {
                    films: %s
                    errors: [SakError]
                }
                type Query { x: String }
                type Mutation { sak(in: [FilmInput!]!): SakPayload @mutation(typeName: INSERT, table: "film") }
                """.formatted(dataType));

            assertThat(schema.field("Mutation", "sak"))
                .as("films: %s", dataType)
                .isInstanceOf(MutationField.MutationBulkDmlRecordField.class);
        }
    }

    @Test
    void validationCoexistsWithBroadExceptionHandler_isAccepted() {
        // VALIDATION runs as a wrapper pre-execution step and never reaches the dispatcher,
        // so a coexisting broad ExceptionHandler is not a shadowing risk. Each runtime arm has
        // its own source path: validation violations come back from Validator.validate ahead
        // of the body call; any post-body throw flows through the dispatch arm matched by the
        // GENERIC handler.
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
            type SakPayload {
                film: Film
                errors: [SakError]
            }
            """ + DML_SAK);

        var channel = channelOf(schema);
        assertThat(channel).isPresent();
        assertThat(channel.get().mappedErrorTypes())
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
            type SakPayload {
                film: Film
                errors: [SakError]
            }
            """ + DML_SAK);

        var channel = channelOf(schema);
        assertThat(channel).isPresent();
        assertThat(channel.get().mappedErrorTypes())
            .extracting(et -> et.name())
            .containsExactly("ValidationErr", "ArgErr");
    }

    @Test
    void rule8_duplicateExceptionHandlersAcrossTypes_rejectsCarrier() {
        // Identical (variant, criteria) ExceptionHandler tuples across two @error types in the
        // same channel; the second is unreachable at dispatch.
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
            type SakPayload {
                film: Film
                errors: [SakError]
            }
            """ + DML_SAK);

        var field = schema.field("Mutation", "sak");
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
            type SakPayload {
                film: Film
                errors: [SakError]
            }
            """ + DML_SAK);

        var field = schema.field("Mutation", "sak");
        assertThat(field).isInstanceOf(UnclassifiedField.class);
        var u = (UnclassifiedField) field;
        assertThat(u.reason())
            .contains("identical match-criteria")
            .contains("DupHandlers")
            .contains("java.lang.RuntimeException");
    }

    @Test
    void rule8_duplicateSqlStateDatabaseHandlers_rejectsCarrier() {
        // Identical DatabaseHandler(SqlState) tuples across two @error types; the second is unreachable.
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
            type SakPayload {
                film: Film
                errors: [SakError]
            }
            """ + DML_SAK);

        var field = schema.field("Mutation", "sak");
        assertThat(field).isInstanceOf(UnclassifiedField.class);
        var u = (UnclassifiedField) field;
        assertThat(u.reason())
            .contains("identical match-criteria")
            .contains("FkA")
            .contains("FkB")
            .contains("23503");
    }

    @Test
    void rule8_duplicateVendorCodeDatabaseHandlers_rejectsCarrier() {
        // Identical DatabaseHandler(VendorCode) tuples across two @error types; the second is unreachable.
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
            type SakPayload {
                film: Film
                errors: [SakError]
            }
            """ + DML_SAK);

        var field = schema.field("Mutation", "sak");
        assertThat(field).isInstanceOf(UnclassifiedField.class);
        var u = (UnclassifiedField) field;
        assertThat(u.reason())
            .contains("identical match-criteria")
            .contains("OraA")
            .contains("OraB");
    }

    @Test
    void rule8_crossVariantOverlapIsAccepted() {
        // ExceptionHandler(SQLException) and DatabaseHandler(SqlState "23503") are different
        // variants. They may both match the same SQLException; §3 source-order picks the first.
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
            type SakPayload {
                film: Film
                errors: [SakError]
            }
            """ + DML_SAK);

        var channel = channelOf(schema);
        assertThat(channel).isPresent();
        assertThat(channel.get().mappedErrorTypes())
            .extracting(et -> et.name())
            .containsExactly("FkErr", "DbErr");
    }

    @Test
    void rule8_duplicateDatabaseMatchesWithoutDiscriminator_rejectsCarrier() {
        // Two {DATABASE, matches: X} entries key on (Any, X): the second is unreachable, as it
        // was when both lifted to one ExceptionHandler(SQLException).
        var schema = build("""
            type ColA @error(handlers: [{handler: DATABASE, matches: "BANKKONTONR"}]) {
                path: [String!]!
                message: String!
            }
            type ColB @error(handlers: [{handler: DATABASE, matches: "BANKKONTONR"}]) {
                path: [String!]!
                message: String!
            }
            union SakError = ColA | ColB
            type SakPayload {
                film: Film
                errors: [SakError]
            }
            """ + DML_SAK);

        var field = schema.field("Mutation", "sak");
        assertThat(field).isInstanceOf(UnclassifiedField.class);
        assertThat(((UnclassifiedField) field).reason())
            .contains("identical match-criteria")
            .contains("ColA")
            .contains("ColB")
            .contains("BANKKONTONR");
    }

    @Test
    void rule8_genericSqlExceptionAndDatabaseWithSameMatches_bothAdmitted() {
        // {GENERIC, className: SQLException, matches: X} tests X against the SQLException's own
        // message; {DATABASE, matches: X} also tests the wrappers above it. Different variants
        // reaching different exceptions, so the overlap is admitted.
        var schema = build("""
            type Generic @error(handlers: [{handler: GENERIC, className: "java.sql.SQLException", matches: "BANKKONTONR"}]) {
                path: [String!]!
                message: String!
            }
            type Database @error(handlers: [{handler: DATABASE, matches: "BANKKONTONR"}]) {
                path: [String!]!
                message: String!
            }
            union SakError = Generic | Database
            type SakPayload {
                film: Film
                errors: [SakError]
            }
            """ + DML_SAK);

        var channel = channelOf(schema);
        assertThat(channel).isPresent();
        assertThat(channel.get().mappedErrorTypes())
            .extracting(et -> et.name())
            .containsExactly("Generic", "Database");
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
            type SakPayload {
                film: Film
                errors: [SakError]
            }
            """ + DML_SAK);

        var channel = channelOf(schema);
        assertThat(channel).isPresent();
        assertThat(channel.get().mappedErrorTypes())
            .extracting(et -> et.name())
            .containsExactly("WithMatches", "WithoutMatches");
    }

    @Test
    void mutationDmlField_tableReturn_carriesEmptyChannel() {
        // DML mutations return @table or ID. @table-returning fetchers build no ErrorChannel:
        // the carrier helper is gated on ResultReturnType, and a jOOQ Record return has no
        // payload-factory shape to construct into. Verifies the WithErrorChannel slot is wired
        // and the INSERT variant produces an empty channel rather than null.
        var schema = build("""
            type Film @table(name: "film") { title: String }
            input FilmInput { title: String }
            type Query { x: String }
            type Mutation { createFilm(in: FilmInput!): Film @mutation(typeName: INSERT) }
            """);

        var f = (MutationField.DmlTableField) schema.field("Mutation", "createFilm");
        assertThat(f.errorChannel()).isEqualTo(Optional.<ErrorChannel>empty());
    }

    @Test
    void extraField_fieldDirectiveRemapsAccessor_classifiesWithOverride() {
        // @field(name:) on an extra field names the source-class accessor when it diverges
        // from the SDL field name. `detail` has no getDetail() on RuntimeException, but
        // @field(name: "localizedMessage") remaps to Throwable.getLocalizedMessage(), and the
        // classified ErrorType the channel carries holds the override.
        var schema = build("""
            type RemapErr @error(handlers: [{handler: GENERIC, className: "java.lang.RuntimeException"}]) {
                path: [String!]!
                message: String!
                detail: String @field(name: "localizedMessage")
            }
            union SakError = RemapErr
            type SakPayload {
                film: Film
                errors: [SakError]
            }
            """ + DML_SAK);

        var channel = channelOf(schema);
        assertThat(channel).isPresent();
        var errorType = channel.get().mappedErrorTypes().stream()
            .filter(et -> et.name().equals("RemapErr")).findFirst().orElseThrow();
        assertThat(errorType.accessorOverrides())
            .extracting(o -> o.sdlFieldName(), o -> o.accessorBase())
            .containsExactly(tuple("detail", "localizedMessage"));
        assertThat(errorType.accessorBaseFor("detail")).isEqualTo("localizedMessage");
    }

    // ===== Carrier-walk error channel binding =====

    private static final String CARRIER_WALK_ERROR_SDL = """
            type SimpleErr @error(handlers: [{handler: GENERIC, className: "java.lang.RuntimeException"}]) {
                path: [String!]!
                message: String!
            }
            union CarrierError = SimpleErr
            type Film @table(name: "film") { title: String }
            input FilmInput { title: String }
            """;

    @Test
    void payloadErrorsField_classifiesAsErrorsFieldWithWrapperArmTransport() {
        // A plain SDL Object carrier (bound to a JooqTableRecordType after promotion) with an
        // errors-shaped field admits at the structural DML-payload scan. The errors field
        // classifies as ChildField.ErrorsField, which FetcherEmitter reads at emit time. The
        // mutation classifies as
        // MutationBulkDmlRecordField and FieldBuilder.detectStructuralDmlErrorChannel wires
        // the channel onto its Optional<ErrorChannel> slot.
        var schema = TestSchemaHelper.buildSchema(CARRIER_WALK_ERROR_SDL + """
            type FilmPayload { films: [Film!] errors: [CarrierError!] }
            type Query { x: String }
            type Mutation { createFilm(in: [FilmInput!]!): FilmPayload @mutation(typeName: INSERT, table: "film") }
            """);

        var errorsField = schema.field("FilmPayload", "errors");
        assertThat(errorsField).isInstanceOf(no.sikt.graphitron.rewrite.model.ChildField.ErrorsField.class);
        var ef = (no.sikt.graphitron.rewrite.model.ChildField.ErrorsField) errorsField;
        assertThat(ef).isInstanceOf(no.sikt.graphitron.rewrite.model.ChildField.ErrorsField.class);
        assertThat(ef.errorTypes()).extracting(et -> et.name()).containsExactly("SimpleErr");
    }

    @Test
    void payloadErrorsField_rejectsMultipleValidationHandlers() {
        // Rule 7 (§1): a carrier with two VALIDATION-handler @error types in its errors channel
        // is rejected at the structural DML-payload scan with the offending channel named.
        var schema = TestSchemaHelper.buildSchema("""
            type V1 @error(handlers: [{handler: VALIDATION}]) { path: [String!]! message: String! }
            type V2 @error(handlers: [{handler: VALIDATION}]) { path: [String!]! message: String! }
            type Film @table(name: "film") { title: String }
            input FilmInput { title: String }
            union DoubleVal = V1 | V2
            type FilmPayload { films: [Film!] errors: [DoubleVal!] }
            type Query { x: String }
            type Mutation { createFilm(in: [FilmInput!]!): FilmPayload @mutation(typeName: INSERT, table: "film") }
            """);

        var mutField = schema.field("Mutation", "createFilm");
        assertThat(mutField).isInstanceOf(UnclassifiedField.class);
        var reason = ((UnclassifiedField) mutField).rejection().message();
        assertThat(reason).contains("errors-shaped carrier field 'errors'", "more than one", "VALIDATION");
    }

    // ===== The single @error type as the errors field's element =====

    private static final String DB_ERR_SDL = """
            type DbErr @error(handlers: [{handler: DATABASE, sqlState: "23503"}]) {
                path: [String!]!
                message: String!
            }
            """;

    @Test
    void singleErrorTypeElement_backingClassWithoutErrorsMember_bindsAndCarries() {
        // The backing class declares the data field only. The binding pass runs before the error
        // index exists and reads the SDL alone, so it must not take the @error-typed list for the
        // payload's one data field: the payload binds to DataOnlyPayload and the channel forms.
        var schema = build(DB_ERR_SDL + """
            type ReviewPayload {
                data: String
                errors: [DbErr]
            }
            type Query {
                review: ReviewPayload @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "runDataOnly"})
            }
            """);

        var f = (QueryField.QueryServiceRecordField) schema.field("Query", "review");
        assertThat(f.errorChannel()).get().isInstanceOf(ErrorChannel.Mapped.class);
        assertThat(schema.field("ReviewPayload", "errors")).isInstanceOf(ChildField.ErrorsField.class);
        assertThat(schema.field("ReviewPayload", "data")).isNotInstanceOf(UnclassifiedField.class);
    }

    private static Optional<? extends ErrorChannel> channelOf(GraphitronSchema schema) {
        return ((WithErrorChannel) schema.field("Mutation", "sak")).errorChannel();
    }

    private GraphitronSchema build(String schemaText) {
        return TestSchemaHelper.buildSchema(schemaText);
    }
}
