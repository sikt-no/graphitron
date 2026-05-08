package no.sikt.graphitron.rewrite.model;

import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pins the canonical-constructor non-empty invariants on the composite-key {@link
 * LookupMapping.ColumnMapping.LookupArg} permits. The emitter and slot pipeline read
 * {@code bindings.get(0)} and iterate {@code bindings} unconditionally; an empty list would
 * surface as a {@code Row<1>} with only the index cell or an out-of-bounds index, both
 * structurally invalid.
 */
@UnitTier
class LookupMappingTest {

    private static final ColumnRef FILM_ID =
        new ColumnRef("film_id", "FILM_ID", "java.lang.Integer");
    private static final ColumnRef ACTOR_ID =
        new ColumnRef("actor_id", "ACTOR_ID", "java.lang.Integer");

    private static final HelperRef.Decode DECODE_FILM_ACTOR = new HelperRef.Decode(
        ClassName.bestGuess("com.example.NodeIdEncoder"),
        "decodeFilmActor",
        List.of(FILM_ID, ACTOR_ID));

    @Test
    void mapInput_withEmptyBindings_throws() {
        assertThatThrownBy(() ->
            new LookupMapping.ColumnMapping.LookupArg.MapInput("keys", true, List.of()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("MapInput 'keys'");
    }

    @Test
    void mapInput_withSingleBinding_isLegal() {
        var binding = new InputColumnBinding.MapBinding(
            "filmId", FILM_ID, new CallSiteExtraction.Direct());
        var mapInput = new LookupMapping.ColumnMapping.LookupArg.MapInput(
            "keys", true, List.of(binding));
        assertThat(mapInput.bindings()).containsExactly(binding);
    }

    @Test
    void decodedRecord_withEmptyBindings_throws() {
        assertThatThrownBy(() ->
            new LookupMapping.ColumnMapping.LookupArg.DecodedRecord(
                "ids", true,
                new CallSiteExtraction.NodeIdDecodeKeys.ThrowOnMismatch(DECODE_FILM_ACTOR),
                List.of()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("DecodedRecord 'ids'");
    }
}
