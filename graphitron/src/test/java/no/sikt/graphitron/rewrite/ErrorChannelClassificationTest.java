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
import static org.assertj.core.api.Assertions.tuple;

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
        // @service outcome fields classify to ErrorChannel.Mapped (the Outcome wrapper
        // transport), not PayloadClass; no developer payload class is constructed on the error path.
        var ch = (no.sikt.graphitron.rewrite.model.ErrorChannel.Mapped) f.errorChannel().get();
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

    // PayloadConstructor_recordsErrorsSlotAndDefaultLiterals deleted. The ctor errors-slot /
    // defaulted-slot resolution it pinned is PayloadClass-construction internals, which @service
    // fields no longer use (they classify to ErrorChannel.Mapped). The construction-shape machinery
    // is exercised directly by PayloadConstructionShapeTest and retires in slice-1 commit 4.

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

    // UnTypedRecordPayload_producesChannelFromReflectedProducer and
    // payloadHasErrorsFieldButPayloadClassMissing_rejectsCarrier deleted. Both pinned behaviour of
    // the @record directive's className (bare @record still grounds via the producer; a missing
    // @record className rejects). @record is deprecated and ignored — it never drives binding — so
    // there is no @record-className behaviour left to test: SakPayload grounds via its @service
    // producer's reflected return, which the surviving errors-channel cases above already exercise.

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
        // A success-projection (data) field declared non-null resolves null on the ErrorList
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

    // PayloadWithMultipleConstructors_canonicalCtorIsSelectedByArity deleted. Canonical-ctor
    // selection by arity is PayloadClass-construction internals that @service fields no longer reach
    // (they classify to ErrorChannel.Mapped). Covered directly by PayloadConstructionShapeTest;
    // retires in slice-1 commit 4.

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
    void payloadErrorsField_classifiesAsErrorsFieldWithLocalContextTransport() {
        // A plain SDL Object carrier (bound to a JooqTableRecordType after promotion) with an
        // errors-shaped field admits at the structural DML-payload scan. The errors field
        // classifies as ChildField.ErrorsField carrying Transport.LocalContext (the
        // discriminator FetcherEmitter reads at emit time). The mutation classifies as
        // MutationBulkDmlRecordField and FieldBuilder.detectStructuralDmlErrorChannel wires
        // the channel onto its Optional<ErrorChannel> slot.
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
    void payloadErrorsField_rejectsMultipleValidationHandlers() {
        // Rule 7 (§1): a carrier with two VALIDATION-handler @error types in its errors channel
        // is rejected at the structural DML-payload scan with the offending channel named.
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

    // The two @service-path in-hand bestGuess swaps (resolveErrorChannel and
    // computeMutationServiceRecordReturnType) are pinned end-to-end by a nested-payload compilation
    // fixture in graphitron-sakila-example. This unit-tier case additionally pins resolveErrorChannel's
    // boundary directly: @service outcome fields classify to ErrorChannel.Mapped (no payload class is
    // constructed), so the PayloadClass arm resolveErrorChannel builds is reached by a *child* @service
    // field. With a nested payload backing class, the resolved PayloadClass.payloadClass() must be the
    // JLS-legal Outer.Nested, not the $-qualified binary Outer$Nested that bestGuess would carry as a
    // single simple name. This is the belt-and-suspenders object-equality assertion on the resolved
    // TypeName (no code-string match, no database) that guards the swap against reintroduction.
    @Test
    void childServiceRecordField_nestedPayloadBacking_payloadClassIsStructurallyResolved() {
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
        var ch = (ErrorChannel.PayloadClass)
            ((WithErrorChannel) f).errorChannel().orElseThrow();
        // The payload's binary name is AccessorPayloads$NestedErrorsPayload; resolveErrorChannel must
        // resolve the enclosing structure via ClassName.get(Class), matching the reflected class.
        assertThat(ch.payloadClass())
            .isEqualTo(ClassName.get(
                no.sikt.graphitron.codereferences.dummyreferences.AccessorPayloads.NestedErrorsPayload.class));
        // Pin the exact defect: a bestGuess over the binary name would keep the whole
        // "AccessorPayloads$NestedErrorsPayload" as one simple name (it never splits on '$').
        assertThat(ch.payloadClass().simpleName()).isEqualTo("NestedErrorsPayload");
    }

    @Test
    void extraField_legacyPayloadClassPath_fieldDirectiveRemapsAccessor() {
        // The class-backed payload path runs FieldBuilder.checkErrorTypeSourceAccessors, a
        // distinct check site from the @service HandlerAccessorCheck. A child @service field
        // returning a nested backed payload reaches resolveErrorChannel's PayloadClass arm;
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
        var ch = (ErrorChannel.PayloadClass) ((WithErrorChannel) f).errorChannel().orElseThrow();
        var errorType = ch.mappedErrorTypes().stream()
            .filter(et -> et.name().equals("RemapErr")).findFirst().orElseThrow();
        assertThat(errorType.accessorBaseFor("detail")).isEqualTo("localizedMessage");
        assertThat(errorType.accessorOverrides())
            .extracting(o -> o.sdlFieldName(), o -> o.accessorBase())
            .containsExactly(tuple("detail", "localizedMessage"));
    }

    // ===== R201: @field(name:) in @error payload construction shape resolution =====
    //
    // Each fixture backs a child @service field's payload with a dummy class from
    // AccessorPayloads (bean / record) or a JDK type (name-less POJO), reaching
    // FieldBuilder.resolveErrorChannel's PayloadClass arm. The construction-shape resolution
    // (ctor vs. bean) is what R201 teaches to honor @field(name:); the read side already did.

    private static final String R201_SIMPLE_ERR = """
            type SimpleErr @error(handlers: [{handler: GENERIC, className: "java.lang.RuntimeException"}]) {
                path: [String!]!
                message: String!
            }
            union SakError = SimpleErr
            """;

    /** A child @service Film field returning {@code payloadType}, produced by {@code stubMethod}. */
    private static String r201Schema(String payloadTypeSdl, String stubMethod) {
        return R201_SIMPLE_ERR + payloadTypeSdl + """
            type Film @table(name: "film") {
                title: String
                sak: %s @service(service: {
                    className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "%s"})
            }
            type Query { films: [Film!] }
            """.formatted(payloadTypeName(payloadTypeSdl), stubMethod);
    }

    /** First SDL object type name in {@code payloadTypeSdl} (the payload type declaration). */
    private static String payloadTypeName(String payloadTypeSdl) {
        var m = java.util.regex.Pattern.compile("type\\s+(\\w+)").matcher(payloadTypeSdl);
        if (!m.find()) throw new IllegalArgumentException("no payload type in: " + payloadTypeSdl);
        return m.group(1);
    }

    @Test
    void beanArm_remappedSetters_classifiesWithErrorsSetterBoundToRemappedName() {
        // The payload class exposes setInfo / setFailures; @field(name:) on the SDL data/errors
        // fields binds them. The errors-slot setter is the remapped setFailures.
        var schema = build(r201Schema("""
            type BeanRemapPayload {
                data: String @field(name: "info")
                errors: [SakError] @field(name: "failures")
            }
            """, "runDivergentBeanErrors"));

        var f = schema.field("Film", "sak");
        assertThat(f).isInstanceOf(no.sikt.graphitron.rewrite.model.ChildField.ServiceRecordField.class);
        var ch = (ErrorChannel.PayloadClass) ((WithErrorChannel) f).errorChannel().orElseThrow();
        assertThat(ch.errorsSlot())
            .isInstanceOfSatisfying(no.sikt.graphitron.rewrite.model.ErrorsSlot.SetterMethod.class,
                sm -> assertThat(sm.boundSetter().getName()).isEqualTo("setFailures"));
    }

    @Test
    void beanArm_dataFieldDirectiveParticipatesInExistence_rejectsWhenSetterMissing() {
        // Contract rule 1's behavior change pinned as an invariant: the payload has setData /
        // setErrors, but @field(name: "info") on the data field remaps the lookup to setInfo,
        // which does not exist. The reject names the SDL field, the directive value, and the
        // parenthetical.
        var schema = build(r201Schema("""
            type BeanExistencePayload {
                data: String @field(name: "info")
                errors: [SakError]
            }
            """, "runSetterErrors"));

        var f = schema.field("Film", "sak");
        assertThat(f).isInstanceOf(UnclassifiedField.class);
        assertThat(((UnclassifiedField) f).reason())
            .contains("setInfo")
            .contains("'data'")
            .contains("(remapped to 'info' by @field)");
    }

    @Test
    void ctorArm_reorderedRecordWithFieldOnErrors_classifiesAtNameResolvedIndex() {
        // The record declares (problems, data); the SDL declares data then errors. @field(name:
        // "problems") on the errors field name-matches ctor parameter 0, so the errors slot is
        // index 0 (not the SDL index 1) and the defaulted data slot is computed against it.
        var schema = build(r201Schema("""
            type CtorRemapPayload {
                data: String
                errors: [SakError] @field(name: "problems")
            }
            """, "runReorderedErrors"));

        var f = schema.field("Film", "sak");
        assertThat(f).isInstanceOf(no.sikt.graphitron.rewrite.model.ChildField.ServiceRecordField.class);
        var ch = (ErrorChannel.PayloadClass) ((WithErrorChannel) f).errorChannel().orElseThrow();
        assertThat(ch.errorsSlot())
            .isInstanceOfSatisfying(
                no.sikt.graphitron.rewrite.model.ErrorsSlot.CtorParameterIndex.class,
                cpi -> assertThat(cpi.index()).isEqualTo(0));
        // The single non-errors slot (data) is defaulted, and it is the SDL/ctor index 1, i.e.
        // the resolved errors index (0) was excluded, not the stale SDL index.
        assertThat(ch.defaultedSlots()).extracting(
                no.sikt.graphitron.rewrite.model.DefaultedSlot::index)
            .containsExactly(1);
    }

    @Test
    void ctorArm_unresolvableFieldValue_rejectsNamingDirectiveValueAndCandidates() {
        // NestedErrorsPayload(data, errors); @field(name: "nonexistent") on the errors field
        // matches no ctor parameter. The reject names the directive value and the candidates.
        var schema = build(r201Schema("""
            type CtorUnresolvedPayload {
                data: String
                errors: [SakError] @field(name: "nonexistent")
            }
            """, "runNestedErrors"));

        var f = schema.field("Film", "sak");
        assertThat(f).isInstanceOf(UnclassifiedField.class);
        assertThat(((UnclassifiedField) f).reason())
            .contains("nonexistent")
            .contains("data")
            .contains("errors");
    }

    @Test
    void ctorArm_directiveOnNameLessPojo_rejectsWithParametersGuidance() {
        // NamelessErrorsPayload lives in the codereferences.noparams package, compiled without
        // -parameters, so its ctor exposes no parameter names and it is not a record. The
        // @field(name:) value here COINCIDES with the SDL field name ("errors"), so a
        // presence-by-value-divergence shortcut would wrongly admit it via the positional path.
        // Presence-tracking rejects it with the -parameters guidance. Its service stub also lives
        // in that package, named here only by class-name string.
        var schema = build(R201_SIMPLE_ERR + """
            type NamelessPayload {
                data: String
                errors: [SakError] @field(name: "errors")
            }
            type Film @table(name: "film") {
                title: String
                sak: NamelessPayload @service(service: {
                    className: "no.sikt.graphitron.codereferences.noparams.NoParamsServiceStub",
                    method: "runNameless"})
            }
            type Query { films: [Film!] }
            """);

        var f = schema.field("Film", "sak");
        assertThat(f).isInstanceOf(UnclassifiedField.class);
        assertThat(((UnclassifiedField) f).reason())
            .contains("-parameters")
            .contains("errors");
    }

    @Test
    void anyArm_blankFieldValue_rejectsChannel() {
        // A present-but-blank @field(name: "") on any payload field rejects the channel
        // (contract rule 3, R200/R202 precedent).
        var schema = build(r201Schema("""
            type BlankPayload {
                data: String
                errors: [SakError] @field(name: "")
            }
            """, "runNestedErrors"));

        var f = schema.field("Film", "sak");
        assertThat(f).isInstanceOf(UnclassifiedField.class);
        assertThat(((UnclassifiedField) f).reason())
            .contains("blank")
            .contains("errors");
    }

    @Test
    void regressionFloor_divergentNamesWithoutDirective_rejectsAsToday() {
        // DivergentBeanErrorsPayload exposes setInfo / setFailures. Without a @field directive the
        // bean arm still looks up setData / setErrors and rejects, exactly as before R201.
        var schema = build(r201Schema("""
            type RegressionPayload {
                data: String
                errors: [SakError]
            }
            """, "runDivergentBeanErrors"));

        var f = schema.field("Film", "sak");
        assertThat(f).isInstanceOf(UnclassifiedField.class);
        assertThat(((UnclassifiedField) f).reason()).contains("setData");
    }

    private GraphitronSchema build(String schemaText) {
        return TestSchemaHelper.buildSchema(schemaText);
    }
}
