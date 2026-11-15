package no.sikt.graphitron.lsp;

import io.github.treesitter.jtreesitter.Point;
import no.sikt.graphitron.lsp.hover.DeclarationHovers;
import no.sikt.graphitron.lsp.inlay.InlayHints;
import no.sikt.graphitron.lsp.state.FileSnapshot;
import no.sikt.graphitron.lsp.state.InlayHintConfig;
import no.sikt.graphitron.lsp.state.WorkspaceFileTestSupport;
import no.sikt.graphitron.model.test.FactGraphs;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The type hover, the type's inlay label and a root field's target tables say what the generator
 * reads a type's fields from, the sources of their occurrences, rather than a result the type holds
 * whoever reaches it. Each case is one where the two answers part.
 */
class OccurrenceSourceReadersTest {

    @RegisterExtension
    static final FactGraphs.Reader FACTS = FactGraphs.reader();

    private static final String UNREACHED = "scenarios/field-path-unreached";
    private static final String SOURCES = "scenarios/occurrence-source";
    private static final String CONNECTIONS = "scenarios/connection-producers";
    private static final String TWO_TABLES = "scenarios/occurrence-source-two-tables";

    @Test
    @DisplayName("a table-bound type no field returns is read from its table")
    void aTableTypeNothingReturnsIsReadFromItsTable() {
        var file = file("""
            type Film @table(name: "film") {
                title: String
            }
            """);
        assertThat(hoverAt(UNREACHED, file, 0, "type Fi".length()))
            .contains("**Is read from** a row of `public.film`");
        assertThat(labels(UNREACHED, file)).first().isEqualTo("film");
    }

    @Test
    @DisplayName("a connection graphitron builds is read from nothing its hover names")
    void aConnectionShowsNoSource() {
        var file = file("""
            type FilmsConnection { edges: [FilmsEdge!]! nodes: [Film!]! pageInfo: PageInfo! totalCount: Int }
            """);
        assertThat(hoverAt(CONNECTIONS, file, 0, "type FilmsCon".length()))
            .contains("`type FilmsConnection`")
            .doesNotContain("**Is read from**")
            .doesNotContain("graphitron builds");
    }

    @Test
    @DisplayName("a write's payload whose field is table-bound is read from that table")
    void aWritePayloadIsReadFromItsFieldsTable() {
        var file = file("""
            type AddFilmOutcome { film: Film, errors: [FilmFailure] }
            """);
        assertThat(hoverAt(SOURCES, file, 0, "type AddFilmOut".length()))
            .contains("**Is read from** a row of `public.film`")
            .doesNotContain("an instance of")
            .doesNotContain("graphitron builds");
        assertThat(labels(SOURCES, file)).first().isEqualTo("film");
    }

    @Test
    @DisplayName("a plain type a service returns as a table's jOOQ record is read from that table")
    void aServiceRecordIsReadFromItsTable() {
        var file = file("""
            type FilmDetailsCarrier {
                filmId: Int @field(name: "film_id")
            }
            """);
        assertThat(hoverAt(SOURCES, file, 0, "type FilmDetailsCar".length()))
            .contains("**Is read from** a row of `public.film`")
            .doesNotContain("an instance of");
        assertThat(labels(SOURCES, file)).first().isEqualTo("film");
    }

    @Test
    @DisplayName("a type two fields reach from two tables is read from both, and each field from its own")
    void aTypeTwoFieldsReachListsOnlyThisFieldsTable() {
        var type = file("""
            type Row { title: String }
            """);
        assertThat(hoverAt(TWO_TABLES, type, 0, "type Ro".length()))
            .contains("**Is read from** a row of `public.film`")
            .contains("**Is read from** a row of `public.films_for_actor`");

        var root = file("""
            type Query {
                rows(actorId: Int!, minLength: Int!): [Row!]!
                    @routine(name: "films_for_actor", argMapping: "pActorId: actorId, pMinLength: minLength")
                    @defaultOrder(fields: [{name: "film_id"}])
            }
            """);
        assertThat(hoverAt(TWO_TABLES, root, 1, "    row".length()))
            .contains("**Target** `Row` → `public.films_for_actor`")
            .doesNotContain("`public.film`");
        assertThat(labels(TWO_TABLES, root)).containsExactly("→ films_for_actor");
    }

    // ===== Helpers =====

    private static String hoverAt(String document, FileSnapshot file, int line, int column) {
        return FACTS.read(document, graph -> DeclarationHovers.compute(
                file, Optional.of(graph.handle()), new Point(line, column))
            .map(h -> h.getContents().getRight().getValue()))
            .orElseThrow();
    }

    /** The label arm alone. */
    private static List<String> labels(String document, FileSnapshot file) {
        var visible = new Range(new Position(0, 0), new Position(10_000, 0));
        return FACTS.read(document, graph -> InlayHints.compute(new InlayHintConfig(false, true, false, false),
                file, Optional.of(graph.handle()), visible))
            .stream()
            .map(hint -> {
                var either = hint.getLabel();
                return either.isLeft() ? either.getLeft() : either.getRight().toString();
            })
            .toList();
    }

    private static FileSnapshot file(String source) {
        return WorkspaceFileTestSupport.snapshot(source);
    }
}
