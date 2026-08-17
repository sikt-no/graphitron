package no.sikt.graphitron.lsp;

import no.sikt.graphitron.lsp.definition.IntraSchemaDefinitions;
import no.sikt.graphitron.lsp.state.Workspace;
import no.sikt.graphitron.model.read.StoreHandle;
import org.eclipse.lsp4j.Location;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import io.github.treesitter.jtreesitter.Point;

import java.nio.file.Path;
import java.util.Optional;

import static no.sikt.graphitron.model.Tables.GRAPHQL_TYPE_DECLARATION;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Goto-definition for intra-schema type references: cursor on a {@code named_type}
 * reference jumps to the canonical {@code type Foo { ... }} declaration. When an open
 * buffer declares the type the jump lands on the live tree-sitter name span; when none
 * does it falls back to the declaration sites the graph's capture recorded. Drives
 * a real {@link Workspace} (open the files, issue the request) and asserts the returned
 * {@link Location}'s URI and range, not any walk internals.
 *
 * <p>The fallback arms capture real SDL into a real store and read positions back, so a
 * position asserted below is where a parse found the declaration, never a coordinate a
 * fixture asserted into a map.
 */
class IntraSchemaDefinitionTest {

    @Test
    void fieldTypeReferenceResolvesWithinSameFile() {
        var ws = new Workspace();
        String uri = "file:///schema.graphqls";
        ws.didOpen(uri, 1, """
            type Query {
              films: [Film!]!
            }

            type Film {
              title: String
            }
            """);

        var loc = compute(ws, uri, "Film!").orElseThrow();
        assertThat(loc.getUri()).isEqualTo(uri);
        // Lands on the "Film" name token of "type Film", line 4 (zero-based).
        assertThat(loc.getRange().getStart().getLine()).isEqualTo(4);
        assertThat(loc.getRange().getStart().getCharacter()).isEqualTo(5);
        assertThat(loc.getRange().getEnd().getCharacter()).isEqualTo(9);
    }

    @Test
    void fieldTypeReferenceResolvesAcrossFiles() {
        var ws = new Workspace();
        String queryUri = "file:///query.graphqls";
        String filmUri = "file:///film.graphqls";
        ws.didOpen(queryUri, 1, """
            type Query {
              films: [Film!]!
            }
            """);
        ws.didOpen(filmUri, 1, """
            type Film {
              title: String
            }
            """);

        var loc = compute(ws, queryUri, "Film!").orElseThrow();
        assertThat(loc.getUri()).isEqualTo(filmUri);
        assertThat(loc.getRange().getStart().getLine()).isZero();
    }

    @Test
    void implementsInterfaceResolves() {
        var ws = new Workspace();
        String uri = "file:///schema.graphqls";
        // Reference before declaration so the token targets the `implements`
        // reference, not the `interface Node` declaration name.
        ws.didOpen(uri, 1, """
            type Film implements Node {
              id: ID!
            }

            interface Node {
              id: ID!
            }
            """);

        var loc = compute(ws, uri, "Node {").orElseThrow();
        assertThat(loc.getUri()).isEqualTo(uri);
        // Lands on the "Node" name of "interface Node", line 4 (zero-based).
        assertThat(loc.getRange().getStart().getLine()).isEqualTo(4);
    }

    @Test
    void unionMemberResolves() {
        var ws = new Workspace();
        String uri = "file:///schema.graphqls";
        ws.didOpen(uri, 1, """
            type Film {
              title: String
            }

            union SearchResult = Film
            """);

        var loc = compute(ws, uri, "Film\n").orElseThrow();
        assertThat(loc.getUri()).isEqualTo(uri);
        assertThat(loc.getRange().getStart().getLine()).isZero();
    }

    @Test
    void inputFieldTypeResolves() {
        var ws = new Workspace();
        String uri = "file:///schema.graphqls";
        ws.didOpen(uri, 1, """
            input FilmFilter {
              category: CategoryInput
            }

            input CategoryInput {
              name: String
            }
            """);

        var loc = compute(ws, uri, "CategoryInput").orElseThrow();
        assertThat(loc.getUri()).isEqualTo(uri);
        assertThat(loc.getRange().getStart().getLine()).isEqualTo(4);
    }

    @Test
    void builtinScalarReferenceReturnsEmpty() {
        var ws = new Workspace();
        String uri = "file:///schema.graphqls";
        ws.didOpen(uri, 1, """
            type Film {
              title: String
            }
            """);

        assertThat(compute(ws, uri, "String")).isEmpty();
    }

    @Test
    void unknownTypeReferenceReturnsEmpty() {
        var ws = new Workspace();
        String uri = "file:///schema.graphqls";
        ws.didOpen(uri, 1, """
            type Query {
              missing: Missing
            }
            """);

        assertThat(compute(ws, uri, "Missing")).isEmpty();
    }

    @Test
    void cursorOnDeclarationNameReturnsEmpty() {
        var ws = new Workspace();
        String uri = "file:///schema.graphqls";
        ws.didOpen(uri, 1, """
            type Film {
              title: String
            }
            """);

        // Cursor on the "Film" of "type Film" is a declaration name, not a
        // named_type reference, so the provider does not engage.
        assertThat(compute(ws, uri, "Film {")).isEmpty();
    }

    @Test
    void definitionWinsOverExtension() {
        var ws = new Workspace();
        String defUri = "file:///film.graphqls";
        String extUri = "file:///film-ext.graphqls";
        String refUri = "file:///query.graphqls";
        ws.didOpen(extUri, 1, """
            extend type Film {
              rentalRate: Float
            }
            """);
        ws.didOpen(defUri, 1, """
            type Film {
              title: String
            }
            """);
        ws.didOpen(refUri, 1, """
            type Query {
              films: [Film!]!
            }
            """);

        var loc = compute(ws, refUri, "Film!").orElseThrow();
        // The canonical "type Film" definition, never the "extend type Film".
        assertThat(loc.getUri()).isEqualTo(defUri);
    }

    /**
     * A session outside a build resolves every reference the workspace declares. The
     * asymmetry with the two providers that jump into the Java tree is the point: those
     * have nothing to say without a store and decline at the top, while this one's
     * authoritative arm is the buffer and only its on-disk fallback needs the store.
     */
    @Test
    void aSessionWithNoStoreAccessStillResolvesWithinOpenBuffers() {
        var ws = new Workspace();
        String queryUri = "file:///query.graphqls";
        String filmUri = "file:///film.graphqls";
        ws.didOpen(queryUri, 1, "type Query { films: [Film!]! }\n");
        ws.didOpen(filmUri, 1, "type Film { title: String }\n");

        var loc = IntraSchemaDefinitions
            .compute(ws, Optional.empty(), queryUri, cursorOn(ws, queryUri, "Film!"))
            .orElseThrow();
        assertThat(loc.getUri()).isEqualTo(filmUri);
    }

    @Test
    void resolvesViaCapturedSiteWhenDeclaringFileNotOpen(@TempDir Path tmp) {
        // Only the referencing file is open; the file declaring Film is on disk and in no
        // buffer. Its captured declaration site carries the position, so the jump resolves
        // workspace-wide rather than silently no-opping.
        var ws = new Workspace();
        String refUri = "file:///ref.graphqls";
        ws.didOpen(refUri, 1, "type Ref { films: [Film!]! }\n");

        try (var fixture = StoreFixture.of(tmp, """
            type Query {
              films: [Film!]!
            }

            type Film {
              title: String
            }
            """)) {
            var loc = compute(ws, refUri, "Film!", fixture.handle()).orElseThrow();
            assertThat(loc.getUri()).isEqualTo(capturedUri(fixture));
            // "type Film" opens the fifth line of the SDL above, which the store holds
            // 1-based and this surface converts.
            assertThat(loc.getRange().getStart().getLine()).isEqualTo(4);
            assertThat(loc.getRange().getStart().getCharacter()).isZero();
        }
    }

    @Test
    void openBufferDeclarationWinsOverCapturedSite(@TempDir Path tmp) {
        // Precedence: the declaring file is open, and the buffer holds an edit that moved
        // the declaration, so the live tree-sitter span answers rather than the position
        // the last capture recorded for the same file.
        var ws = new Workspace();
        String refUri = "file:///ref.graphqls";
        ws.didOpen(refUri, 1, "type Ref { films: [Film!]! }\n");

        try (var fixture = StoreFixture.of(tmp, """
            type Query {
              films: [Film!]!
            }

            type Film {
              title: String
            }
            """)) {
            ws.didOpen(capturedUri(fixture), 1, "type Film { title: String }\n");

            var loc = compute(ws, refUri, "Film!", fixture.handle()).orElseThrow();
            assertThat(loc.getUri()).isEqualTo(capturedUri(fixture));
            // The edited buffer declares Film on line 0; the captured site says line 4.
            assertThat(loc.getRange().getStart().getLine()).isZero();
        }
    }

    @Test
    void capturedBaseDeclarationWinsOverItsExtension(@TempDir Path tmp) {
        // The extension is written first, so document order and merge order disagree: the
        // jump lands on the base definition because merge order puts it first, not because
        // it came first in the file.
        var ws = new Workspace();
        String refUri = "file:///ref.graphqls";
        ws.didOpen(refUri, 1, "type Ref { films: [Film!]! }\n");

        try (var fixture = StoreFixture.of(tmp, """
            type Query {
              films: [Film!]!
            }

            extend type Film {
              rentalRate: Float
            }

            type Film {
              title: String
            }
            """)) {
            var loc = compute(ws, refUri, "Film!", fixture.handle()).orElseThrow();
            // "type Film" opens the ninth line, "extend type Film" the fifth.
            assertThat(loc.getRange().getStart().getLine()).isEqualTo(8);
        }
    }

    /**
     * A type graphitron's own bundled directive definitions declare. Capture reads that
     * file like any other schema file, so the site is a real captured row; what it is not
     * is a file an editor can open, and the jump declines rather than handing back a URI
     * built from a classpath resource name.
     */
    @Test
    void aTypeDeclaredOnlyInTheBundledDirectiveSourceDoesNotJump(@TempDir Path tmp) {
        var ws = new Workspace();
        String refUri = "file:///ref.graphqls";
        ws.didOpen(refUri, 1, "type Ref { handler: ErrorHandler }\n");

        try (var fixture = StoreFixture.of(tmp, "type Query { placeholder: Int }\n")) {
            var handle = fixture.handle();
            String site = handle.dsl()
                .select(GRAPHQL_TYPE_DECLARATION.SOURCE_NAME)
                .from(GRAPHQL_TYPE_DECLARATION)
                .where(GRAPHQL_TYPE_DECLARATION.GRAPH_NAME.eq(StoreFixture.GRAPH))
                .and(GRAPHQL_TYPE_DECLARATION.TYPE_NAME.eq("ErrorHandler"))
                .fetchOne(GRAPHQL_TYPE_DECLARATION.SOURCE_NAME);
            assertThat(site)
                .as("the bundled directive type is captured, under a source name that is no file")
                .isNotNull()
                .satisfies(name -> assertThat(Path.of(name).isAbsolute()).isFalse());

            assertThat(compute(ws, refUri, "ErrorHandler", handle)).isEmpty();
        }
    }

    @Test
    void noOpenDeclarationAndNoCapturedSiteReturnsEmpty(@TempDir Path tmp) {
        // Neither-source: the type is declared in no open buffer and the graph captured no
        // site for it, so the provider preserves its no-op.
        var ws = new Workspace();
        String uri = "file:///schema.graphqls";
        ws.didOpen(uri, 1, """
            type Query {
              missing: Missing
            }
            """);

        try (var fixture = StoreFixture.of(tmp, "type Query { placeholder: Int }\n")) {
            assertThat(compute(ws, uri, "Missing", fixture.handle())).isEmpty();
        }
    }

    /** The URI of the schema file the fixture captured, as an editor would name it. */
    private static String capturedUri(StoreFixture fixture) {
        return Path.of(fixture.sourceName()).toUri().toString();
    }

    /**
     * Resolve the cursor onto the first occurrence of {@code token}; the open-buffer arms
     * run with no store at all, so they exercise the tree-sitter path alone with no
     * fallback in play.
     */
    private static Optional<Location> compute(Workspace ws, String uri, String token) {
        return IntraSchemaDefinitions.compute(ws, Optional.empty(), uri, cursorOn(ws, uri, token));
    }

    private static Optional<Location> compute(
        Workspace ws, String uri, String token, StoreHandle store
    ) {
        return IntraSchemaDefinitions.compute(ws, Optional.of(store), uri, cursorOn(ws, uri, token));
    }

    private static Point cursorOn(Workspace ws, String uri, String token) {
        String source = ws.withView(uri, null,
            v -> new String(v.source(), java.nio.charset.StandardCharsets.UTF_8));
        if (source == null) {
            throw new AssertionError("uri '" + uri + "' is not open");
        }
        int idx = source.indexOf(token);
        if (idx < 0) {
            throw new AssertionError("token '" + token + "' not in source of " + uri);
        }
        int line = 0;
        int lineStart = 0;
        for (int i = 0; i < idx; i++) {
            if (source.charAt(i) == '\n') {
                line++;
                lineStart = i + 1;
            }
        }
        int col = (idx - lineStart) + 1; // land inside the token, not on its first edge
        return new Point(line, col);
    }
}
