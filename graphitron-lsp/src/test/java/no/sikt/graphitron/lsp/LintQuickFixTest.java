package no.sikt.graphitron.lsp;

import graphql.language.SourceLocation;
import no.sikt.graphitron.lsp.code_action.CodeActions;
import no.sikt.graphitron.lsp.state.StoreAccess;
import no.sikt.graphitron.lsp.state.Workspace;
import no.sikt.graphitron.rewrite.BuildWarning;
import no.sikt.graphitron.rewrite.lint.LintFix;
import no.sikt.graphitron.rewrite.lint.LintRule;
import org.eclipse.lsp4j.CodeAction;
import org.eclipse.lsp4j.CodeActionContext;
import org.eclipse.lsp4j.CodeActionKind;
import org.eclipse.lsp4j.CodeActionParams;
import org.eclipse.lsp4j.Command;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.TextDocumentContentChangeEvent;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.TextEdit;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The finding-keyed {@code QuickFix} branch, reading the corrections the build stored: a fix-bearing
 * lint finding becomes a {@link CodeAction} whose edits, applied, yield the corrected SDL. Covers a
 * local rename, an additive insertion, a multi-edit fix whose stored order is not source order, the
 * no-fix case, the other-file case, the sibling-graph case, a session with no store, and the buffer
 * gate: a stored edit is a span in the text the rule read, so it is offered only while the buffer
 * still holds that text.
 *
 * <p>The findings go in through the build's own writer over a real captured schema, so the rows are
 * the rows a dev session would hold. What a rule concludes is the build's subject, not this test's:
 * the fixes are constructed here, which is what keeps these cases about the projection.
 */
class LintQuickFixTest {

    @TempDir
    Path tmp;

    private static final String RENAME_SDL = """
        type Query {
          user: User
        }
        type User {
          userName: String
        }
        """;

    @Test
    @DisplayName("a stored local rename becomes a quick fix that corrects the SDL")
    void localRenameFinding_offersQuickFixThatCorrectsTheSdl() {
        try (var fixture = StoreFixture.of(tmp, RENAME_SDL)) {
            fixture.withBuildWarnings(List.of(renameFinding(fixture)));
            var workspace = session(fixture, RENAME_SDL);

            var action = quickFix(workspace, uriOf(fixture), lineRange(4), "Drop the type-name prefix");

            assertThat(action).isNotNull();
            assertThat(action.getKind()).isEqualTo(CodeActionKind.QuickFix);
            assertThat(applyEdits(RENAME_SDL, action, uriOf(fixture)))
                .isEqualTo(RENAME_SDL.replace("userName", "name"));
        }
    }

    @Test
    @DisplayName("a stored insertion becomes a quick fix that touches no existing text")
    void additiveFinding_offersQuickFixThatInsertsWithoutTouchingExistingText() {
        String sdl = """
            type Query {
              widget: Widget
            }
            type Widget {
              old: String @deprecated
            }
            """;
        try (var fixture = StoreFixture.of(tmp, sdl)) {
            // '@deprecated' begins at column 15 of line 5; the reason goes right after its name.
            var fix = LintFix.insertAt("Add a reason placeholder",
                new SourceLocation(5, 26, fixture.sourceName()), "(reason: \"why\")");
            fixture.withBuildWarnings(List.of(new BuildWarning.LintFinding(
                "@deprecated should carry a non-empty 'reason'.",
                new SourceLocation(5, 15, fixture.sourceName()),
                LintRule.DEPRECATIONS_HAVE_A_REASON, Optional.of(fix))));
            var workspace = session(fixture, sdl);

            var action = quickFix(workspace, uriOf(fixture), lineRange(4), "Add a reason placeholder");

            assertThat(action).isNotNull();
            assertThat(applyEdits(sdl, action, uriOf(fixture)))
                .isEqualTo(sdl.replace("@deprecated", "@deprecated(reason: \"why\")"));
        }
    }

    @Test
    @DisplayName("a multi-edit fix keeps the rule's own edit order, which need not be source order")
    void multiEditFinding_appliesEveryEditInTheStoredOrder() {
        String sdl = """
            type Query {
              point: Point
            }
            type Point {
              xCoord: Int  yCoord: Int
            }
            """;
        try (var fixture = StoreFixture.of(tmp, sdl)) {
            // Written back to front on purpose: the later span first, so a reader sorting the edits
            // by position in the source would come out in the other order.
            var fix = new LintFix("Drop the coordinate suffixes", List.of(
                new LintFix.Edit(new SourceLocation(5, 16, fixture.sourceName()),
                    new SourceLocation(5, 22, fixture.sourceName()), "y"),
                new LintFix.Edit(new SourceLocation(5, 3, fixture.sourceName()),
                    new SourceLocation(5, 9, fixture.sourceName()), "x")));
            fixture.withBuildWarnings(List.of(new BuildWarning.LintFinding(
                "Field names repeat their type's subject.",
                new SourceLocation(5, 3, fixture.sourceName()),
                LintRule.NO_TYPENAME_PREFIX, Optional.of(fix))));
            var workspace = session(fixture, sdl);

            var action = quickFix(workspace, uriOf(fixture), lineRange(4), "Drop the coordinate suffixes");

            assertThat(action).isNotNull();
            List<TextEdit> edits = action.getEdit().getChanges().get(uriOf(fixture));
            assertThat(edits).extracting(e -> e.getRange().getStart().getCharacter())
                .as("the stored position is the rule's order, not a sort of the spans")
                .containsExactly(15, 2);
            assertThat(applyEdits(sdl, action, uriOf(fixture)))
                .isEqualTo(sdl.replace("xCoord: Int  yCoord: Int", "x: Int  y: Int"));
        }
    }

    @Test
    @DisplayName("a finding whose rule suggested nothing offers no quick fix")
    void findingWithoutFix_offersNoQuickFix() {
        String sdl = """
            type Query {
              widget: widget
            }
            type widget {
              id: ID
            }
            """;
        try (var fixture = StoreFixture.of(tmp, sdl)) {
            fixture.withBuildWarnings(List.of(BuildWarning.LintFinding.of(
                "Type name 'widget' should be PascalCase.",
                new SourceLocation(4, 1, fixture.sourceName()), LintRule.TYPE_NAMES_PASCAL_CASE)));

            assertThat(invoke(session(fixture, sdl), lineRange(3), uriOf(fixture))).isEmpty();
        }
    }

    @Test
    @DisplayName("a fix for another file is not offered for this document")
    void findingInAnotherFile_offersNoQuickFix() {
        try (var fixture = StoreFixture.of(tmp, RENAME_SDL)) {
            String elsewhere = tmp.resolve("elsewhere.graphqls").toString();
            var fix = LintFix.replaceToken("Drop the type-name prefix",
                new SourceLocation(5, 3, elsewhere), "userName".length(), "name");
            fixture.withBuildWarnings(List.of(new BuildWarning.LintFinding(
                "Field 'User.userName' is prefixed with its type name; drop the prefix.",
                new SourceLocation(5, 3, elsewhere), LintRule.NO_TYPENAME_PREFIX, Optional.of(fix))));

            assertThat(invoke(session(fixture, RENAME_SDL), lineRange(4), uriOf(fixture))).isEmpty();
        }
    }

    @Test
    @DisplayName("a sibling graph's fix for the same file is not offered to this session")
    void siblingGraphsFix_offersNoQuickFix() {
        try (var fixture = StoreFixture.of(tmp, RENAME_SDL)) {
            fixture.andGraphSharingTheFile(tmp, "sibling");
            fixture.withBuildWarnings("sibling", List.of(renameFinding(fixture)));

            // Both graphs read the file, so the session's own graph is what picks; the fix belongs to
            // the other one's partition.
            assertThat(invoke(session(fixture, RENAME_SDL), lineRange(4), uriOf(fixture))).isEmpty();
        }
    }

    @Test
    @DisplayName("an edited buffer offers no quick fix, the stored spans addressing text it no longer holds")
    void editedBuffer_offersNoQuickFix() {
        try (var fixture = StoreFixture.of(tmp, RENAME_SDL)) {
            fixture.withBuildWarnings(List.of(renameFinding(fixture)));
            var workspace = session(fixture, RENAME_SDL);
            assertThat(invoke(workspace, lineRange(4), uriOf(fixture)))
                .as("the unedited buffer is the captured text, so the fix is offered")
                .isNotEmpty();

            workspace.didChange(uriOf(fixture), 2, List.of(
                new TextDocumentContentChangeEvent("# a comment\n" + RENAME_SDL)));

            assertThat(invoke(workspace, lineRange(5), uriOf(fixture))).isEmpty();
        }
    }

    @Test
    @DisplayName("a session with no store offers no quick fix")
    void sessionWithoutStore_offersNoQuickFix() {
        try (var fixture = StoreFixture.of(tmp, RENAME_SDL)) {
            fixture.withBuildWarnings(List.of(renameFinding(fixture)));
            var workspace = new Workspace();
            workspace.didOpen(uriOf(fixture), 1, RENAME_SDL);

            assertThat(invoke(workspace, lineRange(4), uriOf(fixture))).isEmpty();
        }
    }

    // --- harness ---

    /** The finding {@link #RENAME_SDL} carries, with the fix that corrects it. */
    private static BuildWarning renameFinding(StoreFixture fixture) {
        var fix = LintFix.replaceToken("Drop the type-name prefix",
            new SourceLocation(5, 3, fixture.sourceName()), "userName".length(), "name");
        return new BuildWarning.LintFinding(
            "Field 'User.userName' is prefixed with its type name; drop the prefix.",
            new SourceLocation(5, 3, fixture.sourceName()), LintRule.NO_TYPENAME_PREFIX,
            Optional.of(fix));
    }

    /** A session with the captured file open and read access to the store that captured it. */
    private static Workspace session(StoreFixture fixture, String buffer) {
        var workspace = new Workspace();
        workspace.didOpen(uriOf(fixture), 1, buffer);
        workspace.setStore(fixture.access());
        return workspace;
    }

    private static String uriOf(StoreFixture fixture) {
        return Path.of(fixture.sourceName()).toUri().toString();
    }

    private static List<? extends Either<Command, CodeAction>> invoke(
        Workspace workspace, Range range, String uri
    ) {
        var params = new CodeActionParams(
            new TextDocumentIdentifier(uri), range, new CodeActionContext(List.of()));
        return CodeActions.compute(params, workspace);
    }

    private static CodeAction quickFix(Workspace workspace, String uri, Range range, String title) {
        return invoke(workspace, range, uri).stream()
            .filter(Either::isRight).map(Either::getRight)
            .filter(ca -> title.equals(ca.getTitle()))
            .findFirst().orElse(null);
    }

    private static Range lineRange(int line) {
        return new Range(new Position(line, 0), new Position(line, Integer.MAX_VALUE));
    }

    /**
     * Applies a code action's text edits to {@code source}. Edits are spliced from the last position
     * backward so earlier offsets stay valid, mirroring how an LSP client applies a WorkspaceEdit.
     */
    private static String applyEdits(String source, CodeAction action, String uri) {
        List<TextEdit> edits = new ArrayList<>(action.getEdit().getChanges().get(uri));
        edits.sort(Comparator
            .comparingInt((TextEdit e) -> e.getRange().getStart().getLine())
            .thenComparingInt(e -> e.getRange().getStart().getCharacter())
            .reversed());
        var sb = new StringBuilder(source);
        for (TextEdit edit : edits) {
            int start = offset(source, edit.getRange().getStart());
            int end = offset(source, edit.getRange().getEnd());
            sb.replace(start, end, edit.getNewText());
        }
        return sb.toString();
    }

    private static int offset(String source, Position pos) {
        int line = 0;
        int i = 0;
        while (line < pos.getLine() && i < source.length()) {
            if (source.charAt(i) == '\n') line++;
            i++;
        }
        return Math.min(i + pos.getCharacter(), source.length());
    }
}
