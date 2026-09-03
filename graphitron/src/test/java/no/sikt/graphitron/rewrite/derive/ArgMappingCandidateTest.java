package no.sikt.graphitron.rewrite.derive;

import no.sikt.graphitron.model.test.CapturedStore;
import no.sikt.graphitron.model.jooq.JooqCatalog;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.jooq.DSLContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static no.sikt.graphitron.common.configuration.TestConfiguration.testContext;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_ARGMAPPING_CANDIDATE;
import static no.sikt.graphitron.model.Tables.INTENT_INPUT_OCCURRENCE_PATH;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * What an argMapping right-hand side may name, checked against the capture that writes it.
 *
 * <p>The population is the point, and it is keyed by the coordinate the directive carrying the
 * argMapping sits on rather than by the container whose members a head may name. Three coordinates
 * can carry one: a field, whose arguments a head names; an argument; and an input field. The last
 * two carry a value of their own, so what may be written there is that value, whatever it opens
 * into, and the older spelling that repeats the carrier's name before descending. Both spellings
 * are rows, so resolution is membership and no reader spells a scope test of its own.
 *
 * <p>This relation also exists because the occurrence-path relation beside it holds a different
 * population: that one admits an argument only when the argument's named type is an input object,
 * and a bare name with no dots is a legal right-hand side whatever the argument's type. The first
 * test below is the one that would fail if this relation were quietly re-derived from its
 * neighbour.
 *
 * <p>What each test pins is a column a reader would otherwise have had to recover from the key: the
 * parent link that makes the candidates a tree, the name that says what an author writes at a step,
 * and the mark that tells the two spellings apart.
 */
@PipelineTier
class ArgMappingCandidateTest {

    private static final String GRAPH = CapturedStore.GRAPH;

    @TempDir
    Path tmp;

    @Test
    @DisplayName("every argument is a root at its field, whatever its type")
    void everyArgumentIsARootWhateverItsType() {
        String sdl = """
            input FilterInput { title: String }
            type Film @table(name: "film") { filmId: Int! @field(name: "film_id") }
            type Query { films(filter: FilterInput, limit: Int): [Film!]! }
            """;
        try (var store = CapturedStore.ofCatalog(tmp, sdl, jooq())) {
            var dsl = store.dsl();
            var c = GRAPHITRON_ARGMAPPING_CANDIDATE;
            assertThat(dsl.select(c.NAME).from(c)
                    .where(c.GRAPH_NAME.eq(GRAPH)).and(c.COORDINATE.eq("Query.films"))
                    .and(c.DEPTH.eq(0))
                    .fetch(c.NAME))
                .as("a root per argument at the field coordinate, the scalar one included")
                .containsExactlyInAnyOrder("filter", "limit");
            assertThat(dsl.selectFrom(INTENT_INPUT_OCCURRENCE_PATH)
                    .where(INTENT_INPUT_OCCURRENCE_PATH.GRAPH_NAME.eq(GRAPH))
                    .and(INTENT_INPUT_OCCURRENCE_PATH.DEPTH.eq(0))
                    .fetch(INTENT_INPUT_OCCURRENCE_PATH.ROOT_ARGUMENT_NAME))
                .as("the neighbour holds only the argument that descends, which is why it could not"
                    + " serve as the candidate relation")
                .containsExactly("filter");
        }
    }

    @Test
    @DisplayName("a field coordinate offers its arguments and their descent, and repeats nothing")
    void aFieldCoordinateOffersItsArgumentsAndTheirDescent() {
        try (var store = CapturedStore.ofCatalog(tmp, NESTED, jooq())) {
            assertThat(at(store.dsl(), "Query.films"))
                .as("path | parent | name | type | depth | closesCycle | deprecated, the root named"
                    + " by its own head and told apart by having no parent rather than by an empty"
                    + " path. A head here names an argument, so nothing repeats the field's name")
                .containsExactlyInAnyOrder(
                    "input|<none>|input|Outer|0|false|false",
                    "input.inner|input|inner|Inner|1|false|false",
                    "input.inner.code|input.inner|code|String|2|false|false");
        }
    }

    @Test
    @DisplayName("an argument coordinate offers the argument, the clean descent and the repeat")
    void anArgumentCoordinateOffersBothSpellings() {
        try (var store = CapturedStore.ofCatalog(tmp, NESTED, jooq())) {
            assertThat(at(store.dsl(), "Query.films(input:)"))
                .as("the argument's own name binds the whole value and is not deprecated, there"
                    + " being no other way to say it; below it the clean spelling and the one that"
                    + " repeats the argument, which is what authors write today")
                .containsExactlyInAnyOrder(
                    "input|<none>|input|Outer|0|false|false",
                    "inner|<none>|inner|Inner|0|false|false",
                    "inner.code|inner|code|String|1|false|false",
                    "input.inner|input|inner|Inner|1|false|true",
                    "input.inner.code|input.inner|code|String|2|false|true");
        }
    }

    @Test
    @DisplayName("an input-field coordinate offers the same two spellings under its own field")
    void anInputFieldCoordinateOffersBothSpellings() {
        try (var store = CapturedStore.ofCatalog(tmp, NESTED, jooq())) {
            assertThat(at(store.dsl(), "Outer.inner"))
                .as("one coordinate per input field, not one per occurrence: what follows a field"
                    + " is fixed by that field's own type, so every occurrence would repeat one"
                    + " subtree")
                .containsExactlyInAnyOrder(
                    "inner|<none>|inner|Inner|0|false|false",
                    "code|<none>|code|String|0|false|false",
                    "inner.code|inner|code|String|1|false|true");
        }
    }

    @Test
    @DisplayName("a scalar input field offers only itself")
    void aScalarInputFieldOffersOnlyItself() {
        try (var store = CapturedStore.ofCatalog(tmp, NESTED, jooq())) {
            assertThat(at(store.dsl(), "Inner.code"))
                .as("nothing opens under a String, so the field's own name is the whole of what may"
                    + " be written there, and it is a row rather than an absence")
                .containsExactly("code|<none>|code|String|0|false|false");
        }
    }

    @Test
    @DisplayName("the element that closes a cycle keeps its row and says so")
    void theElementThatClosesACycleSaysSo() {
        String sdl = """
            input A { b: B }
            input B { a: A }
            type Film @table(name: "film") { filmId: Int! @field(name: "film_id") }
            type Query { films(filter: A): [Film!]! }
            """;
        try (var store = CapturedStore.ofCatalog(tmp, sdl, jooq())) {
            assertThat(at(store.dsl(), "Query.films"))
                .as("the closing element is nameable and so has a row, marked as what it is, and"
                    + " nothing below it is written; a reader tells it from a leaf by the column"
                    + " rather than by the absence of children")
                .containsExactlyInAnyOrder(
                    "filter|<none>|filter|A|0|false|false",
                    "filter.b|filter|b|B|1|false|false",
                    "filter.b.a|filter.b|a|A|2|true|false");
        }
    }

    /**
     * The carrier's own type counts as an ancestor of the clean spelling even though no row stands
     * for it, which is what keeps the two spellings from stopping at different depths. Written out
     * because it is the one place the clean tree could have under-detected: it has no parent row to
     * walk, so the type it hangs under is joined rather than followed.
     */
    @Test
    @DisplayName("the clean spelling closes the same cycle the repeating one does")
    void bothSpellingsCloseTheSameCycle() {
        String sdl = """
            input A { b: B }
            input B { a: A }
            type Film @table(name: "film") { filmId: Int! @field(name: "film_id") }
            type Query { films(filter: A): [Film!]! }
            """;
        try (var store = CapturedStore.ofCatalog(tmp, sdl, jooq())) {
            assertThat(at(store.dsl(), "Query.films(filter:)"))
                .containsExactlyInAnyOrder(
                    "filter|<none>|filter|A|0|false|false",
                    "b|<none>|b|B|0|false|false",
                    "b.a|b|a|A|1|true|false",
                    "filter.b|filter|b|B|1|false|true",
                    "filter.b.a|filter.b|a|A|2|true|true");
        }
    }

    @Test
    @DisplayName("an input field is one coordinate however many arguments reach it")
    void anInputFieldIsOneCoordinateHoweverManyArgumentsReachIt() {
        String sdl = """
            input Termin { arstall: Int }
            input FilterA { termin: Termin }
            input FilterB { termin: Termin }
            type Film @table(name: "film") { filmId: Int! @field(name: "film_id") }
            type Query { a(f: FilterA): [Film!]!, b(f: FilterB): [Film!]! }
            """;
        try (var store = CapturedStore.ofCatalog(tmp, sdl, jooq())) {
            var c = GRAPHITRON_ARGMAPPING_CANDIDATE;
            assertThat(store.dsl().select(c.COORDINATE, c.PATH).from(c)
                    .where(c.GRAPH_NAME.eq(GRAPH))
                    .and(c.COORDINATE.in("FilterA.termin", "FilterB.termin"))
                    .fetch(r -> r.value1() + "|" + r.value2()))
                .as("one coordinate per input field, carrying its own name, the clean descent and"
                    + " the repeating spelling of it, and no row per argument that reaches it")
                .containsExactlyInAnyOrder(
                    "FilterA.termin|termin", "FilterA.termin|arstall",
                    "FilterA.termin|termin.arstall",
                    "FilterB.termin|termin", "FilterB.termin|arstall",
                    "FilterB.termin|termin.arstall");
        }
    }

    /**
     * Two readings meet on one spelling exactly once, at the carrier's own name against a field of
     * the carrier's type with that name. The carrier wins, which is what the enforced spelling
     * meant before both readings were held, and the row says the other one existed.
     */
    @Test
    @DisplayName("a field named after its carrier loses the spelling and the winner is marked")
    void aFieldNamedAfterItsCarrierIsMarkedRatherThanDropped() {
        String sdl = """
            input Filter { filter: String, other: Int }
            type Film @table(name: "film") { filmId: Int! @field(name: "film_id") }
            type Query { films(filter: Filter): [Film!]! }
            """;
        try (var store = CapturedStore.ofCatalog(tmp, sdl, jooq())) {
            var c = GRAPHITRON_ARGMAPPING_CANDIDATE;
            assertThat(store.dsl().select(c.PATH, c.NAMED_TYPE, c.AMBIGUOUS).from(c)
                    .where(c.GRAPH_NAME.eq(GRAPH))
                    .and(c.COORDINATE.eq("Query.films(filter:)"))
                    .fetch(r -> r.value1() + "|" + r.value2() + "|" + r.value3()))
                .as("the spelling filter binds the argument rather than the field of the same name,"
                    + " and carries the mark; the field is still reachable by the repeating"
                    + " spelling, which nothing contests")
                .containsExactlyInAnyOrder(
                    "filter|Filter|true",
                    "other|Int|false",
                    "filter.filter|String|false",
                    "filter.other|Int|false");
        }
    }

    private static final String NESTED = """
        input Inner { code: String }
        input Outer { inner: Inner }
        type Film @table(name: "film") { filmId: Int! @field(name: "film_id") }
        type Query { films(input: Outer): [Film!]! }
        """;

    /** Every candidate at one coordinate, rendered so a whole population is one assertion. */
    private static List<String> at(DSLContext dsl, String coordinate) {
        var c = GRAPHITRON_ARGMAPPING_CANDIDATE;
        return dsl.selectFrom(c)
            .where(c.GRAPH_NAME.eq(GRAPH)).and(c.COORDINATE.eq(coordinate))
            .fetch(r -> r.getPath() + "|"
                + (r.getParentPath() == null ? "<none>" : r.getParentPath()) + "|"
                + r.getName() + "|" + r.getNamedType() + "|" + r.getDepth() + "|"
                + r.getClosesCycle() + "|" + r.getDeprecated());
    }

    private static JooqCatalog jooq() {
        var ctx = testContext();
        return new JooqCatalog(ctx.jooqPackage(), ctx.codegenLoader());
    }
}
