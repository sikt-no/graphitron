package no.sikt.graphitron.lsp;

import no.sikt.graphitron.lsp.definition.Definitions;
import no.sikt.graphitron.lsp.hover.Hovers;
import no.sikt.graphitron.lsp.parsing.LspVocabulary;
import no.sikt.graphitron.lsp.state.Workspace;
import no.sikt.graphitron.lsp.state.FileSnapshot;
import no.sikt.graphitron.lsp.state.WorkspaceFileTestSupport;
import no.sikt.graphitron.rewrite.catalog.CompletionData;
import no.sikt.graphitron.rewrite.catalog.SourceWalker;
import io.github.treesitter.jtreesitter.Point;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end coverage of the source-cadence decoupling through the real pieces: real {@code .java}
 * files on disk, a real {@link SourceWalker} parse behind the index a dev session hands a
 * {@link Workspace}, a real capture of the same files into the store's {@code java_} family, and the
 * real {@link Hovers} and {@link Definitions} entry points resolving against them. Nothing here is
 * mocked: the positions and Javadoc come from parsing actual source on disk.
 *
 * <p>The two surfaces read two different things during the migration, and that is what these cases
 * are for. Hover's method arm reads the store; goto-definition still reads the index. Both are
 * refreshed from one file by one edit, so the property the earlier shared index gave for free now has
 * to be asserted: a source edit moves the doc comment hover renders and the position definition jumps
 * to, together, with no catalog rebuild in between. The catalog fixtures carry only the
 * build-derivable structure ({@code FQN}s, method signatures, empty descriptions), so any Javadoc or
 * position in an assertion came from a parse.
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
        workspace.setSourceIndex(new SourceWalker().walk(List.of(srcRoot)));

        var file = file("""
            type Query {
                films: Int @service(service: {className: "com.example.PriceService", method: "price"})
            }
            """);
        var methodPos = pointAt(file, 1, "price\"");

        try (var store = priceServiceStore(srcRoot)) {
            // Hover surfaces the method Javadoc the parse read into the store's java-source family;
            // the classpath census it joins to carries none by design.
            assertThat(hoverText(workspace, store, file, methodPos))
                .contains("Looks up a price for a film.");

            // Goto-definition jumps to the same method declaration in the same file.
            var loc = Definitions.compute(LspVocabulary.load(), file, workspace.catalog(),
                workspace.sourceIndex(), workspace.snapshot(), methodPos).orElseThrow();
            assertThat(loc.getUri()).endsWith("PriceService.java");
            // The method is declared on the 6th line (0-based line 5) of the source above.
            assertThat(loc.getRange().getStart().getLine()).isEqualTo(5);
        }
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
        workspace.setSourceIndex(new SourceWalker().walk(List.of(srcRoot)));

        var file = file("type Foo @table(name: \"film\") { bar: Int }");
        var tablePos = pointAt(file, 0, "film\"");

        // Hover falls back from the (empty) catalog description to the generated
        // class Javadoc the walk recovered. Still the projection's index: the table arm has not
        // migrated, so there is no store read on this path to give one.
        var hover = Hovers.compute(LspVocabulary.load(), file, workspace.catalog(), Optional.empty(),
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
        workspace.setSourceIndex(new SourceWalker().walk(List.of(srcRoot)));

        var file = file("""
            type Query {
                films: Int @service(service: {className: "com.example.PriceService", method: "price"})
            }
            """);
        var methodPos = pointAt(file, 1, "price\"");

        try (var store = priceServiceStore(srcRoot)) {
            int lineBefore = Definitions.compute(LspVocabulary.load(), file, workspace.catalog(),
                workspace.sourceIndex(), workspace.snapshot(), methodPos).orElseThrow()
                .getRange().getStart().getLine();
            assertThat(hoverText(workspace, store, file, methodPos)).contains("First doc.");

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

            // Both readers of the edited file are refreshed, and nothing else is; the catalog is the
            // same instance it was before.
            workspace.setSourceIndex(new SourceWalker().walk(List.of(srcRoot)));
            store.refreshJavaSources(srcRoot);
            assertThat(workspace.catalog())
                .as("source-cadence refresh must not rebuild the catalog")
                .isSameAs(catalogBefore);

            int lineAfter = Definitions.compute(LspVocabulary.load(), file, workspace.catalog(),
                workspace.sourceIndex(), workspace.snapshot(), methodPos).orElseThrow()
                .getRange().getStart().getLine();

            // Hover and goto move together off one edit: the new doc comment comes from the store's
            // parse of the file and the new line from the index's, and the two cannot disagree about
            // a declaration they both just read.
            assertThat(lineAfter).isGreaterThan(lineBefore);
            assertThat(hoverText(workspace, store, file, methodPos)).contains("Second doc, moved down.");
        }
    }

    /**
     * The census and the parse for {@code PriceService}, captured from the file the test wrote: the
     * classpath side is what makes the method resolvable, the parse side is where its doc comment
     * comes from, and hover needs both.
     */
    private static StoreFixture priceServiceStore(Path srcRoot) {
        var store = StoreFixture.ofClasspath(srcRoot, List.of(StoreFixture.jarClass(SVC_FQN,
            List.of(StoreFixture.method("price", "Object", StoreFixture.parameter("table", "Object"))))));
        store.refreshJavaSources(srcRoot);
        return store;
    }

    private static String hoverText(
        Workspace workspace, StoreFixture store, FileSnapshot file, Point pos
    ) {
        return Hovers.compute(LspVocabulary.load(), file, workspace.catalog(),
            Optional.of(store.handle()), workspace.sourceIndex(), workspace.snapshot(), pos, false)
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
