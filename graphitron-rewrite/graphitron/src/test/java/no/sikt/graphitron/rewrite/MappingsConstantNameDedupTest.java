package no.sikt.graphitron.rewrite;

import graphql.schema.FieldCoordinates;
import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.TypeName;
import no.sikt.graphitron.rewrite.model.DefaultedSlot;
import no.sikt.graphitron.rewrite.model.ErrorChannel;
import no.sikt.graphitron.rewrite.model.FieldWrapper;
import no.sikt.graphitron.rewrite.model.GraphitronField;
import no.sikt.graphitron.rewrite.model.GraphitronType.ErrorType;
import no.sikt.graphitron.rewrite.model.GraphitronType.ErrorType.ExceptionHandler;
import no.sikt.graphitron.rewrite.model.GraphitronType.ErrorType.SqlStateHandler;
import no.sikt.graphitron.rewrite.model.MethodRef;
import no.sikt.graphitron.rewrite.model.MutationField;
import no.sikt.graphitron.rewrite.model.ReturnTypeRef;
import no.sikt.graphitron.rewrite.model.WithErrorChannel;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Focused unit coverage for {@link MappingsConstantNameDedup#apply}. The four scenarios in the
 * class javadoc are tested directly rather than through the schema build's emitter pipeline:
 *
 * <ul>
 *   <li>Single channel for a payload class: the bare name passes through.</li>
 *   <li>Two channels with identical handler lists for the same payload: both keep the bare name
 *       (no suffix; the dedup recognises them as equivalent).</li>
 *   <li>Two channels with different handler lists for the same payload: the first-seen channel
 *       keeps the bare name; the second gets a deterministic 8-hex suffix.</li>
 *   <li>Two channels for different payload classes: each keeps its bare name (the dedup never
 *       collapses across payload boundaries).</li>
 * </ul>
 *
 * <p>The schema-build path also exercises the dedup indirectly through
 * {@code ErrorMappingsClassGeneratorTest}; these tests assert the dedup contract in isolation
 * so a regression that breaks the bare/suffix split fails here before it propagates downstream.
 */
@UnitTier
class MappingsConstantNameDedupTest {

    private static final String FILM_PAYLOAD_FQN = "com.example.FilmPayload";
    private static final String CREATE_FILM_PAYLOAD_FQN = "com.example.CreateFilmPayload";

    @Test
    void singleChannel_keepsBareName() {
        var channel = filmPayloadChannel(exceptionHandler("java.lang.RuntimeException"));
        var fields = oneFetcher("fetch0", channel);

        var deduped = MappingsConstantNameDedup.apply(fields);

        var resolved = channelOf(deduped, "fetch0");
        assertThat(resolved.mappingsConstantName()).isEqualTo("FILM_PAYLOAD");
    }

    @Test
    void identicalChannels_samePayload_shareBareName() {
        // Two fetchers with byte-identical handler lists collapse to one mapping group; both
        // channels keep the bare name. No suffix is emitted because there's only one shape.
        var ch1 = filmPayloadChannel(exceptionHandler("java.lang.RuntimeException"));
        var ch2 = filmPayloadChannel(exceptionHandler("java.lang.RuntimeException"));
        var fields = twoFetchers("fetch0", ch1, "fetch1", ch2);

        var deduped = MappingsConstantNameDedup.apply(fields);

        assertThat(channelOf(deduped, "fetch0").mappingsConstantName()).isEqualTo("FILM_PAYLOAD");
        assertThat(channelOf(deduped, "fetch1").mappingsConstantName()).isEqualTo("FILM_PAYLOAD");
    }

    @Test
    void distinctShapes_samePayload_firstKeepsBareNameSecondGetsSuffix() {
        // Two channels for the same payload class but different handler lists. The first-seen
        // channel keeps the bare name; the second gets a deterministic 8-hex suffix derived from
        // the SHA-256 of its canonicalised handler list. The suffix shape is fixed at 8 hex
        // chars per the §3 spec; the actual hex value is implementation-defined, so this test
        // asserts the structural contract rather than literal characters.
        var ch1 = filmPayloadChannel(exceptionHandler("java.lang.RuntimeException"));
        var ch2 = filmPayloadChannel(sqlStateHandler("23503"));
        var fields = twoFetchers("fetch0", ch1, "fetch1", ch2);

        var deduped = MappingsConstantNameDedup.apply(fields);

        var name0 = channelOf(deduped, "fetch0").mappingsConstantName();
        var name1 = channelOf(deduped, "fetch1").mappingsConstantName();
        assertThat(name0).isEqualTo("FILM_PAYLOAD");
        assertThat(name1).startsWith("FILM_PAYLOAD_").hasSize("FILM_PAYLOAD_".length() + 8);
        assertThat(name1.substring("FILM_PAYLOAD_".length())).matches("[0-9A-F]{8}");
    }

    @Test
    void distinctShapes_samePayload_suffixIsDeterministic() {
        // Same input handler list always produces the same suffix; this rules out a regression
        // that would derive the suffix from iteration order or per-build state.
        var ch1a = filmPayloadChannel(exceptionHandler("java.lang.RuntimeException"));
        var ch2a = filmPayloadChannel(sqlStateHandler("23503"));
        var ch1b = filmPayloadChannel(exceptionHandler("java.lang.RuntimeException"));
        var ch2b = filmPayloadChannel(sqlStateHandler("23503"));

        var run1 = MappingsConstantNameDedup.apply(twoFetchers("a0", ch1a, "a1", ch2a));
        var run2 = MappingsConstantNameDedup.apply(twoFetchers("b0", ch1b, "b1", ch2b));

        assertThat(channelOf(run1, "a1").mappingsConstantName())
            .isEqualTo(channelOf(run2, "b1").mappingsConstantName());
    }

    @Test
    void distinctPayloadClasses_neverCollapse() {
        // Cross-payload dedup never happens, even if two channels happen to carry byte-identical
        // handler lists, because the per-fetcher payload-factory differs and a shared constant
        // would be misleading.
        var ch1 = filmPayloadChannel(exceptionHandler("java.lang.RuntimeException"));
        var ch2 = createFilmPayloadChannel(exceptionHandler("java.lang.RuntimeException"));
        var fields = twoFetchers("fetch0", ch1, "fetch1", ch2);

        var deduped = MappingsConstantNameDedup.apply(fields);

        assertThat(channelOf(deduped, "fetch0").mappingsConstantName()).isEqualTo("FILM_PAYLOAD");
        assertThat(channelOf(deduped, "fetch1").mappingsConstantName()).isEqualTo("CREATE_FILM_PAYLOAD");
    }

    @Test
    void fieldsWithoutErrorChannel_passThroughUnchanged() {
        // Fields that don't carry a channel pass through by reference. The dedup must not crash
        // on such fields and must not invent a channel for them.
        var withoutChannel = mutationServiceRecordField("noChannel", FILM_PAYLOAD_FQN, Optional.empty());
        var withChannel = mutationServiceRecordField("withChannel", FILM_PAYLOAD_FQN,
            Optional.of(filmPayloadChannel(exceptionHandler("java.lang.RuntimeException"))));
        var fields = new LinkedHashMap<FieldCoordinates, GraphitronField>();
        fields.put(FieldCoordinates.coordinates("Mutation", "noChannel"), withoutChannel);
        fields.put(FieldCoordinates.coordinates("Mutation", "withChannel"), withChannel);

        var deduped = MappingsConstantNameDedup.apply(fields);

        // The no-channel field must round-trip by reference (the dedup never reconstructs it).
        assertThat(deduped.get(FieldCoordinates.coordinates("Mutation", "noChannel")))
            .isSameAs(withoutChannel);
        assertThat(channelOf(deduped, "withChannel").mappingsConstantName()).isEqualTo("FILM_PAYLOAD");
    }

    // ===== Helpers =====

    private static ErrorChannel filmPayloadChannel(ErrorType.Handler handler) {
        return channel(FILM_PAYLOAD_FQN, "FILM_PAYLOAD", handler);
    }

    private static ErrorChannel createFilmPayloadChannel(ErrorType.Handler handler) {
        return channel(CREATE_FILM_PAYLOAD_FQN, "CREATE_FILM_PAYLOAD", handler);
    }

    private static ErrorChannel channel(String payloadFqn, String constantName, ErrorType.Handler handler) {
        var errorType = new ErrorType("FilmErr", null, List.of(handler));
        return new ErrorChannel(
            List.of(errorType),
            ClassName.bestGuess(payloadFqn),
            1,
            List.of(new DefaultedSlot(0, "data", ClassName.get("java.lang", "String"), "null")),
            constantName);
    }

    private static ExceptionHandler exceptionHandler(String className) {
        return new ExceptionHandler(className, Optional.empty(), Optional.empty());
    }

    private static SqlStateHandler sqlStateHandler(String state) {
        return new SqlStateHandler(state, Optional.empty(), Optional.empty());
    }

    private static MutationField.MutationServiceRecordField mutationServiceRecordField(
            String fieldName, String payloadFqn, Optional<ErrorChannel> channel) {
        return new MutationField.MutationServiceRecordField(
            "Mutation",
            fieldName,
            null,
            new ReturnTypeRef.ResultReturnType("Payload", new FieldWrapper.Single(true), payloadFqn),
            new MethodRef.Basic("com.example.SvcStub", "doStuff", TypeName.OBJECT, List.of()),
            channel,
            Optional.empty());
    }

    private static Map<FieldCoordinates, GraphitronField> oneFetcher(String fieldName, ErrorChannel channel) {
        var field = mutationServiceRecordField(fieldName,
            channel.payloadClass().reflectionName(), Optional.of(channel));
        var fields = new LinkedHashMap<FieldCoordinates, GraphitronField>();
        fields.put(FieldCoordinates.coordinates("Mutation", fieldName), field);
        return fields;
    }

    private static Map<FieldCoordinates, GraphitronField> twoFetchers(String n0, ErrorChannel c0,
                                                                      String n1, ErrorChannel c1) {
        var f0 = mutationServiceRecordField(n0, c0.payloadClass().reflectionName(), Optional.of(c0));
        var f1 = mutationServiceRecordField(n1, c1.payloadClass().reflectionName(), Optional.of(c1));
        var fields = new LinkedHashMap<FieldCoordinates, GraphitronField>();
        fields.put(FieldCoordinates.coordinates("Mutation", n0), f0);
        fields.put(FieldCoordinates.coordinates("Mutation", n1), f1);
        return fields;
    }

    private static ErrorChannel channelOf(Map<FieldCoordinates, GraphitronField> fields, String fieldName) {
        var field = fields.get(FieldCoordinates.coordinates("Mutation", fieldName));
        if (!(field instanceof WithErrorChannel w) || w.errorChannel().isEmpty()) {
            throw new AssertionError("Field '" + fieldName + "' has no ErrorChannel");
        }
        return w.errorChannel().get();
    }
}
