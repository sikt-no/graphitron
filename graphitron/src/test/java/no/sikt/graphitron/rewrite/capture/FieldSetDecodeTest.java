package no.sikt.graphitron.rewrite.capture;

import no.sikt.graphitron.model.test.CapturedStore;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.jooq.DSLContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_FEDERATION_KEY_FIELD;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_FEDERATION_KEY_FIELD_SEGMENT;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The registered anchor for federation's field-set grammar: what a {@code @key(fields:)} selection
 * is made of, recorded as segments rather than rendered back into a dotted string.
 *
 * <p>The nesting is the whole subject. The parser holds it in a prefix stack while it walks, and
 * the question is whether that structure reaches the store or is flattened on the way out; a
 * consumer asking which leaf a key selects and under what parent should join rather than split.
 *
 * <p>Tolerance is pinned beside it, because the value arrives from a registry that validated
 * nothing: a field set that does not close its braces yields whatever prefix parsed and never
 * throws, and what "whatever parsed" means is a fact worth stating rather than leaving to whoever
 * next reads the tokenizer.
 */
@UnitTier
class FieldSetDecodeTest {

    private static final String DIRECTIVES = """
        directive @link(url: String!, import: [String]) repeatable on SCHEMA
        directive @key(fields: String!, resolvable: Boolean) repeatable on OBJECT
        """;

    private static String federated(String fieldSet) {
        return DIRECTIVES + """
            extend schema @link(url: "https://specs.apollo.dev/federation/v2.10", import: ["@key"])

            type Query { film: Film }

            type Film @key(fields: "%s") {
              id: ID!
              title: String
              language: Language
            }

            type Language { id: ID!, name: String }
            """.formatted(fieldSet);
    }

    /** A flat selection is one selection of one segment, which is the base the nesting extends. */
    @Test
    @DisplayName("an unnested selection is a single segment")
    void anUnnestedSelectionIsASingleSegment(@TempDir Path tmp) {
        try (var store = CapturedStore.of(tmp, federated("id"))) {
            assertThat(selections(store.dsl())).isEqualTo(1);
            assertThat(segments(store.dsl(), 0)).containsExactly("id");
        }
    }

    /**
     * The case the relation exists for. Two leaves under one parent are two selections, each
     * carrying the parent as its own first segment, so the parent is a value a join can match
     * rather than a prefix a reader has to cut off a string.
     */
    @Test
    @DisplayName("a nested selection records its parent as a segment of each leaf")
    void aNestedSelectionRecordsItsParentPerLeaf(@TempDir Path tmp) {
        try (var store = CapturedStore.of(tmp, federated("language { id name }"))) {
            assertThat(selections(store.dsl())).isEqualTo(2);
            assertThat(segments(store.dsl(), 0)).containsExactly("language", "id");
            assertThat(segments(store.dsl(), 1)).containsExactly("language", "name");
        }
    }

    /** Order among selections is the written order, and the positions are dense from zero. */
    @Test
    @DisplayName("selections keep their written order beside a nested one")
    void selectionsKeepTheirWrittenOrder(@TempDir Path tmp) {
        try (var store = CapturedStore.of(tmp, federated("id language { name } title"))) {
            assertThat(selections(store.dsl())).isEqualTo(3);
            assertThat(segments(store.dsl(), 0)).containsExactly("id");
            assertThat(segments(store.dsl(), 1)).containsExactly("language", "name");
            assertThat(segments(store.dsl(), 2)).containsExactly("title");
        }
    }

    /**
     * A field set that never closes its brace still yields the leaves it reached, still under the
     * parent it had opened, and the capture completes. Rejecting it here would put a validation in
     * the decode, which is the detection stratum's business and not capture's.
     */
    @Test
    @DisplayName("an unclosed field set yields the prefix that parsed")
    void anUnclosedFieldSetYieldsTheParsedPrefix(@TempDir Path tmp) {
        try (var store = CapturedStore.of(tmp, federated("id language { name"))) {
            assertThat(selections(store.dsl())).isEqualTo(2);
            assertThat(segments(store.dsl(), 0)).containsExactly("id");
            assertThat(segments(store.dsl(), 1)).containsExactly("language", "name");
        }
    }

    // ===== Helpers =====

    /** How many selections the field set decoded to. */
    private static int selections(DSLContext dsl) {
        return dsl.fetchCount(GRAPHITRON_FEDERATION_KEY_FIELD);
    }

    /** One selection's segments, outermost first, which is also the density check. */
    private static List<String> segments(DSLContext dsl, int position) {
        var s = GRAPHITRON_FEDERATION_KEY_FIELD_SEGMENT;
        return dsl.select(s.SEGMENT_NAME)
            .from(s)
            .where(s.POSITION.eq(position))
            .orderBy(s.SEGMENT_POSITION)
            .fetch(s.SEGMENT_NAME);
    }
}
