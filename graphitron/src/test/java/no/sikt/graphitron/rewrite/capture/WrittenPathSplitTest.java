package no.sikt.graphitron.rewrite.capture;

import no.sikt.graphitron.model.test.CapturedStore;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.jooq.DSLContext;
import org.jooq.Record3;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_ARGMAPPING_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_UNDECODED_ARGUMENT;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * How an argMapping right-hand side comes apart, which is now the engine's answer rather than a
 * relation of its own. {@code graphitron_argmapping_entry} stores the path as written and computes
 * three readings of it: the first name, the path less its last name, and that last name. Each has
 * exactly one reader, and the three expressions are a schema decision rather than a library
 * default, so they are pinned here.
 *
 * <p>The relation this replaces held one row per position of one path at one coordinate, so that a
 * reader could ask how far a written path resolved by probing every prefix. Nothing asks that any
 * more: the candidate relation holds every legal spelling at a coordinate, so a path either is one
 * or its head is one, and two equalities answer what a stored decomposition and a ranked probe used
 * to. What is worth pinning is therefore the split itself and the boundary a splitting rule gets
 * wrong, a bare name having no head and being its own tail.
 */
@UnitTier
class WrittenPathSplitTest {

    /**
     * A three-name path, a bare name, a name carrying an underscore, and a sigil, so the pinned
     * expressions meet every shape an author can write. The underscore matters: it is a LIKE
     * wildcard and a legal GraphQL name character at once, so a split written with the wrong
     * operator passes on every other fixture and fails on this one.
     */
    private static final String FIXTURE = """
        type Query {
          films(filter: FilmFilter, title_or_name: String): [Film!]! @service(
            service: {className: "com.example.FilmService", method: "find",
                      argMapping: "name: filter.title.value, exact: title_or_name"})
        }
        type Film @table(name: "film") { title: String }
        input FilmFilter { title: TitleFilter, rating: String }
        input TitleFilter { value: String }
        """;

    @Test
    @DisplayName("a dotted path splits into its first name, its head and its last name")
    void aDottedPathSplitsThreeWays(@TempDir Path tmp) {
        try (var store = CapturedStore.of(tmp, FIXTURE)) {
            assertThat(split(store.dsl(), "filter.title.value"))
                .containsExactly("filter", "filter.title", "value");
        }
    }

    /**
     * A bare name is the whole path, so it is its own first name and its own last name and has no
     * head at all. The null is the point: it is what makes "the head is a candidate" a question
     * with no answer at a single-name path rather than a comparison against the path itself, which
     * would resolve a name against itself and report one name trailing where none does.
     */
    @Test
    @DisplayName("a bare name is its own first and last, and has no head")
    void aBareNameHasNoHead(@TempDir Path tmp) {
        try (var store = CapturedStore.of(tmp, FIXTURE)) {
            assertThat(split(store.dsl(), "title_or_name"))
                .containsExactly("title_or_name", null, "title_or_name");
        }
    }

    /**
     * A value the grammar rejects quarantines whole and produces no entries, so it produces no
     * split either. The split follows the entry rows rather than the raw string, which is what
     * keeps a malformed argMapping from arriving here as a half-parse.
     */
    @Test
    @DisplayName("a quarantined argMapping contributes no entry to split")
    void aQuarantinedArgMappingContributesNoEntry(@TempDir Path tmp) {
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
            assertThat(store.dsl().selectFrom(GRAPHITRON_ARGMAPPING_ENTRY).fetch()).isEmpty();
        }
    }

    /** The three generated readings of one written path, in the order the relation declares them. */
    private static Iterable<String> split(DSLContext dsl, String writtenPath) {
        var e = GRAPHITRON_ARGMAPPING_ENTRY;
        Record3<String, String, String> row = dsl
            .select(e.ROOT_NAME, e.HEAD_PATH, e.TAIL_NAME)
            .from(e)
            .where(e.WRITTEN_PATH.eq(writtenPath))
            .fetchOne();
        assertThat(row).as("the fixture must write the path %s", writtenPath).isNotNull();
        return java.util.Arrays.asList(row.value1(), row.value2(), row.value3());
    }
}
