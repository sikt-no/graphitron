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
    private static final String ROUTINE_RESULT = "scenarios/routine-result";
    private static final String KEY_ROWS = "scenarios/field-key-row";
    private static final String INTERFACES = "scenarios/multitable-interface";
    private static final String PATH_WITHOUT_TABLE = "defects/reference-without-target-table";

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
    @DisplayName("a plain type a root @routine returns is read from the function's rows, and the field targets them alone")
    void aRoutinesPlainTypeIsReadFromItsFunctionsRows() {
        // A plain type reached both as a routine's row and as a nested row is refused by the walk's
        // classifier today, so only the routine half is pinned here.
        var type = file("""
            type Tilgang {
              organisasjonskode: Int
              rollekode: String
            }
            """);
        assertThat(hoverAt(ROUTINE_RESULT, type, 0, "type Til".length()))
            .contains("**Is read from** a row of `public.tilganger_for_feidebruker_med_fs_fiktivt_fnr`");

        var root = file("""
            type Query {
                tilganger(env: String!, serviceId: String!, feideId: String!): [Tilgang!]!
                    @routine(name: "tilganger_for_feidebruker_med_fs_fiktivt_fnr",
                             argMapping: "pEnv: env, pServiceId: serviceId, pFeideId: feideId")
                    @defaultOrder(fields: [{name: "organisasjonskode"}, {name: "rollekode"}])
            }
            """);
        assertThat(hoverAt(ROUTINE_RESULT, root, 1, "    tilg".length()))
            .contains("**Target** `Tilgang` → `public.tilganger_for_feidebruker_med_fs_fiktivt_fnr`");
        assertThat(labels(ROUTINE_RESULT, root)).containsExactly("→ tilganger_for_feidebruker_med_fs_fiktivt_fnr");
    }

    @Test
    @DisplayName("an @error type is read from nothing its hover names, an errors field having no source")
    void anErrorTypeShowsNoSource() {
        var file = file("""
            type FilmInvalid @error(handlers: [{handler: DATABASE}]) { path: [String!]!, message: String! }
            """);
        assertThat(hoverAt(SOURCES, file, 0, "type FilmInv".length()))
            .contains("`type FilmInvalid`")
            .doesNotContain("**Is read from**");
    }

    @Test
    @DisplayName("a @pivot type is read from the table its slots read, the path's last")
    void aPivotTypeIsReadFromItsSlotsTable() {
        var file = file("""
            type TranslatedTexts { nn: String en: String }
            """);
        assertThat(hoverAt(SOURCES, file, 0, "type Translat".length()))
            .contains("**Is read from** a row of `public.film_translation`")
            .doesNotContain("`public.film`");
        assertThat(labels(SOURCES, file)).first().isEqualTo("film_translation");
    }

    @Test
    @DisplayName("a plain type behind a written path is read from where the path arrives, not its parent's table")
    void aPlainTypeBehindAPathIsReadFromThePathsTarget() {
        // The schema is refused, REFERENCE_WITHOUT_TARGET_TABLE at Film.summary; the editor answers on it
        // all the same.
        var file = file("""
            type Summary { name: String }
            """);
        assertThat(hoverAt(PATH_WITHOUT_TABLE, file, 0, "type Summ".length()))
            .contains("**Is read from** a row of `public.language`")
            .doesNotContain("`public.film`");
    }

    @Test
    @DisplayName("a nested class is named as an author writes it, Outer.Inner, though the store keeps its binary name")
    void aNestedClassIsNamedAsWritten() {
        var file = file("""
            type NestedFilmsPayload {
                films: [Film!]!
            }
            """);
        assertThat(hoverAt(KEY_ROWS, file, 0, "type NestedFilms".length()))
            .contains("**Is read from** an instance of "
                + "`no.sikt.graphitron.rewrite.test.services.NestedFilmsPayloadHolder.Payload`")
            .doesNotContain("$");
        assertThat(labels(KEY_ROWS, file)).first().isEqualTo("NestedFilmsPayloadHolder.Payload");
    }

    @Test
    @DisplayName("an interface no table backs is read from nothing its hover names")
    void aNonTableInterfaceShowsNoSource() {
        var file = file("""
            interface Searchable {
                title: String
            }
            """);
        assertThat(hoverAt(INTERFACES, file, 0, "interface Searc".length()))
            .contains("`interface Searchable`")
            .doesNotContain("**Is read from**");
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
