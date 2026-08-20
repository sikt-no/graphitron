package no.sikt.graphitron.rewrite.generators.schema;

import graphql.schema.FieldCoordinates;
import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.FieldSpec;
import no.sikt.graphitron.javapoet.TypeName;
import no.sikt.graphitron.javapoet.TypeSpec;
import no.sikt.graphitron.rewrite.GraphitronSchema;
import no.sikt.graphitron.rewrite.model.DefaultedSlot;
import no.sikt.graphitron.rewrite.model.ErrorChannel;
import no.sikt.graphitron.rewrite.model.GraphitronField;
import no.sikt.graphitron.rewrite.model.GraphitronType;
import no.sikt.graphitron.rewrite.model.GraphitronType.ErrorType;
import no.sikt.graphitron.rewrite.model.GraphitronType.ErrorType.ClientMessage;
import no.sikt.graphitron.rewrite.model.GraphitronType.ErrorType.ExceptionHandler;
import no.sikt.graphitron.rewrite.model.GraphitronType.ErrorType.SqlStateHandler;
import no.sikt.graphitron.rewrite.model.GraphitronType.ErrorType.ValidationHandler;
import no.sikt.graphitron.rewrite.model.GraphitronType.ErrorType.VendorCodeHandler;
import no.sikt.graphitron.rewrite.model.FieldWrapper;
import no.sikt.graphitron.rewrite.TestFixtures;
import no.sikt.graphitron.rewrite.model.MethodRef;
import no.sikt.graphitron.rewrite.model.QueryField;
import no.sikt.graphitron.rewrite.model.ReturnTypeRef;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pipeline test for {@link ErrorMappingsClassGenerator}: builds a synthesized schema with
 * one or more {@link ErrorChannel}-bearing fields and asserts the emitted {@code ErrorMappings}
 * class has the expected constants. Avoids the SDL-driven pipeline so every test scenario can
 * exercise channel shapes without requiring a Java-class fixture per {@code @error} type.
 */
@UnitTier
class ErrorMappingsClassGeneratorTest {

    private static final String OUTPUT_PACKAGE = "com.example";
    private static final String FILM_PAYLOAD_FQN = "com.example.FilmPayload";
    private static final String CREATE_FILM_PAYLOAD_FQN = "com.example.CreateFilmPayload";
    private static final String FILM_NOT_FOUND_FQN = "com.example.FilmNotFoundException";
    private static final String FILM_FK_FQN = "com.example.FilmFkViolation";
    private static final String FILM_VALIDATION_FQN = "com.example.FilmValidation";

    @Test
    void emits_emptyClass_whenNoChannelsExist() {
        var schema = synthesizeSchema(List.of());
        var spec = ErrorMappingsClassGenerator.generate(schema, OUTPUT_PACKAGE).get(0);
        assertThat(spec.name()).isEqualTo("ErrorMappings");
        assertThat(spec.fieldSpecs()).isEmpty();
    }

    @Test
    void emits_oneConstant_perChannelKeyedByMappingsConstantName() {
        var filmChannel = channel("FilmPayload", FILM_PAYLOAD_FQN, "FILM_PAYLOAD",
            List.of(errorType("FilmNotFoundException", FILM_NOT_FOUND_FQN,
                List.of(new ExceptionHandler("java.lang.RuntimeException",
                    Optional.empty(), new ClientMessage.FromSource())))));
        var schema = synthesizeSchema(List.of(filmChannel));

        var spec = ErrorMappingsClassGenerator.generate(schema, OUTPUT_PACKAGE).get(0);
        assertThat(spec.fieldSpecs()).extracting(FieldSpec::name)
            .containsExactly("FILM_PAYLOAD");
    }

    @Test
    void dedups_twoIdenticalChannelsIntoOneConstant() {
        var handler = new SqlStateHandler("23503", Optional.empty(), new ClientMessage.FromSource());
        var errorTypes = List.of(errorType("FilmFkViolation", FILM_FK_FQN, List.of(handler)));
        var ch1 = channel("FilmPayload", FILM_PAYLOAD_FQN, "FILM_PAYLOAD", errorTypes);
        var ch2 = channel("FilmPayload", FILM_PAYLOAD_FQN, "FILM_PAYLOAD", errorTypes);

        var schema = synthesizeSchema(List.of(ch1, ch2));
        var spec = ErrorMappingsClassGenerator.generate(schema, OUTPUT_PACKAGE).get(0);

        assertThat(spec.fieldSpecs()).extracting(FieldSpec::name).containsExactly("FILM_PAYLOAD");
    }

    @Test
    void dedups_collidingChannelsWithDifferentHandlerLists_intoSuffixedConstants() {
        // Same payload class with different handler lists: the §3 hash-suffix dedup pass
        // (MappingsConstantNameDedup, run during schema build) resolves the collision by
        // keeping the first-seen channel's bare name and assigning suffixed names to subsequent
        // distinct shapes. The emitter sees already-resolved names and just emits.
        var ch1 = channel("FilmPayload", FILM_PAYLOAD_FQN, "FILM_PAYLOAD",
            List.of(errorType("FilmNotFoundException", FILM_NOT_FOUND_FQN,
                List.of(new ExceptionHandler("java.lang.RuntimeException",
                    Optional.empty(), new ClientMessage.FromSource())))));
        var ch2 = channel("FilmPayload", FILM_PAYLOAD_FQN, "FILM_PAYLOAD",
            List.of(errorType("FilmFkViolation", FILM_FK_FQN,
                List.of(new SqlStateHandler("23503", Optional.empty(), new ClientMessage.FromSource())))));

        var schema = synthesizeSchemaWithDedup(List.of(ch1, ch2));
        var spec = ErrorMappingsClassGenerator.generate(schema, OUTPUT_PACKAGE).get(0);

        var names = spec.fieldSpecs().stream().map(FieldSpec::name).toList();
        assertThat(names).hasSize(2);
        assertThat(names).contains("FILM_PAYLOAD");
        assertThat(names.stream().filter(n -> n.startsWith("FILM_PAYLOAD_") && n.length() == "FILM_PAYLOAD_".length() + 8))
            .as("expected one suffixed FILM_PAYLOAD_<8hex> constant alongside the bare name")
            .hasSize(1);
    }

    @Test
    void distinctPayloadClasses_yieldDistinctConstants() {
        var ch1 = channel("FilmPayload", FILM_PAYLOAD_FQN, "FILM_PAYLOAD",
            List.of(errorType("FilmNotFoundException", FILM_NOT_FOUND_FQN,
                List.of(new ExceptionHandler("java.lang.RuntimeException",
                    Optional.empty(), new ClientMessage.FromSource())))));
        var ch2 = channel("CreateFilmPayload", CREATE_FILM_PAYLOAD_FQN, "CREATE_FILM_PAYLOAD",
            List.of(errorType("FilmFkViolation", FILM_FK_FQN,
                List.of(new SqlStateHandler("23503", Optional.empty(), new ClientMessage.FromSource())))));

        var schema = synthesizeSchema(List.of(ch1, ch2));
        var spec = ErrorMappingsClassGenerator.generate(schema, OUTPUT_PACKAGE).get(0);

        assertThat(spec.fieldSpecs()).extracting(FieldSpec::name)
            .containsExactlyInAnyOrder("FILM_PAYLOAD", "CREATE_FILM_PAYLOAD");
    }

    @Test
    void initializer_emitsExceptionMappingForExceptionHandler() {
        var ch = channel("FilmPayload", FILM_PAYLOAD_FQN, "FILM_PAYLOAD",
            List.of(errorType("FilmNotFoundException", FILM_NOT_FOUND_FQN,
                List.of(new ExceptionHandler("java.lang.IllegalArgumentException",
                    Optional.of("not found"), new ClientMessage.Static("Film not found"))))));
        var spec = ErrorMappingsClassGenerator
            .generate(synthesizeSchema(List.of(ch)), OUTPUT_PACKAGE).get(0);

        var init = byTypeEntries(spec, "FILM_NOT_FOUND_EXCEPTION");
        // Source-direct emission: ExceptionMapping(IllegalArgumentException.class, matches, description).
        // No per-mapping factory: the matched throwable goes into the errors list directly.
        assertThat(init).contains("ExceptionMapping");
        assertThat(init).contains("IllegalArgumentException.class");
        assertThat(init).contains("\"not found\"");
        assertThat(init).contains("\"Film not found\"");
        assertThat(init).doesNotContain("FilmNotFoundException::new");
        assertThat(init).doesNotContain("::new");
    }

    @Test
    void initializer_emitsSqlStateMappingForSqlStateHandler() {
        var ch = channel("FilmPayload", FILM_PAYLOAD_FQN, "FILM_PAYLOAD",
            List.of(errorType("FilmFkViolation", FILM_FK_FQN,
                List.of(new SqlStateHandler("23503", Optional.empty(), new ClientMessage.FromSource())))));
        var spec = ErrorMappingsClassGenerator
            .generate(synthesizeSchema(List.of(ch)), OUTPUT_PACKAGE).get(0);

        var init = byTypeEntries(spec, "FILM_FK_VIOLATION");
        assertThat(init).contains("SqlStateMapping");
        assertThat(init).contains("\"23503\"");
        assertThat(init).doesNotContain("::new");
        // An absent matches and a FromSource client message both render as bare null.
        assertThat(init).contains("null");
    }

    @Test
    void initializer_emitsVendorCodeMappingForVendorCodeHandler() {
        var ch = channel("FilmPayload", FILM_PAYLOAD_FQN, "FILM_PAYLOAD",
            List.of(errorType("FilmFkViolation", FILM_FK_FQN,
                List.of(new VendorCodeHandler("1452", Optional.empty(), new ClientMessage.FromSource())))));
        var spec = ErrorMappingsClassGenerator
            .generate(synthesizeSchema(List.of(ch)), OUTPUT_PACKAGE).get(0);

        var init = byTypeEntries(spec, "FILM_FK_VIOLATION");
        assertThat(init).contains("VendorCodeMapping");
        assertThat(init).contains("\"1452\"");
        assertThat(init).doesNotContain("::new");
    }

    @Test
    void initializer_skipsValidationHandler_emittingEmptyMappingArray() {
        // §5: ValidationHandler entries produce no Mapping. The wrapper invokes
        // jakarta.validation.Validator as a pre-execution step and routes the resulting
        // GraphQLErrors directly into the errors slot, bypassing the dispatcher entirely.
        var ch = channel("FilmPayload", FILM_PAYLOAD_FQN, "FILM_PAYLOAD",
            List.of(errorType("FilmValidation", FILM_VALIDATION_FQN,
                List.of(new ValidationHandler()))));
        var spec = ErrorMappingsClassGenerator
            .generate(synthesizeSchema(List.of(ch)), OUTPUT_PACKAGE).get(0);

        var init = byTypeEntries(spec, "FILM_VALIDATION");
        assertThat(init).doesNotContain("ValidationMapping");
        assertThat(init).doesNotContain("ExceptionMapping");
        assertThat(init).doesNotContain("SqlStateMapping");
        assertThat(init).doesNotContain("VendorCodeMapping");
    }

    @Test
    void initializer_skipsValidationHandler_keepingDispatchHandlers() {
        // ValidationHandler interleaved with dispatch handlers: the dispatch handlers still
        // emit Mappings; the ValidationHandler is silently dropped from its type's array, which
        // leaves that type's ByType constant empty rather than absent.
        var ch = channel("FilmPayload", FILM_PAYLOAD_FQN, "FILM_PAYLOAD",
            List.of(
                errorType("FilmValidation", FILM_VALIDATION_FQN,
                    List.of(new ValidationHandler())),
                errorType("FilmFkViolation", FILM_FK_FQN,
                    List.of(new SqlStateHandler("23503", Optional.empty(), new ClientMessage.FromSource())))));
        var spec = ErrorMappingsClassGenerator
            .generate(synthesizeSchema(List.of(ch)), OUTPUT_PACKAGE).get(0);

        assertThat(byTypeEntries(spec, "FILM_VALIDATION")).doesNotContain("Mapping(");
        var fk = byTypeEntries(spec, "FILM_FK_VIOLATION");
        assertThat(fk).doesNotContain("ValidationMapping");
        assertThat(fk).contains("SqlStateMapping");
        assertThat(fk).contains("\"23503\"");
    }

    @Test
    void preservesSourceOrder_fromMappedErrorTypesAndHandlers() {
        // Two @error types, each with its own handlers. Handler order within a type is the
        // declaration order of its own ByType array; type order is the channel constant's
        // concatenation order. Both matter: dispatch is source-order-first-match.
        var et1 = errorType("FilmFkViolation", FILM_FK_FQN, List.of(
            new SqlStateHandler("23503", Optional.empty(), new ClientMessage.FromSource()),
            new SqlStateHandler("23505", Optional.empty(), new ClientMessage.FromSource())));
        var et2 = errorType("FilmNotFoundException", FILM_NOT_FOUND_FQN, List.of(
            new ExceptionHandler("java.lang.RuntimeException",
                Optional.empty(), new ClientMessage.FromSource())));
        var ch = channel("FilmPayload", FILM_PAYLOAD_FQN, "FILM_PAYLOAD", List.of(et1, et2));
        var spec = ErrorMappingsClassGenerator
            .generate(synthesizeSchema(List.of(ch)), OUTPUT_PACKAGE).get(0);

        var fk = byTypeEntries(spec, "FILM_FK_VIOLATION");
        assertThat(fk.indexOf("\"23503\"")).isNotNegative().isLessThan(fk.indexOf("\"23505\""));
        assertThat(byTypeEntries(spec, "FILM_NOT_FOUND_EXCEPTION")).contains("RuntimeException");
        assertThat(channelInitializer(spec, "FILM_PAYLOAD"))
            .isEqualTo("concat(ByType.FILM_FK_VIOLATION, ByType.FILM_NOT_FOUND_EXCEPTION)");
    }

    @Test
    void channelConstant_concatenatesItsMappedTypesInDeclarationOrder() {
        // The use-keyed grain derives wholly from the definition-keyed one: a channel constant
        // is the ordered concatenation of the ByType arrays of the types it maps, nothing else.
        var et1 = errorType("FilmFkViolation", FILM_FK_FQN,
            List.of(new SqlStateHandler("23503", Optional.empty(), new ClientMessage.FromSource())));
        var et2 = errorType("FilmNotFoundException", FILM_NOT_FOUND_FQN,
            List.of(new ExceptionHandler("java.lang.RuntimeException",
                Optional.empty(), new ClientMessage.FromSource())));
        var spec = ErrorMappingsClassGenerator.generate(synthesizeSchema(List.of(
            channel("FilmPayload", FILM_PAYLOAD_FQN, "FILM_PAYLOAD", List.of(et2, et1)))),
            OUTPUT_PACKAGE).get(0);

        // Reversed relative to the test above: the concatenation follows mappedErrorTypes order,
        // not the (name-sorted) order the ByType constants are declared in.
        assertThat(channelInitializer(spec, "FILM_PAYLOAD"))
            .isEqualTo("concat(ByType.FILM_NOT_FOUND_EXCEPTION, ByType.FILM_FK_VIOLATION)");
        assertThat(byTypeHolder(spec).fieldSpecs()).extracting(FieldSpec::name)
            .containsExactly("FILM_FK_VIOLATION", "FILM_NOT_FOUND_EXCEPTION");
    }

    @Test
    void byTypeConstant_isMintedForAnErrorTypeNoChannelMaps() {
        // The per-type grain is keyed on the schema's @error registry, not on channel reach. An
        // @error type no channel maps still gets a fetchers class (TypeUnitCommands mints one per
        // registered @error type), and that class's message body names this constant, so a
        // channel-keyed mint would leave it naming something that does not exist.
        var mapped = errorType("FilmFkViolation", FILM_FK_FQN,
            List.of(new SqlStateHandler("23503", Optional.empty(), new ClientMessage.FromSource())));
        var unmapped = errorType("FilmValidation", FILM_VALIDATION_FQN,
            List.of(new ExceptionHandler("java.lang.IllegalStateException",
                Optional.empty(), new ClientMessage.Static("nope"))));
        var raw = synthesizeSchema(List.of(
            channel("FilmPayload", FILM_PAYLOAD_FQN, "FILM_PAYLOAD", List.of(mapped))));
        var types = new LinkedHashMap<>(raw.types());
        types.put("FilmValidation", unmapped);
        var spec = ErrorMappingsClassGenerator
            .generate(new GraphitronSchema(types, raw.fields()), OUTPUT_PACKAGE).get(0);

        assertThat(byTypeHolder(spec).fieldSpecs()).extracting(FieldSpec::name)
            .containsExactly("FILM_FK_VIOLATION", "FILM_VALIDATION");
        assertThat(byTypeEntries(spec, "FILM_VALIDATION")).contains("\"nope\"");
        // No channel maps it, so no channel constant names it.
        assertThat(channelInitializer(spec, "FILM_PAYLOAD"))
            .isEqualTo("concat(ByType.FILM_FK_VIOLATION)");
    }

    @Test
    void emits_handlerForErrorTypeMissingClassFqn() {
        // Source-direct dispatch: an @error type without a backing class still emits its
        // handlers. Per §2c there is no developer-supplied @error data class: the matched
        // exception itself goes into the errors list, so the absence of a Java class on the
        // SDL @error side is no longer a reason to drop a Mapping.
        var withClass = errorType("FilmNotFoundException", FILM_NOT_FOUND_FQN,
            List.of(new ExceptionHandler("java.lang.RuntimeException",
                Optional.empty(), new ClientMessage.FromSource())));
        var withoutClass = new ErrorType("Untyped", null,
            List.of(new ExceptionHandler("java.lang.IllegalStateException",
                Optional.empty(), new ClientMessage.FromSource())), List.of());
        var ch = channel("FilmPayload", FILM_PAYLOAD_FQN, "FILM_PAYLOAD",
            List.of(withClass, withoutClass));
        var spec = ErrorMappingsClassGenerator
            .generate(synthesizeSchema(List.of(ch)), OUTPUT_PACKAGE).get(0);

        assertThat(byTypeEntries(spec, "FILM_NOT_FOUND_EXCEPTION")).contains("RuntimeException");
        assertThat(byTypeEntries(spec, "UNTYPED")).contains("IllegalStateException");
    }

    /** The nested {@code ByType} holder, where the definition-keyed constants live. */
    private static TypeSpec byTypeHolder(TypeSpec spec) {
        return spec.typeSpecs().stream()
            .filter(t -> t.name().equals(ErrorMappingsClassGenerator.BY_TYPE_HOLDER))
            .findFirst()
            .orElseThrow(() -> new AssertionError("no ByType holder emitted on " + spec.name()));
    }

    /** One {@code @error} type's dispatch-table initializer, rendered. */
    private static String byTypeEntries(TypeSpec spec, String constantName) {
        return byTypeHolder(spec).fieldSpecs().stream()
            .filter(f -> f.name().equals(constantName))
            .findFirst()
            .orElseThrow(() -> new AssertionError("no ByType." + constantName + " constant emitted"))
            .initializer().toString();
    }

    /** One channel constant's initializer, rendered. */
    private static String channelInitializer(TypeSpec spec, String constantName) {
        return spec.fieldSpecs().stream()
            .filter(f -> f.name().equals(constantName))
            .findFirst()
            .orElseThrow(() -> new AssertionError("no channel constant " + constantName + " emitted"))
            .initializer().toString();
    }

    private static ErrorType errorType(String name, String classFqn, List<ErrorType.Handler> handlers) {
        // classFqn retained as a parameter for call-site signature stability while step-3 dust
        // settles; under source-direct dispatch the developer-supplied @error data class
        // is gone and the value is ignored. Future cleanups can drop the param entirely.
        return new ErrorType(name, null, handlers, List.of());
    }

    private static ErrorChannel.PayloadClass channel(String payloadSimple, String payloadFqn, String constantName,
                                        List<ErrorType> mappedErrorTypes) {
        var stringType = (TypeName) ClassName.get(String.class);
        var defaultedSlots = List.of(
            new DefaultedSlot(0, "data", stringType, "null"));
        return new ErrorChannel.PayloadClass(mappedErrorTypes, ClassName.bestGuess(payloadFqn),
            new no.sikt.graphitron.rewrite.model.ErrorsSlot.CtorParameterIndex(1),
            defaultedSlots, constantName);
    }

    /**
     * Synthesises a minimal schema with one DML-record mutation field per channel
     * supplied: the lightest field variant whose channel slot carries the
     * {@link ErrorChannel.RouterDispatched} partition {@link ErrorChannel.PayloadClass}
     * belongs to (root {@code @service} variants carry {@link ErrorChannel.Mapped}).
     */
    private static GraphitronSchema synthesizeSchema(List<ErrorChannel.PayloadClass> channels) {
        Map<String, GraphitronType> types = new LinkedHashMap<>();
        types.put("Query", new GraphitronType.RootType("Query", null));
        Map<FieldCoordinates, GraphitronField> fields = new LinkedHashMap<>();
        for (int i = 0; i < channels.size(); i++) {
            String fieldName = "fetch" + i;
            var tableInputArg = new no.sikt.graphitron.rewrite.ArgumentRef.InputTypeArg.TableInputArg(
                "in", "FilmInput", true, false, TestFixtures.filmTable(), List.of(), Optional.empty(),
                List.of(), List.of(), List.of());
            var field = new no.sikt.graphitron.rewrite.model.MutationField.MutationDmlRecordField(
                "Query",
                fieldName,
                null,
                new ReturnTypeRef.ResultReturnType("FilmPayload", new FieldWrapper.Single(true), null, null),
                new no.sikt.graphitron.rewrite.model.OperationMember.Write.Insert(tableInputArg),
                Optional.of(channels.get(i)));
            fields.put(FieldCoordinates.coordinates("Query", fieldName), field);
        }
        return new GraphitronSchema(types, fields);
    }

    /**
     * Same as {@link #synthesizeSchema} but runs the §3 hash-suffix dedup pass over the
     * synthesized fields before constructing the {@link GraphitronSchema}. Mirrors the
     * production schema-build flow where {@code MappingsConstantNameDedup} runs between
     * field classification and emitter invocation.
     */
    private static GraphitronSchema synthesizeSchemaWithDedup(List<ErrorChannel.PayloadClass> channels) {
        var raw = synthesizeSchema(channels);
        var deduped = no.sikt.graphitron.rewrite.MappingsConstantNameDedup.apply(raw.fields());
        return new GraphitronSchema(raw.types(), deduped);
    }
}
