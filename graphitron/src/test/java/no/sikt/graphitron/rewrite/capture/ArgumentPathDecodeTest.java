package no.sikt.graphitron.rewrite.capture;

import no.sikt.graphitron.model.Public;
import no.sikt.graphitron.rewrite.CapturedStore;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.jooq.DSLContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_ARGUMENT_PATH_SEGMENT;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_UNDECODED_ARGUMENT;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The registered anchor for {@code graphitron_argument_path_segment}: what a dotted argMapping
 * right-hand side is made of, recorded once per distinct path.
 *
 * <p>Two things are pinned separately, because they can fail independently. The decode's content is
 * pinned on hand-written fixtures, including the two boundaries a splitting rule gets wrong (a bare
 * name is one segment, not none; two paths sharing a head are two decodes, not one). And the
 * decode's <em>reach</em> is pinned mechanically: every relation in the schema carrying an
 * {@code argument_path} column is enumerated from the generated catalog, and each path it holds
 * must rejoin exactly from its own segment rows. An eighth pair relation whose writer forgot the
 * decode fails that test without anyone remembering to extend a list.
 */
@UnitTier
class ArgumentPathDecodeTest {

    /**
     * One dotted path, one bare name, and a second dotted path sharing the first's head, spread
     * across three directive kinds so the reach test has more than one pair relation to find.
     */
    private static final String FIXTURE = """
        type Query {
          films(filter: FilmFilter, title: String): [Film!]! @service(
            service: {className: "com.example.FilmService", method: "find",
                      argMapping: "name: filter.title.value, exact: title"})
        }
        type Film @table(name: "film") {
          title: String
          actors(filter: FilmFilter): [Actor!]! @condition(
            condition: {className: "com.example.Conditions", method: "byRating",
                        argMapping: "rating: filter.rating"})
        }
        type Actor @table(name: "actor") { firstName: String @field(name: "first_name") }
        input FilmFilter { title: TitleFilter, rating: String }
        input TitleFilter { value: String }
        """;

    // ===== What a decode says =====

    @Test
    @DisplayName("a dotted path is its segments, in order")
    void aDottedPathIsItsSegmentsInOrder(@TempDir Path tmp) {
        try (var store = CapturedStore.of(tmp, FIXTURE)) {
            assertThat(segments(store.dsl(), "filter.title.value"))
                .containsExactly("filter", "title", "value");
        }
    }

    /**
     * A bare argument name is one row rather than none. A decode that only spoke for dotted paths
     * would make every reader test for the absence before joining, which is the shape of a rule
     * that has not decided what it means.
     */
    @Test
    @DisplayName("a bare argument name is a one-segment decode, not an absent one")
    void aBareNameIsAOneSegmentDecode(@TempDir Path tmp) {
        try (var store = CapturedStore.of(tmp, FIXTURE)) {
            assertThat(segments(store.dsl(), "title")).containsExactly("title");
        }
    }

    /**
     * Two paths that start the same way are two decodes. The head is not the key here; the whole
     * path is, and a relation keyed by the head would have folded these into one.
     */
    @Test
    @DisplayName("paths sharing a head are separate decodes")
    void pathsSharingAHeadAreSeparateDecodes(@TempDir Path tmp) {
        try (var store = CapturedStore.of(tmp, FIXTURE)) {
            assertThat(segments(store.dsl(), "filter.title.value"))
                .containsExactly("filter", "title", "value");
            assertThat(segments(store.dsl(), "filter.rating"))
                .containsExactly("filter", "rating");
        }
    }

    /**
     * The grain: one row per position of one path, not one per site that spells it. The same path
     * written at two coordinates is decoded once, which is the whole reason this relation is keyed
     * by the value.
     */
    @Test
    @DisplayName("a path two sites spell is decoded once")
    void aPathTwoSitesSpellIsDecodedOnce(@TempDir Path tmp) {
        String twice = """
            type Query {
              a(title: String): Film @service(
                service: {className: "com.example.S", method: "a", argMapping: "t: title"})
              b(title: String): Film @service(
                service: {className: "com.example.S", method: "b", argMapping: "t: title"})
            }
            type Film { title: String }
            """;
        try (var store = CapturedStore.of(tmp, twice)) {
            assertThat(store.dsl().fetchCount(GRAPHITRON_ARGUMENT_PATH_SEGMENT,
                GRAPHITRON_ARGUMENT_PATH_SEGMENT.ARGUMENT_PATH.eq("title")))
                .as("two sites, one decode")
                .isOne();
        }
    }

    // ===== Where there is nothing to decode =====

    /**
     * A value the grammar rejects quarantines whole and produces no pairs, so it produces no
     * segments either. The decode follows the pair rows rather than the raw string, which is what
     * keeps a malformed argMapping from arriving here as a half-parse.
     */
    @Test
    @DisplayName("a quarantined argMapping contributes no segments")
    void aQuarantinedArgMappingContributesNoSegments(@TempDir Path tmp) {
        String malformed = """
            type Query {
              films: [Film!]! @service(
                service: {className: "com.example.S", method: "f", argMapping: "t: ,, ."})
            }
            type Film { title: String }
            """;
        try (var store = CapturedStore.of(tmp, malformed)) {
            assertThat(store.dsl().selectFrom(GRAPHITRON_UNDECODED_ARGUMENT).fetch())
                .as("the fixture has to actually quarantine, or this asserts nothing")
                .isNotEmpty();
            assertThat(store.dsl().selectFrom(GRAPHITRON_ARGUMENT_PATH_SEGMENT).fetch()).isEmpty();
        }
    }

    // ===== How far the decode reaches =====

    /**
     * Every relation carrying an {@code argument_path} is enumerated from the generated catalog
     * rather than listed here, so a pair relation added without a decode fails this rather than
     * passing unnoticed. Each path it holds must rejoin from its segments exactly, which pins the
     * order, the density of the positions, and that the two columns are talking about one string.
     */
    @Test
    @DisplayName("every relation with an argument path has its paths decoded, and they rejoin")
    void everyArgumentPathIsDecodedAndRejoins(@TempDir Path tmp) {
        var relations = Public.PUBLIC.getTables().stream()
            .filter(table -> table.field("ARGUMENT_PATH", String.class) != null)
            .filter(table -> !table.getName().equalsIgnoreCase(
                GRAPHITRON_ARGUMENT_PATH_SEGMENT.getName()))
            .toList();
        assertThat(relations)
            .as("the schema has pair relations, so this test is not vacuous")
            .isNotEmpty();

        try (var store = CapturedStore.of(tmp, FIXTURE)) {
            var checked = new ArrayList<String>();
            for (var relation : relations) {
                var column = relation.field("ARGUMENT_PATH", String.class);
                for (String path : store.dsl().selectDistinct(column).from(relation)
                    .fetch(column)) {
                    assertThat(String.join(".", segments(store.dsl(), path)))
                        .as("%s holds the path %s, which must rejoin from its own decode",
                            relation.getName(), path)
                        .isEqualTo(path);
                    checked.add(path);
                }
            }
            assertThat(checked)
                .as("the fixture has to put paths in those relations, or the loop found nothing")
                .isNotEmpty();
        }
    }

    // ===== Helpers =====

    /** One path's segments in position order, which is also the density check when rejoined. */
    private static List<String> segments(DSLContext dsl, String path) {
        var s = GRAPHITRON_ARGUMENT_PATH_SEGMENT;
        return dsl.select(s.SEGMENT_NAME)
            .from(s)
            .where(s.ARGUMENT_PATH.eq(path))
            .orderBy(s.POSITION)
            .fetch(s.SEGMENT_NAME);
    }
}
