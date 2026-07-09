package no.sikt.graphitron.lsp;

import no.sikt.graphitron.lsp.definition.Definitions;
import no.sikt.graphitron.lsp.hover.Hovers;
import no.sikt.graphitron.lsp.parsing.LspVocabulary;
import no.sikt.graphitron.lsp.state.Workspace;
import no.sikt.graphitron.lsp.state.FileSnapshot;
import no.sikt.graphitron.lsp.state.WorkspaceFileTestSupport;
import no.sikt.graphitron.rewrite.catalog.CompletionData;
import io.github.treesitter.jtreesitter.Point;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end coverage of the R349/R352 decoupling through the real pieces: a
 * real {@link Workspace} owns and walks real {@code .java} files with the real
 * {@code SourceWalker} ({@link Workspace#refreshSourceIndex}), and the real
 * {@link Hovers} and {@link Definitions} entry points resolve against the
 * resulting index. Nothing here is mocked: the positions and Javadoc come from
 * parsing actual source on disk.
 *
 * <p>The catalog fixtures carry only the build-derivable structure ({@code FQN}s,
 * method signatures, empty descriptions), exactly as {@code CatalogBuilder} now
 * produces it; the Javadoc and positions the assertions check come solely from
 * the walked sources. The decisive property is that hover and goto-definition
 * read the <em>same</em> declaration from the <em>same</em> index, so a source
 * edit moves both together and they cannot disagree.
 */
class SourceCadenceHoverAndDefinitionTest {

    private static final String SVC_FQN = "com.example.PriceService";
    private static final String FILM_FQN = "fake.jooq.tables.Film";

    @Test
    void serviceMethodHoverAndGotoBothReadTheWalkedSource(@TempDir Path srcRoot) throws IOException {
        writeJava(srcRoot, "com/example/PriceService.java", """
            package com.example;
            /** Computes prices. */
            public class PriceService {

                /** Looks up a price for a film. */
                public Object price(Object table) {
                    return null;
                }
            }
            """);
        var workspace = workspaceWithServiceCatalog();
        workspace.refreshSourceIndex(List.of(srcRoot));

        var file = file("""
            type Query {
                films: Int @service(service: {className: "com.example.PriceService", method: "price"})
            }
            """);
        var methodPos = pointAt(file, 1, "price\"");

        // Hover surfaces the method Javadoc from the walked source (the catalog
        // method carries an empty description), not from the catalog.
        var hover = Hovers.compute(LspVocabulary.load(), file, workspace.catalog(),
            workspace.sourceIndex(), workspace.snapshot(), methodPos, false).orElseThrow();
        assertThat(hover.getContents().getRight().getValue())
            .contains("Looks up a price for a film.");

        // Goto-definition jumps to the same method declaration in the same file.
        var loc = Definitions.compute(LspVocabulary.load(), file, workspace.catalog(),
            workspace.sourceIndex(), workspace.snapshot(), methodPos).orElseThrow();
        assertThat(loc.getUri()).endsWith("PriceService.java");
        // The method is declared on the 6th line (0-based line 5) of the source above.
        assertThat(loc.getRange().getStart().getLine()).isEqualTo(5);
    }

    @Test
    void tableHoverAndGotoBothReadTheWalkedGeneratedSource(@TempDir Path srcRoot) throws IOException {
        writeJava(srcRoot, "fake/jooq/tables/Film.java", """
            package fake.jooq.tables;
            /** The film table. */
            public class Film {
                public final Object FILM_ID = null;
            }
            """);
        var workspace = workspaceWithTableCatalog();
        workspace.refreshSourceIndex(List.of(srcRoot));

        var file = file("type Foo @table(name: \"film\") { bar: Int }");
        var tablePos = pointAt(file, 0, "film\"");

        // Hover falls back from the (empty) catalog description to the generated
        // class Javadoc the walk recovered.
        var hover = Hovers.compute(LspVocabulary.load(), file, workspace.catalog(),
            workspace.sourceIndex(), workspace.snapshot(), tablePos, false).orElseThrow();
        assertThat(hover.getContents().getRight().getValue()).contains("The film table.");

        var loc = Definitions.compute(LspVocabulary.load(), file, workspace.catalog(),
            workspace.sourceIndex(), workspace.snapshot(), tablePos).orElseThrow();
        assertThat(loc.getUri()).endsWith("Film.java");
        // The class is declared on line 3 (0-based line 2).
        assertThat(loc.getRange().getStart().getLine()).isEqualTo(2);
    }

    @Test
    void aSourceEditMovesHoverAndGotoTogetherWithoutACatalogRebuild(@TempDir Path srcRoot) throws IOException {
        Path source = writeJava(srcRoot, "com/example/PriceService.java", """
            package com.example;
            public class PriceService {
                /** First doc. */
                public Object price(Object table) { return null; }
            }
            """);
        var workspace = workspaceWithServiceCatalog();
        var catalogBefore = workspace.catalog();
        workspace.refreshSourceIndex(List.of(srcRoot));

        var file = file("""
            type Query {
                films: Int @service(service: {className: "com.example.PriceService", method: "price"})
            }
            """);
        var methodPos = pointAt(file, 1, "price\"");

        int lineBefore = Definitions.compute(LspVocabulary.load(), file, workspace.catalog(),
            workspace.sourceIndex(), workspace.snapshot(), methodPos).orElseThrow()
            .getRange().getStart().getLine();
        assertThat(hoverText(workspace, file, methodPos)).contains("First doc.");

        // Edit the source: new Javadoc, and the method shifts down two lines.
        Files.writeString(source, """
            package com.example;
            public class PriceService {


                /** Second doc, moved down. */
                public Object price(Object table) { return null; }
            }
            """);
        Files.setLastModifiedTime(source, java.nio.file.attribute.FileTime.fromMillis(
            Files.getLastModifiedTime(source).toMillis() + 5000));

        // Only the source index is refreshed; the catalog is the same instance.
        workspace.refreshSourceIndex(List.of(srcRoot));
        assertThat(workspace.catalog())
            .as("source-cadence refresh must not rebuild the catalog")
            .isSameAs(catalogBefore);

        int lineAfter = Definitions.compute(LspVocabulary.load(), file, workspace.catalog(),
            workspace.sourceIndex(), workspace.snapshot(), methodPos).orElseThrow()
            .getRange().getStart().getLine();

        // Hover and goto move together: the new Javadoc and the new line both come
        // from the one refreshed index, so they cannot show two different snapshots.
        assertThat(lineAfter).isGreaterThan(lineBefore);
        assertThat(hoverText(workspace, file, methodPos)).contains("Second doc, moved down.");
    }

    private static String hoverText(Workspace workspace, FileSnapshot file, Point pos) {
        return Hovers.compute(LspVocabulary.load(), file, workspace.catalog(),
            workspace.sourceIndex(), workspace.snapshot(), pos, false)
            .orElseThrow().getContents().getRight().getValue();
    }

    private static Workspace workspaceWithServiceCatalog() {
        var price = new CompletionData.Method(
            "price", "Object", "",
            List.of(new CompletionData.Parameter("table", "Object", "Table", "")));
        var ref = new CompletionData.ExternalReference(SVC_FQN, SVC_FQN, "", List.of(price), List.of());
        return new Workspace(new CompletionData(List.of(), List.of(), List.of(ref)));
    }

    private static Workspace workspaceWithTableCatalog() {
        var film = new CompletionData.Table(
            "film", "", FILM_FQN,
            List.of(new CompletionData.Column("FILM_ID", "Integer", false, "")),
            List.of());
        return new Workspace(new CompletionData(List.of(film), List.of(), List.of()));
    }

    private static FileSnapshot file(String source) {
        return WorkspaceFileTestSupport.snapshot(source);
    }

    private static Point pointAt(FileSnapshot file, int line, String token) {
        String source = new String(file.source(), StandardCharsets.UTF_8);
        String[] lines = source.split("\n", -1);
        int col = lines[line].indexOf(token);
        if (col < 0) {
            throw new AssertionError("token '" + token + "' not on line " + line + ": " + lines[line]);
        }
        // Land inside the token (just past its first char), before the closing quote.
        return new Point(line, col + 1);
    }

    private static Path writeJava(Path root, String relative, String content) throws IOException {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
        return file;
    }
}
