package no.sikt.graphitron.lsp;

import no.sikt.graphitron.lsp.definition.DeclarationDefinitions;
import no.sikt.graphitron.lsp.definition.Definitions;
import no.sikt.graphitron.lsp.hover.Hovers;
import no.sikt.graphitron.lsp.parsing.LspVocabulary;
import no.sikt.graphitron.lsp.state.Workspace;
import no.sikt.graphitron.lsp.state.FileSnapshot;
import no.sikt.graphitron.lsp.state.WorkspaceFileTestSupport;
import no.sikt.graphitron.rewrite.catalog.CompletionData;
import no.sikt.graphitron.rewrite.catalog.LspSchemaSnapshot;
import no.sikt.graphitron.rewrite.catalog.TypeBackingShape;
import io.github.treesitter.jtreesitter.Point;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end coverage of the source-cadence decoupling through the real pieces: real {@code .java}
 * files on disk, a real capture of them into the store's {@code java_} family, and the real
 * {@link Hovers} and {@link Definitions} entry points resolving against it. Nothing here is mocked:
 * the positions and Javadoc come from parsing actual source on disk.
 *
 * <p>The cadence is the subject. A {@code .java} edit moves the doc comment hover renders and the
 * position definition jumps to, together and with no catalog rebuild in between, because one walk
 * writes the family both surfaces read and neither of them consults the catalog for a position. The
 * catalog fixtures carry only the build-derivable structure ({@code FQN}s, method signatures, empty
 * descriptions), so any Javadoc or position in an assertion came from a parse.
 */
class SourceCadenceHoverAndDefinitionTest {

    private static final String SVC_FQN = "com.example.PriceService";

    /** The graph is beside the point in every case here; the subject is the {@code .java} files. */
    private static final String PLACEHOLDER_SDL = "type Query { placeholder: Int }\n";

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
            var loc = Definitions.compute(LspVocabulary.load(), file, store.handle(), methodPos)
                .orElseThrow();
            assertThat(loc.getUri()).endsWith("PriceService.java");
            // The method is declared on the 6th line (0-based line 5) of the source above.
            assertThat(loc.getRange().getStart().getLine()).isEqualTo(5);
        }
    }

    @Test
    void tableHoverAndGotoBothReadTheWalkedGeneratedSource(@TempDir Path srcRoot) throws IOException {
        var file = file("type Foo @table(name: \"film\") { bar: Int }");
        var tablePos = pointAt(file, 0, "film\"");

        try (var store = StoreFixture.ofCatalog(srcRoot, PLACEHOLDER_SDL)) {
            // The generated table class, written under the FQN the catalog walk actually captured
            // rather than a spelled-out one, so the join both readers make is a real one.
            String filmFqn = store.tableClassFqn("film");
            store.withJavaSource(srcRoot, filmFqn, """
                /** The film table. */
                public class Film {
                    public final Object FILM_ID = null;
                }
                """);
            var workspace = workspaceWithTableCatalog(filmFqn);

            // Hover's description is the generated class's Javadoc, reached from the store's
            // catalog census through the FQN into its java-source family; the fixture database
            // carries no comment, so a parse is the only thing that can have supplied it.
            assertThat(hoverText(workspace, store, file, tablePos)).contains("The film table.");

            var loc = Definitions.compute(LspVocabulary.load(), file, store.handle(), tablePos)
                .orElseThrow();
            assertThat(loc.getUri()).endsWith("Film.java");
            // The class is declared on line 3 (0-based line 2).
            assertThat(loc.getRange().getStart().getLine()).isEqualTo(2);
        }
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

        var file = file("""
            type Query {
                films: Int @service(service: {className: "com.example.PriceService", method: "price"})
            }
            """);
        var methodPos = pointAt(file, 1, "price\"");

        try (var store = priceServiceStore(srcRoot)) {
            int lineBefore = Definitions.compute(LspVocabulary.load(), file, store.handle(), methodPos)
                .orElseThrow().getRange().getStart().getLine();
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

            // The edited file is re-read, and nothing else is; the catalog is the same instance it
            // was before.
            store.refreshJavaSources(srcRoot);
            assertThat(workspace.catalog())
                .as("source-cadence refresh must not rebuild the catalog")
                .isSameAs(catalogBefore);

            int lineAfter = Definitions.compute(LspVocabulary.load(), file, store.handle(), methodPos)
                .orElseThrow().getRange().getStart().getLine();

            // Hover and goto move together off one edit: the new doc comment and the new line come
            // out of one re-read of the file, so the two cannot disagree about the declaration.
            assertThat(lineAfter).isGreaterThan(lineBefore);
            assertThat(hoverText(workspace, store, file, methodPos)).contains("Second doc, moved down.");
        }
    }

    /**
     * The declaration-name arm, whose two halves ask one parsed declaration two questions: hover
     * overlays its doc comment, goto jumps to its position. The type name is the only handle either
     * surface has here, the coordinate being a declaration rather than a directive argument.
     */
    @Test
    void declarationNameHoverAndGotoBothReadTheParsedDeclaration(@TempDir Path srcRoot) {
        var file = file("type Film @table(name: \"film\") { title: String }");
        // Cursor on the 'i' of the Film declaration's own name token.
        var namePos = new Point(0, "type Fi".length());

        try (var store = StoreFixture.ofCatalog(srcRoot, PLACEHOLDER_SDL)) {
            String filmFqn = store.tableClassFqn("film");
            store.withJavaSource(srcRoot, filmFqn, """
                /** The film table. */
                public class Film {
                    public final Object TITLE = null;
                }
                """);
            var workspace = workspaceWithTableCatalog(filmFqn);
            var snapshot = new LspSchemaSnapshot.Built.Current(
                List.of(), Map.of("Film", new TypeBackingShape.TableBacking("film")),
                Map.of(), Map.of(), Map.of());

            assertThat(hoverText(workspace, store, file, namePos, snapshot, true))
                .contains("The film table.");

            var loc = DeclarationDefinitions
                .compute(file, store.handle(), snapshot, namePos)
                .orElseThrow();
            assertThat(loc.getUri()).endsWith("Film.java");
            assertThat(loc.getRange().getStart().getLine()).isEqualTo(2);
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
        return hoverText(workspace, store, file, pos, workspace.snapshot(), false);
    }

    private static String hoverText(
        Workspace workspace, StoreFixture store, FileSnapshot file, Point pos,
        LspSchemaSnapshot snapshot, boolean declarationNames
    ) {
        return Hovers.compute(LspVocabulary.load(), file, workspace.catalog(),
            Optional.of(store.handle()), snapshot, pos, declarationNames)
            .orElseThrow().getContents().getRight().getValue();
    }

    private static Workspace workspaceWithServiceCatalog() {
        var price = new CompletionData.Method(
            "price", "Object", "",
            List.of(new CompletionData.Parameter("table", "Object", "Table", "")));
        var ref = new CompletionData.ExternalReference(SVC_FQN, SVC_FQN, "", List.of(price), List.of());
        return new Workspace(new CompletionData(List.of(), List.of(), List.of(ref)));
    }

    /** The projection hover still reads for a table: its name and the FQN it was captured under. */
    private static Workspace workspaceWithTableCatalog(String filmFqn) {
        var film = new CompletionData.Table(
            "film", "", filmFqn,
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
