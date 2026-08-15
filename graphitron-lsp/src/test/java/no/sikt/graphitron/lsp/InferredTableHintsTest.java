package no.sikt.graphitron.lsp;

import no.sikt.graphitron.lsp.inlay.InlayHints;
import no.sikt.graphitron.lsp.state.FileSnapshot;
import no.sikt.graphitron.lsp.state.InlayHintConfig;
import no.sikt.graphitron.lsp.state.WorkspaceFileTestSupport;
import no.sikt.graphitron.rewrite.catalog.LspSchemaSnapshot;
import org.eclipse.lsp4j.InlayHint;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The inferred-directive arm's {@code @table} passes, over a real capture. Both of them, the one
 * that fills in an argument a present directive omitted and the one that renders the whole
 * directive at a site carrying none, read the binding relation the column-match classifier stands
 * on rather than a name-keyed classification the generator pass shipped.
 *
 * <p>Every case runs under an unavailable snapshot, which is the point of the move as much as it is
 * the marker of where the answer comes from: these ghosts now appear in a session that has captured
 * a schema and has no successful generator pass behind it, where before they waited for one.
 *
 * <p>One silence is deliberately not asserted, because it is not the arm's claim to make. A
 * directiveless object reached from a field of a table-bound type resolves its fields against the
 * parent's own row, which binds it as surely as {@code @table} binds anything, and the whole-directive
 * ghost is exactly what such a type wants. The relation this arm reads is keyed on {@code @table}
 * applications and carries no consumer-derived binding, so an unmarked nesting type here means "no
 * relation answers yet" rather than "not bound", and pinning it would take the derivation that does
 * not exist. The prohibition lives in the arm's own javadoc, where it outlives this test.
 *
 * <p>The sibling {@code InlayHintsTest} holds the {@code @field} and {@code @reference} renderers,
 * which still read the snapshot and pass an empty handle for the same reason.
 */
class InferredTableHintsTest {

    /**
     * {@code Movie} binds to a table of a different name on purpose: it is what tells an answer read
     * from the capture apart from one derived from the buffer's own type name.
     */
    private static final String SDL = """
        type Query { placeholder: Int }

        type Film @table(name: "film") {
            title: String
        }

        type Movie @table(name: "film") {
            title: String
        }

        type Customer @table {
            firstName: String
        }

        type Note {
            text: String
        }
        """;

    @TempDir
    static Path tmp;

    private static StoreFixture store;

    @BeforeAll
    static void capture() {
        store = StoreFixture.ofCatalog(tmp, SDL);
    }

    @AfterAll
    static void closeStore() {
        store.close();
    }

    @Test
    void aBareTableDirectiveShowsTheNameTheBindingResolvedTo() {
        var file = file("""
            type Customer @table {
                firstName: String
            }
            """);
        assertThat(labels(file))
            .as("the omitted argument defaults to the type's own name, which the census matches"
                + " case-insensitively")
            .contains("name: \"customer\"");
    }

    @Test
    void theNameShownIsTheCapturedBindingRatherThanTheTypeName() {
        // Movie is bound to film. A renderer deriving the value from the buffer would say "movie".
        var file = file("""
            type Movie @table {
                title: String
            }
            """);
        assertThat(labels(file)).contains("name: \"film\"");
    }

    @Test
    void anAuthoredNameSilencesBothPasses() {
        var file = file("""
            type Film @table(name: "film") {
                title: String
            }
            """);
        assertThat(labels(file))
            .as("the argument is in the buffer, so there is nothing to fill in, and the directive"
                + " is in the buffer, so there is nothing to render whole")
            .isEmpty();
    }

    @Test
    void anExtensionSiteResolvesThroughTheBaseDeclarationsBinding() {
        // The absent pass's real subject. A binding is a property of the type rather than of the
        // declaration the cursor is in, so an extension whose base carries @table in another file
        // is table-bound at a site that carries no directive at all.
        var file = file("""
            extend type Film {
                rating: String
            }
            """);
        assertThat(labels(file)).containsExactly("@table(name: \"film\")");
    }

    @Test
    void anExtensionCarryingTheDirectiveItselfGetsTheArgumentInstead() {
        var file = file("""
            extend type Film @table {
                rating: String
            }
            """);
        assertThat(labels(file))
            .as("the whole-directive ghost is for a site with no directive; this one has one")
            .containsExactly("name: \"film\"");
    }

    @Test
    void aTypeNoFieldEverReachesGetsNeitherPass() {
        // Note is declared standing alone: no @table, and no field of a table-bound type returns
        // it, so nothing gives its fields a row to resolve against. That is what makes this silence
        // assertable, and it is narrower than it looks; see the class javadoc.
        var file = file("""
            type Note {
                text: String
            }
            """);
        assertThat(labels(file)).isEmpty();
    }

    @Test
    void aRootTypeGetsNoGhost() {
        // Roots classify before any table binding is read, and the relation masks the three names.
        // Without that, every schema's Query would wear an invented @table(name: "query").
        var file = file("""
            type Query {
                placeholder: Int
            }
            """);
        assertThat(labels(file)).isEmpty();
    }

    @Test
    void anAmbiguousBindingRendersNothing(@TempDir Path directory) {
        // Two schemas declare a table named event, so there is no value inference would fill in.
        // Showing either would tell the author graphitron resolved something it declined to.
        try (var multiSchema = StoreFixture.ofMultiSchemaCatalog(directory, """
            type Query { placeholder: Int }

            type Foo @table(name: "event") {
                bar: Int
            }
            """)) {
            var file = file("""
                type Foo @table {
                    bar: Int
                }
                """);
            assertThat(labels(multiSchema, file)).isEmpty();
        }
    }

    @Test
    void noGhostsWithoutAStore() {
        var file = file("""
            type Customer @table {
                firstName: String
            }
            """);
        assertThat(InlayHints.compute(config(), file, Optional.empty(),
            LspSchemaSnapshot.unavailable(), fullRange())).isEmpty();
    }

    // ===== Helpers =====

    private static List<String> labels(FileSnapshot file) {
        return labels(store, file);
    }

    private static List<String> labels(StoreFixture fixture, FileSnapshot file) {
        return InlayHints.compute(config(), file, Optional.of(fixture.handle()),
                LspSchemaSnapshot.unavailable(), fullRange())
            .stream().map(InferredTableHintsTest::labelOf).toList();
    }

    private static String labelOf(InlayHint hint) {
        var either = hint.getLabel();
        return either.isLeft() ? either.getLeft() : either.getRight().toString();
    }

    /** The inferred-directive toggle alone, so no other arm's label can be mistaken for a ghost. */
    private static InlayHintConfig config() {
        return new InlayHintConfig(true, false, false, false);
    }

    private static FileSnapshot file(String source) {
        return WorkspaceFileTestSupport.snapshot(source);
    }

    private static Range fullRange() {
        return new Range(new Position(0, 0), new Position(10_000, 0));
    }
}
