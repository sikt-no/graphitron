package no.sikt.graphitron.lsp;

import no.sikt.graphitron.lsp.definition.DeclarationDefinitions;
import no.sikt.graphitron.lsp.definition.Definitions;
import no.sikt.graphitron.lsp.hover.Hovers;
import no.sikt.graphitron.lsp.parsing.LspVocabulary;
import no.sikt.graphitron.lsp.state.Workspace;
import no.sikt.graphitron.lsp.state.FileSnapshot;
import no.sikt.graphitron.lsp.state.WorkspaceFileTestSupport;
import no.sikt.graphitron.rewrite.catalog.LspSchemaSnapshot;
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

    /**
     * The graph the declaration-name case needs, which is the one case where it is not beside the
     * point: the type name that case puts a cursor on resolves through the store's own binding.
     */
    private static final String DECLARED_ACTOR_SDL = """
        type Query { actor: Actor }
        type Actor @table(name: "actor") { first_name: String }
        """;

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
        var workspace = new Workspace();

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

    /**
     * Asserted on {@code actor}, which the fixture database declares no comment on, and that is the
     * subject rather than an arbitrary table: at the table grain the database comment wins over the
     * generated class's Javadoc, so on a commented table hover reports the comment and reads nothing
     * the source cadence produced. A commentless table is what leaves the parse as the only thing
     * that can have supplied the text, which is the whole claim here.
     */
    @Test
    void tableHoverAndGotoBothReadTheWalkedGeneratedSource(@TempDir Path srcRoot) throws IOException {
        var file = file("type Foo @table(name: \"actor\") { bar: Int }");
        var tablePos = pointAt(file, 0, "actor\"");

        try (var store = StoreFixture.ofCatalog(srcRoot, PLACEHOLDER_SDL)) {
            // The generated table class, written under the FQN the catalog walk actually captured
            // rather than a spelled-out one, so the join both readers make is a real one.
            String actorFqn = store.tableClassFqn("actor");
            store.withJavaSource(srcRoot, actorFqn, """
                /** The actor table. */
                public class Actor {
                    public final Object ACTOR_ID = null;
                }
                """);
            var workspace = new Workspace();

            // Hover's description is the generated class's Javadoc, reached from the store's
            // catalog census through the FQN into its java-source family; the database declares no
            // comment on actor, so a parse is the only thing that can have supplied it.
            assertThat(hoverText(workspace, store, file, tablePos)).contains("The actor table.");

            var loc = Definitions.compute(LspVocabulary.load(), file, store.handle(), tablePos)
                .orElseThrow();
            assertThat(loc.getUri()).endsWith("Actor.java");
            // The class is declared on line 3 (0-based line 2).
            assertThat(loc.getRange().getStart().getLine()).isEqualTo(2);
        }
    }

    @Test
    void aSourceEditMovesHoverAndGotoTogetherWithoutAGeneratorPass(@TempDir Path srcRoot) throws IOException {
        Path source = writeJava(srcRoot, "com/example/PriceService.java", """
            package com.example;
            public class PriceService {
                /** First doc. */
                public Object price(Object table) { return null; }
            }
            """);
        var workspace = new Workspace();
        var snapshotBefore = workspace.snapshot();

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

            // The edited file is re-read, and nothing else is; no generator pass ran, which the
            // workspace states by still holding the build output it held before.
            store.refreshJavaSources(srcRoot);
            assertThat(workspace.snapshot())
                .as("source-cadence refresh must not run a generator pass")
                .isSameAs(snapshotBefore);

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
     *
     * <p>Bound to {@code actor} for the reason the table-hover case above gives: the overlay's table
     * arm prefers the database comment, so only a commentless table lets a parsed doc comment be the
     * text under assertion.
     */
    @Test
    void declarationNameHoverAndGotoBothReadTheParsedDeclaration(@TempDir Path srcRoot) {
        var file = file("type Actor @table(name: \"actor\") { first_name: String }");
        // Cursor on the 'c' of the Actor declaration's own name token.
        var namePos = new Point(0, "type Ac".length());

        try (var store = StoreFixture.ofCatalog(srcRoot, DECLARED_ACTOR_SDL)) {
            String actorFqn = store.tableClassFqn("actor");
            store.withJavaSource(srcRoot, actorFqn, """
                /** The actor table. */
                public class Actor {
                    public final Object FIRST_NAME = null;
                }
                """);
            var workspace = new Workspace();
            // Which declaration the type name binds to is the store's answer off the captured
            // binding, so the projection this arm still takes carries nothing.
            var snapshot = new LspSchemaSnapshot.Built.Current();

            assertThat(hoverText(workspace, store, file, namePos, snapshot, true))
                .contains("The actor table.");

            var loc = DeclarationDefinitions
                .compute(file, store.handle(), snapshot, namePos)
                .orElseThrow();
            assertThat(loc.getUri()).endsWith("Actor.java");
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
        return Hovers.compute(LspVocabulary.load(), file,
            Optional.of(store.handle()), snapshot, pos, declarationNames)
            .orElseThrow().getContents().getRight().getValue();
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
