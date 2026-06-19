package no.sikt.graphitron.lsp;

import no.sikt.graphitron.lsp.definition.IntraSchemaDefinitions;
import no.sikt.graphitron.lsp.state.Workspace;
import org.eclipse.lsp4j.Location;
import org.junit.jupiter.api.Test;
import io.github.treesitter.jtreesitter.Point;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Goto-definition for intra-schema type references: cursor on a {@code named_type}
 * reference jumps to the canonical {@code type Foo { ... }} declaration in whichever
 * open workspace file declares it. Drives a real {@link Workspace} (open the files,
 * issue the request) and asserts the returned {@link Location}'s URI and range, not
 * any walk internals.
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
              ghost: Ghost
            }
            """);

        assertThat(compute(ws, uri, "Ghost")).isEmpty();
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

    /** Resolve the cursor onto the first occurrence of {@code token} in the file's source. */
    private static Optional<Location> compute(Workspace ws, String uri, String token) {
        var file = ws.get(uri).orElseThrow();
        String source = new String(file.source(), java.nio.charset.StandardCharsets.UTF_8);
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
        return IntraSchemaDefinitions.compute(ws, uri, new Point(line, col));
    }
}
