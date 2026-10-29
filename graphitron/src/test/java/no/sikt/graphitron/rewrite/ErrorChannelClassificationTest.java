package no.sikt.graphitron.rewrite;

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
 * member is {@code @error}. The channel rides the {@code Outcome} wrapper; no payload class is
 * constructed. The fixtures use {@code SakPayload} (in {@code dummyreferences}) as the payload a
 * service returns.
 */
@UnitTier
class ErrorChannelClassificationTest {

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
            type SakPayload {
                data: String
                errors: [SakError]
            }
            """;

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
        // @service outcome fields carry ErrorChannel.Mapped (the Outcome wrapper transport) by
        // the record component's own type; no developer payload class is constructed on the
        // error path.
        var ch = f.errorChannel().get();
        assertThat(ch.mappedErrorTypes())
            .extracting(et -> et.name())
            .containsExactly("ValidationErr", "DbErr");
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

    private static final String INPUT_GRID_SDL = """
            enum TestInputBeanEnum { LOW HIGH }
            input TestInputNested { key: String, value: String }
            input TestInputBean { title: String, rating: TestInputBeanEnum, nested: [TestInputNested!] }
            type Query { x: String }
            type Mutation {
                runWithInputGrid(grid: [[TestInputBean!]!]!): SakPayload
                    @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "runSakWithInputGrid"})
            }
            """;

    @Test
    void serviceField_listOfListsOfInputObjects_underValidationChannel_classifiesWithNothingToValidate() {
        // A list of lists of input objects can only bind as a Map pass-through, which is not a class
        // the consumer wrote, so the VALIDATION handler's pre-step has no target here and the field
        // classifies like any other.
        var schema = build(UNION_ERROR_PAYLOAD_SDL + INPUT_GRID_SDL);

        var f = (MutationField.MutationServiceRecordField) schema.field("Mutation", "runWithInputGrid");
        assertThat(f.errorChannel().orElseThrow().hasValidationHandler()).isTrue();
        assertThat(f.serviceMethodCall().validationTargets()).isEmpty();
    }

    @Test
    void serviceField_listOfListsOfInputObjects_withoutValidationHandler_classifies() {
        var schema = build("""
            type DbErr @error(handlers: [{handler: DATABASE, sqlState: "23503"}]) {
                path: [String!]!
                message: String!
            }
            union SakError = DbErr
            type SakPayload {
                data: String
                errors: [SakError]
            }
            """ + INPUT_GRID_SDL);

        var f = (MutationField.MutationServiceRecordField) schema.field("Mutation", "runWithInputGrid");
        assertThat(f.errorChannel()).isPresent();
    }

    @Test
    void payloadWithoutErrorsField_producesNoChannel() {
        var schema = build("""
            type Plain {
                data: String
            }
            type Query { plain: Plain %s }
            """.formatted(SERVICE_DECL));

        var f = (QueryField.QueryServiceRecordField) schema.field("Query", "plain");
        assertThat(f.errorChannel()).isEmpty();
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
                data: String
                errors: [SakError]
            }
            type Query { sak: SakPayload %s }
            """.formatted(SERVICE_DECL));

        var field = schema.field("Query", "sak");
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
        // resolveServiceOutcomeChannel Reject arm), not on the payload type.
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
                data: String!
                errors: [SakError]
            }
            type Query { sak: SakPayload %s }
            """.formatted(SERVICE_DECL));

        var field = schema.field("Query", "sak");
        assertThat(field).isInstanceOf(UnclassifiedField.class);
        var u = (UnclassifiedField) field;
        assertThat(u.reason())
            .contains("non-null success-projection field 'data'")
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
        // the carrier classifies; the list's element nullability does not matter.
        for (var dataType : java.util.List.of("[String!]!", "[String]!")) {
            var schema = build(SAK_ERRORS_SDL + """
                type SakPayload {
                    data: %s
                    errors: [SakError]
                }
                type Query { sak: SakPayload %s }
                """.formatted(dataType, SERVICE_DECL));

            assertThat(schema.field("Query", "sak"))
                .as("data: %s", dataType)
                .isInstanceOf(QueryField.QueryServiceRecordField.class);
        }
    }

    @Test
    void nonNullConnectionSuccessProjectionField_rejectsCarrier() {
        // @asConnection is still a list on the pre-rewrite schema the classifier reads; the rule
        // keys on the field's wrapper, which resolves the directive to a connection, and a
        // connection counts as a single value on the error arm.
        var schema = build(SAK_ERRORS_SDL + """
            type Film @table(name: "film") { title: String }
            type SakPayload {
                data: [Film!]! @asConnection
                errors: [SakError]
            }
            type Query { sak: SakPayload %s }
            """.formatted(SERVICE_DECL));

        assertThat(schema.field("Query", "sak"))
            .isInstanceOfSatisfying(UnclassifiedField.class, u -> assertThat(u.rejection())
                .isEqualTo(new no.sikt.graphitron.model.diagnostics.ErrorChannelWalkerError
                    .NonNullableSuccessProjectionField("SakPayload", "data")));
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
                data: String
                errors: [SakError]
            }
            type Query { sak: SakPayload %s }
            """.formatted(SERVICE_DECL));

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
            type SakPayload {
                data: String
                errors: [SakError]
            }
            type Query { sak: SakPayload %s }
            """.formatted(SERVICE_DECL));

        var f = (QueryField.QueryServiceRecordField) schema.field("Query", "sak");
        assertThat(f.errorChannel()).isPresent();
        assertThat(f.errorChannel().get().mappedErrorTypes())
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
                data: String
                errors: [SakError]
            }
            type Query { sak: SakPayload %s }
            """.formatted(SERVICE_DECL));

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
            type SakPayload {
                data: String
                errors: [SakError]
            }
            type Query { sak: SakPayload %s }
            """.formatted(SERVICE_DECL));

        var field = schema.field("Query", "sak");
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
                data: String
                errors: [SakError]
            }
            type Query { sak: SakPayload %s }
            """.formatted(SERVICE_DECL));

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
                data: String
                errors: [SakError]
            }
            type Query { sak: SakPayload %s }
            """.formatted(SERVICE_DECL));

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
                data: String
                errors: [SakError]
            }
            type Query { sak: SakPayload %s }
            """.formatted(SERVICE_DECL));

        var f = (QueryField.QueryServiceRecordField) schema.field("Query", "sak");
        assertThat(f.errorChannel()).isPresent();
        assertThat(f.errorChannel().get().mappedErrorTypes())
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
                data: String
                errors: [SakError]
            }
            type Query { sak: SakPayload %s }
            """.formatted(SERVICE_DECL));

        var field = schema.field("Query", "sak");
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
                data: String
                errors: [SakError]
            }
            type Query { sak: SakPayload %s }
            """.formatted(SERVICE_DECL));

        var f = (QueryField.QueryServiceRecordField) schema.field("Query", "sak");
        assertThat(f.errorChannel()).isPresent();
        assertThat(f.errorChannel().get().mappedErrorTypes())
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
                data: String
                errors: [SakError]
            }
            type Query { sak: SakPayload %s }
            """.formatted(SERVICE_DECL));

        var f = (QueryField.QueryServiceRecordField) schema.field("Query", "sak");
        assertThat(f.errorChannel()).isPresent();
        assertThat(f.errorChannel().get().mappedErrorTypes())
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
    void extraField_missingAccessorOnGenericSourceClass_rejectsCarrier() {
        // An @error type with a field beyond path/message classifies cleanly,
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
            type SakPayload {
                data: String
                errors: [SakError]
            }
            type Query { sak: SakPayload %s }
            """.formatted(SERVICE_DECL));

        var field = schema.field("Query", "sak");
        assertThat(field).isInstanceOf(UnclassifiedField.class);
        var u = (UnclassifiedField) field;
        assertThat(u.reason())
            .contains("RichErr")
            .contains("severity")
            .contains("java.lang.IllegalArgumentException");
    }

    @Test
    void extraField_generatedClientException_checkedAgainstSuperclassOnCleanBuild() {
        // The test codegen loader never carries the generated GraphitronClientException, which
        // is the clean-build state. The accessor check still runs, against the generated class's
        // superclass graphql.GraphqlErrorException, which inherits getLocalizedMessage().
        var schema = build("""
            type ClientErr @error(handlers: [{handler: GENERIC, className: "fake.code.generated.schema.GraphitronClientException"}]) {
                path: [String!]!
                message: String!
                localizedMessage: String
            }
            union SakError = ClientErr
            type SakPayload {
                data: String
                errors: [SakError]
            }
            type Query { sak: SakPayload %s }
            """.formatted(SERVICE_DECL));

        var f = (QueryField.QueryServiceRecordField) schema.field("Query", "sak");
        assertThat(f.errorChannel()).isPresent();
        assertThat(f.errorChannel().get().mappedErrorTypes())
            .extracting(et -> et.name())
            .containsExactly("ClientErr");
    }

    @Test
    void extraField_generatedClientException_missingAccessor_rejectsNamingSuperclass() {
        var schema = build("""
            type ClientErr @error(handlers: [{handler: GENERIC, className: "fake.code.generated.schema.GraphitronClientException"}]) {
                path: [String!]!
                message: String!
                bogus: String
            }
            union SakError = ClientErr
            type SakPayload {
                data: String
                errors: [SakError]
            }
            type Query { sak: SakPayload %s }
            """.formatted(SERVICE_DECL));

        var field = schema.field("Query", "sak");
        assertThat(field).isInstanceOf(UnclassifiedField.class);
        assertThat(((UnclassifiedField) field).reason())
            .contains("ClientErr")
            .contains("bogus")
            .contains("graphql.GraphqlErrorException");
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
            type SakPayload {
                data: String
                errors: [SakError]
            }
            type Query { sak: SakPayload %s }
            """.formatted(SERVICE_DECL));

        var f = (QueryField.QueryServiceRecordField) schema.field("Query", "sak");
        assertThat(f.errorChannel()).isPresent();
        assertThat(f.errorChannel().get().mappedErrorTypes())
            .extracting(et -> et.name())
            .containsExactly("SimpleErr");
    }

    @Test
    void extraField_fieldDirectiveRemapsAccessor_classifiesWithOverride() {
        // @field(name:) on an extra field names the source-class accessor when it diverges
        // from the SDL field name. `detail` has no getDetail() on RuntimeException, but
        // @field(name: "localizedMessage") remaps to Throwable.getLocalizedMessage(), so the
        // carrier classifies cleanly and the classified ErrorType carries the override.
        var schema = build("""
            type RemapErr @error(handlers: [{handler: GENERIC, className: "java.lang.RuntimeException"}]) {
                path: [String!]!
                message: String!
                detail: String @field(name: "localizedMessage")
            }
            union SakError = RemapErr
            type SakPayload {
                data: String
                errors: [SakError]
            }
            type Query { sak: SakPayload %s }
            """.formatted(SERVICE_DECL));

        var f = (QueryField.QueryServiceRecordField) schema.field("Query", "sak");
        assertThat(f.errorChannel()).isPresent();
        var errorType = f.errorChannel().get().mappedErrorTypes().stream()
            .filter(et -> et.name().equals("RemapErr")).findFirst().orElseThrow();
        assertThat(errorType.accessorOverrides())
            .extracting(o -> o.sdlFieldName(), o -> o.accessorBase())
            .containsExactly(tuple("detail", "localizedMessage"));
        assertThat(errorType.accessorBaseFor("detail")).isEqualTo("localizedMessage");
    }

    @Test
    void extraField_fieldDirectiveRemapStillMissing_rejectsCarrierNamingFieldAndDirective() {
        // A remap to an accessor the source class still does not expose rejects, and the
        // diagnostic names both the SDL field and the directive value so the failed remap is
        // diagnosable. RuntimeException has no getNope() / nope() / public nope field.
        var schema = build("""
            type RemapMissErr @error(handlers: [{handler: GENERIC, className: "java.lang.RuntimeException"}]) {
                path: [String!]!
                message: String!
                detail: String @field(name: "nope")
            }
            union SakError = RemapMissErr
            type SakPayload {
                data: String
                errors: [SakError]
            }
            type Query { sak: SakPayload %s }
            """.formatted(SERVICE_DECL));

        var field = schema.field("Query", "sak");
        assertThat(field).isInstanceOf(UnclassifiedField.class);
        var reason = ((UnclassifiedField) field).reason();
        assertThat(reason)
            .contains("RemapMissErr")
            .contains("detail")
            .contains("nope")
            .contains("java.lang.RuntimeException");
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

    // A child @service field catches into its outcome as a root one does: its payload's errors
    // field is an ErrorsField and the field carries an ErrorChannel.Mapped over the union's
    // members, with a nested payload backing class as with any other.
    @Test
    void childServiceRecordField_payloadWithErrorsField_catchesIntoItsOutcome() {
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
            type NestedErrPayload {
                data: String
                errors: [SakError]
            }
            type Film @table(name: "film") {
                title: String
                sak: NestedErrPayload @service(service: {
                    className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "runNestedErrors"})
            }
            type Query { films: [Film!] }
            """);

        var f = schema.field("Film", "sak");
        assertThat(f).isInstanceOf(no.sikt.graphitron.rewrite.model.ChildField.ServiceRecordField.class);
        var ch = ((WithErrorChannel) f).errorChannel().orElseThrow();
        assertThat(ch).isInstanceOf(ErrorChannel.Mapped.class);
        assertThat(ch.mappedErrorTypes()).extracting(et -> et.name())
            .containsExactly("ValidationErr", "DbErr");
        assertThat(schema.field("NestedErrPayload", "errors"))
            .isInstanceOf(no.sikt.graphitron.rewrite.model.ChildField.ErrorsField.class);
    }

    @Test
    void extraField_childServicePayload_fieldDirectiveRemapsAccessor() {
        // A child @service field's channel runs the same HandlerAccessorCheck a root one does;
        // @field(name: "localizedMessage") on an extra field remaps to Throwable.getLocalizedMessage()
        // so the accessor check passes and the classified ErrorType carries the override.
        var schema = build("""
            type RemapErr @error(handlers: [{handler: GENERIC, className: "java.lang.RuntimeException"}]) {
                path: [String!]!
                message: String!
                detail: String @field(name: "localizedMessage")
            }
            union SakError = RemapErr
            type NestedErrPayload {
                data: String
                errors: [SakError]
            }
            type Film @table(name: "film") {
                title: String
                sak: NestedErrPayload @service(service: {
                    className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "runNestedErrors"})
            }
            type Query { films: [Film!] }
            """);

        var f = schema.field("Film", "sak");
        assertThat(f).isInstanceOf(no.sikt.graphitron.rewrite.model.ChildField.ServiceRecordField.class);
        var ch = ((WithErrorChannel) f).errorChannel().orElseThrow();
        var errorType = ch.mappedErrorTypes().stream()
            .filter(et -> et.name().equals("RemapErr")).findFirst().orElseThrow();
        assertThat(errorType.accessorBaseFor("detail")).isEqualTo("localizedMessage");
        assertThat(errorType.accessorOverrides())
            .extracting(o -> o.sdlFieldName(), o -> o.accessorBase())
            .containsExactly(tuple("detail", "localizedMessage"));
    }

    private GraphitronSchema build(String schemaText) {
        return TestSchemaHelper.buildSchema(schemaText);
    }
}
