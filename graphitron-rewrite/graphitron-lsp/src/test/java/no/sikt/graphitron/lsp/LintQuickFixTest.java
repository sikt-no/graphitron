package no.sikt.graphitron.lsp;

import graphql.language.SourceLocation;
import no.sikt.graphitron.lsp.code_action.CodeActions;
import no.sikt.graphitron.lsp.state.Workspace;
import no.sikt.graphitron.rewrite.BuildWarning;
import no.sikt.graphitron.rewrite.GraphQLRewriteGenerator;
import no.sikt.graphitron.rewrite.ValidationReport;
import no.sikt.graphitron.rewrite.catalog.CompletionData;
import no.sikt.graphitron.rewrite.catalog.LspSchemaSnapshot;
import no.sikt.graphitron.rewrite.lint.LintFix;
import no.sikt.graphitron.rewrite.lint.LintRule;
import org.eclipse.lsp4j.CodeAction;
import org.eclipse.lsp4j.CodeActionContext;
import org.eclipse.lsp4j.CodeActionKind;
import org.eclipse.lsp4j.CodeActionParams;
import org.eclipse.lsp4j.Command;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.TextEdit;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R398 fix slice, LSP arm: the finding-keyed {@code QuickFix} branch projects a fix-bearing lint
 * {@link BuildWarning.LintFinding} in the build's {@link ValidationReport} into a
 * {@link CodeAction}, and applying its {@link org.eclipse.lsp4j.WorkspaceEdit} yields the corrected
 * SDL. Covers a local rename, an additive insertion, a multi-edit swap, the no-fix case (none
 * offered), and the R139 freshness-silence policy on a stale snapshot. The build stays the single
 * evaluator: the fix is the one the rule computed build-side; the LSP only projects it.
 */
class LintQuickFixTest {

    private static final String PATH = "/tmp/schema.graphqls";
    private static final String URI = ValidationReport.canonicalUri(PATH);

    @Test
    void localRenameFinding_offersQuickFixThatCorrectsTheSdl() {
        String source = "type User {\n  userName: String\n}\n";
        var fix = LintFix.replaceToken("Drop the type-name prefix",
            new SourceLocation(2, 3, PATH), "userName".length(), "name");
        var finding = new BuildWarning.LintFinding(
            "Field 'User.userName' is prefixed with its type name; drop the prefix.",
            new SourceLocation(2, 3, PATH), LintRule.NO_TYPENAME_PREFIX, Optional.of(fix));

        var action = quickFix(currentWorkspace(source, finding), lineRange(1), "Drop the type-name prefix");

        assertThat(action).isNotNull();
        assertThat(action.getKind()).isEqualTo(CodeActionKind.QuickFix);
        assertThat(applyEdits(source, action)).isEqualTo("type User {\n  name: String\n}\n");
    }

    @Test
    void additiveFinding_offersQuickFixThatInsertsWithoutTouchingExistingText() {
        String source = "type Widget { old: String @deprecated }\n";
        // '@deprecated' begins at column 27; the reason is inserted right after its name (column 38).
        var fix = LintFix.insertAt("Add a reason placeholder",
            new SourceLocation(1, 38, PATH), "(reason: \"why\")");
        var finding = new BuildWarning.LintFinding(
            "@deprecated should carry a non-empty 'reason'.",
            new SourceLocation(1, 27, PATH), LintRule.DEPRECATIONS_HAVE_A_REASON, Optional.of(fix));

        var action = quickFix(currentWorkspace(source, finding), lineRange(0), "Add a reason placeholder");

        assertThat(action).isNotNull();
        assertThat(applyEdits(source, action))
            .isEqualTo("type Widget { old: String @deprecated(reason: \"why\") }\n");
    }

    @Test
    void multiEditFinding_appliesEveryEdit() {
        String source = "enum Color { RED @index(name: \"r\") }\n";
        // Two edits on line 1: @index -> @order (cols 19..24) and its arg name -> index (cols 25..29).
        var fix = new LintFix("Replace with the successor directive", List.of(
            new LintFix.Edit(new SourceLocation(1, 19, PATH), new SourceLocation(1, 24, PATH), "order"),
            new LintFix.Edit(new SourceLocation(1, 25, PATH), new SourceLocation(1, 29, PATH), "index")));
        var finding = new BuildWarning.LintFinding(
            "Directive @index is deprecated; see its description for the replacement.",
            new SourceLocation(1, 18, PATH), LintRule.NO_DEPRECATED_DIRECTIVE_USAGE, Optional.of(fix));

        var action = quickFix(currentWorkspace(source, finding), lineRange(0),
            "Replace with the successor directive");

        assertThat(action).isNotNull();
        assertThat(applyEdits(source, action)).isEqualTo("enum Color { RED @order(index: \"r\") }\n");
    }

    @Test
    void findingWithoutFix_offersNoQuickFix() {
        String source = "type widget { id: ID }\n";
        var finding = BuildWarning.LintFinding.of(
            "Type name 'widget' should be PascalCase.",
            new SourceLocation(1, 1, PATH), LintRule.TYPE_NAMES_PASCAL_CASE);

        var actions = invoke(currentWorkspace(source, finding), lineRange(0));

        assertThat(actions).isEmpty();
    }

    @Test
    void staleSnapshot_offersNoQuickFix() {
        String source = "type User {\n  userName: String\n}\n";
        var fix = LintFix.replaceToken("Drop the type-name prefix",
            new SourceLocation(2, 3, PATH), "userName".length(), "name");
        var finding = new BuildWarning.LintFinding(
            "Field 'User.userName' is prefixed with its type name; drop the prefix.",
            new SourceLocation(2, 3, PATH), LintRule.NO_TYPENAME_PREFIX, Optional.of(fix));

        // R139: a stale snapshot silences the fix, since the finding may not reflect the buffer.
        var workspace = new Workspace(CompletionData.empty());
        workspace.didOpen(URI, 1, source);
        workspace.setBuildOutput(
            new GraphQLRewriteGenerator.BuildArtifacts(
                CompletionData.empty(), new LspSchemaSnapshot.Built.Current(List.of(), Map.of(), Map.of())),
            ValidationReport.from(List.of(), List.of(finding)));
        workspace.demoteSnapshot();

        assertThat(invoke(workspace, lineRange(1))).isEmpty();
    }

    // --- harness ---

    private static Workspace currentWorkspace(String source, BuildWarning finding) {
        var workspace = new Workspace(CompletionData.empty());
        workspace.didOpen(URI, 1, source);
        workspace.setBuildOutput(
            new GraphQLRewriteGenerator.BuildArtifacts(
                CompletionData.empty(), new LspSchemaSnapshot.Built.Current(List.of(), Map.of(), Map.of())),
            ValidationReport.from(List.of(), List.of(finding)));
        return workspace;
    }

    private static List<? extends Either<Command, CodeAction>> invoke(Workspace workspace, Range range) {
        var params = new CodeActionParams(
            new TextDocumentIdentifier(URI), range, new CodeActionContext(List.of()));
        return CodeActions.compute(params, workspace);
    }

    private static CodeAction quickFix(Workspace workspace, Range range, String title) {
        return invoke(workspace, range).stream()
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
    private static String applyEdits(String source, CodeAction action) {
        List<TextEdit> edits = new ArrayList<>(action.getEdit().getChanges().get(URI));
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
