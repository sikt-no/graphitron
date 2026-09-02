package no.sikt.graphitron.rewrite.derive;

import no.sikt.graphitron.rewrite.CapturedStore;
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
 * <p>The population is the point. This relation exists because the occurrence-path relation beside
 * it holds a different one: that one admits an argument only when the argument's named type is an
 * input object, and a bare name with no dots is a legal right-hand side whatever the argument's
 * type, so a scalar argument is a candidate too. The first test below is the one that would fail if
 * this relation were quietly re-derived from its neighbour.
 *
 * <p>What each test pins is a column a reader would otherwise have had to recover from the key:
 * the parent link that makes the candidates a tree, and the element name that says what an author
 * writes at a step. A test that asserted only the paths would pass against a relation that stored
 * the path and nothing else, which is the shape this one is replacing.
 */
@PipelineTier
class ArgMappingCandidateTest {

    private static final String GRAPH = CapturedStore.GRAPH;

    @TempDir
    Path tmp;

    @Test
    @DisplayName("every argument is a root, whatever its type, which the occurrence path is not")
    void everyArgumentIsARootWhateverItsType() {
        String sdl = """
            input FilterInput { title: String }
            type Film @table(name: "film") { filmId: Int! @field(name: "film_id") }
            type Query { films(filter: FilterInput, limit: Int): [Film!]! }
            """;
        try (var store = CapturedStore.ofCatalog(tmp, sdl, jooq())) {
            var dsl = store.dsl();
            assertThat(roots(dsl))
                .as("a root per argument, the scalar one included")
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
    @DisplayName("a nested candidate carries its parent, its own element name and its type")
    void aNestedCandidateCarriesItsParentAndItsName() {
        String sdl = """
            input Inner { code: String }
            input Outer { inner: Inner }
            type Film @table(name: "film") { filmId: Int! @field(name: "film_id") }
            type Query { films(input: Outer): [Film!]! }
            """;
        try (var store = CapturedStore.ofCatalog(tmp, sdl, jooq())) {
            assertThat(rows(store.dsl()))
                .as("path | parent | element | type | depth, the root named by its own head and"
                    + " told apart by having no parent rather than by an empty path")
                .containsExactlyInAnyOrder(
                    "Query.films|input|<none>|input|Outer|0|false",
                    "Query.films|input.inner|input|inner|Inner|1|false",
                    "Query.films|input.inner.code|input.inner|code|String|2|false");
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
            assertThat(rows(store.dsl()))
                .as("the closing element is nameable and so has a row, marked as what it is, and"
                    + " nothing below it is written; a reader tells it from a leaf by the column"
                    + " rather than by the absence of children")
                .containsExactlyInAnyOrder(
                    "Query.films|filter|<none>|filter|A|0|false",
                    "Query.films|filter.b|filter|b|B|1|false",
                    "Query.films|filter.b.a|filter.b|a|A|2|true");
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
                    .where(c.GRAPH_NAME.eq(GRAPH)).and(c.ELEMENT_KIND.eq("INPUT_FIELD"))
                    .and(c.TYPE_NAME.in("FilterA", "FilterB"))
                    .fetch(r -> r.value1() + "|" + r.value2()))
                .as("one coordinate per input type, its fields written as paths under it, and no"
                    + " row per argument that happens to reach it")
                .containsExactlyInAnyOrder(
                    "FilterA|termin", "FilterA|termin.arstall",
                    "FilterB|termin", "FilterB|termin.arstall");
        }
    }

    private static List<String> roots(DSLContext dsl) {
        var c = GRAPHITRON_ARGMAPPING_CANDIDATE;
        return dsl.select(c.ELEMENT_NAME).from(c)
            .where(c.GRAPH_NAME.eq(GRAPH)).and(c.DEPTH.eq(0))
            .and(c.ELEMENT_KIND.eq("ARGUMENT"))
            .fetch(c.ELEMENT_NAME);
    }

    private static List<String> rows(DSLContext dsl) {
        var c = GRAPHITRON_ARGMAPPING_CANDIDATE;
        return dsl.selectFrom(c).where(c.GRAPH_NAME.eq(GRAPH))
            .and(c.FIELD_NAME.isNotNull())
            .fetch(r -> r.getCoordinate() + "|" + r.getPath() + "|"
                + (r.getParentPath() == null ? "<none>" : r.getParentPath()) + "|"
                + r.getElementName() + "|" + r.getNamedType() + "|" + r.getDepth() + "|"
                + r.getClosesCycle());
    }

    private static JooqCatalog jooq() {
        var ctx = testContext();
        return new JooqCatalog(ctx.jooqPackage(), ctx.codegenLoader());
    }
}
