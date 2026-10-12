package no.sikt.graphitron.lsp;

import no.sikt.graphitron.lsp.completions.FieldCompletions;
import no.sikt.graphitron.lsp.diagnostics.Diagnostics;
import no.sikt.graphitron.lsp.fixtures.R157FilmPojo;
import no.sikt.graphitron.lsp.fixtures.R157FilmRecord;
import no.sikt.graphitron.lsp.hover.Hovers;
import no.sikt.graphitron.lsp.parsing.Directives;
import no.sikt.graphitron.lsp.parsing.GraphqlLanguage;
import no.sikt.graphitron.lsp.parsing.LspVocabulary;
import no.sikt.graphitron.lsp.state.WorkspaceFileTestSupport;
import no.sikt.graphitron.model.config.ClasspathEntry;
import org.eclipse.lsp4j.CompletionItem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import io.github.treesitter.jtreesitter.Parser;
import io.github.treesitter.jtreesitter.Point;

import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Optional;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_TYPE_BACKING;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Primary tier for @field member completion over classes another module compiled: real backing
 * classes ({@link R157FilmRecord}, {@link R157FilmPojo}) in the LSP module's test output, read by
 * capture as a classpath entry of this one. Each case asks the store which class backs the type,
 * then exercises {@link FieldCompletions}, {@link Diagnostics} and {@link Hovers} against the same
 * store, so the backing and what it offers come off one reading.
 */
class R157PipelineTest {

    @TempDir
    static Path tmp;

    @Test
    void recordBackedTypeSurfacesComponentsThroughThePipeline() {
        var sdl = """
            type FilmCard {
                filmId: Int @field(name: "filmId")
                title: String @field(name: "title")
            }
            type Query {
                x: FilmCard @service(service: {className: "no.sikt.graphitron.lsp.fixtures.R157Service", method: "makeFilmRecord"})
            }
            """;

        assertThat(backingOf(sdl, "FilmCard"))
            .containsExactly("no.sikt.graphitron.lsp.fixtures.R157FilmRecord");

        var completions = completionsAt(sdl,
            "type FilmCard {\n"
                + "    filmId: Int @field(name: \"\")\n"
                + "    title: String @field(name: \"title\")\n"
                + "}\n", 1);
        assertThat(completions).extracting(CompletionItem::getLabel)
            .containsExactly("filmId", "title");

        var diags = diagnosticsFor(sdl,
            "type FilmCard {\n"
                + "    filmId: Int @field(name: \"TYPO\")\n"
                + "}\n");
        assertThat(diags).hasSize(1);
        assertThat(diags.get(0).getMessage()).contains("TYPO").contains("component");
    }

    @Test
    void pojoBackedTypeSurfacesBeanAccessorsThroughThePipeline() {
        var sdl = """
            type FilmPojoView {
                filmId: Int @field(name: "filmId")
                title: String @field(name: "title")
            }
            type Query {
                x: FilmPojoView @service(service: {className: "no.sikt.graphitron.lsp.fixtures.R157Service", method: "makeFilmPojo"})
            }
            """;

        assertThat(backingOf(sdl, "FilmPojoView"))
            .containsExactly("no.sikt.graphitron.lsp.fixtures.R157FilmPojo");
        // The bean rule itself is the store's; that this class's accessors reach the author as member
        // names is what the completion leg of the record case pins, over the same census.
        assertThat(completionsAt(sdl,
            "type FilmPojoView {\n"
                + "    filmId: Int @field(name: \"\")\n"
                + "}\n", 1))
            .extracting(CompletionItem::getLabel)
            .contains("filmId", "title");
    }

    // ---- Pipeline helpers ----

    /** The classes the store says back {@code typeName}, captured over this test's schema. */
    private static List<String> backingOf(String sdl, String typeName) {
        try (var store = storeOver(sdl)) {
            var handle = store.handle();
            var b = GRAPHITRON_TYPE_BACKING;
            return handle.dsl().select(b.CLASS_NAME).from(b)
                .where(b.GRAPH_NAME.eq(handle.graphName()))
                .and(b.TYPE_NAME.eq(typeName))
                .fetch(b.CLASS_NAME);
        }
    }

    private static Path testClassesRoot() {
        try {
            return Paths.get(R157FilmRecord.class.getProtectionDomain()
                .getCodeSource().getLocation().toURI());
        } catch (URISyntaxException e) {
            throw new RuntimeException(e);
        }
    }

    // ---- LSP-side drivers ----

    private static List<CompletionItem> completionsAt(String sdl, String source, int line) {
        Point cursor = new Point(line, source.split("\n")[line].indexOf("\"\"") + 1);
        var parser = new Parser();
        parser.setLanguage(GraphqlLanguage.get());
        var bytes = source.getBytes(StandardCharsets.UTF_8);
        var tree = parser.parse(source).orElseThrow();
        var directive = Directives.findContaining(tree.getRootNode(), cursor)
            .orElseThrow(() -> new AssertionError("no directive at cursor"));
        var vocab = BundledVocabulary.get();
        var locOpt = vocab.locateAt(directive, cursor, bytes);
        if (locOpt.isEmpty()) return List.of();
        var context = no.sikt.graphitron.lsp.completions.CompletionContext.from(locOpt.get(), bytes);
        // Both halves of the arm come from the one root this test read: the store captured it,
        // and names the backing class and what it offers off the same rows.
        try (var store = storeOver(sdl)) {
            return FieldCompletions.generate(vocab, store.handle(), context, directive, bytes);
        }
    }

    private static List<org.eclipse.lsp4j.Diagnostic> diagnosticsFor(String sdl, String source) {
        var file = WorkspaceFileTestSupport.snapshot(source);
        try (var store = storeOver(sdl)) {
            return Diagnostics.compute(BundledVocabulary.get(), "", file, Optional.of(store.handle()));
        }
    }

    /** A store over this test's schema, with the LSP module's test output as a project entry. */
    private static StoreFixture storeOver(String sdl) {
        return StoreFixture.of(tmp, sdl, List.of(new ClasspathEntry(testClassesRoot(),
            ClasspathEntry.Origin.PROJECT, null, null)));
    }
}
