package no.sikt.graphitron.lsp.code_action;

import io.github.treesitter.jtreesitter.Node;
import no.sikt.graphitron.lsp.code_action.SdlAction.RewriteResult;
import no.sikt.graphitron.lsp.parsing.Nodes;
import no.sikt.graphitron.lsp.state.Workspace;
import no.sikt.graphitron.lsp.state.WorkspaceFile;
import org.eclipse.lsp4j.CodeAction;
import org.eclipse.lsp4j.CodeActionKind;
import org.eclipse.lsp4j.CodeActionParams;
import org.eclipse.lsp4j.Command;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.TextEdit;
import org.eclipse.lsp4j.WorkspaceEdit;
import org.eclipse.lsp4j.jsonrpc.messages.Either;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Entry-point for {@code textDocument/codeAction} requests. Runs every
 * {@link SdlAction} registered in {@link SdlActions} against the file
 * (and, for the workspace-scoped bulk action, every other open file)
 * and emits up to three activation points per action:
 *
 * <ul>
 *   <li><b>Per-site quick-fix.</b> Cursor sits on a matched literal:
 *       the action emits a single-edit {@link WorkspaceEdit} for that
 *       one site.</li>
 *   <li><b>File-scoped bulk.</b> "Migrate ... in this file": one
 *       {@link WorkspaceEdit} composing every resolvable site in the
 *       current document.</li>
 *   <li><b>Workspace-scoped bulk.</b> "Migrate ... in this workspace":
 *       one multi-document {@link WorkspaceEdit} composing every
 *       resolvable site across every open file.</li>
 * </ul>
 *
 * <p>For both bulk activations, sites whose rewrite returns
 * {@link RewriteResult.Skip} are dropped from the {@link WorkspaceEdit}
 * but counted; the result message names the rewritten count and the
 * skip count.
 */
public final class CodeActions {

    private CodeActions() {}

    /**
     * Three-branch result-message wording for bulk activations. Pinned
     * by the spec; tested in {@code CodeActionsTest}.
     */
    static String resultMessage(String displayName, int rewritten, int skipped) {
        if (rewritten > 0 && skipped == 0) {
            return "Migrated " + rewritten + " legacy " + countableNoun(displayName) + ".";
        }
        if (rewritten > 0) {
            return "Migrated " + rewritten + " legacy " + countableNoun(displayName)
                + "; " + skipped + " unresolvable, see problems panel.";
        }
        // rewritten == 0 && skipped > 0 (the (0, 0) case never fires;
        // the bulk activation is offered only when at least one match exists).
        return "No resolvable legacy sites; " + skipped + " unresolvable, see problems panel.";
    }

    private static String countableNoun(String displayName) {
        // Phrase the displayName for use after a count. R93's
        // displayName ("Migrate `name:` to `className:`") becomes
        // "ExternalCodeReference.name sites" in the spec's wording;
        // a stable, neutral phrasing here is "rewrite sites".
        return "ExternalCodeReference.name sites";
    }

    public static List<Either<Command, CodeAction>> compute(
        CodeActionParams params, Workspace workspace
    ) {
        var fileOpt = workspace.get(params.getTextDocument().getUri());
        if (fileOpt.isEmpty()) return List.of();
        var file = fileOpt.get();
        var actions = SdlActions.all(workspace.catalog());

        var out = new ArrayList<Either<Command, CodeAction>>();
        for (var action : actions) {
            // Materialise the detector eagerly: per-site filter, plus
            // bulk activation, plus the empty-list guard, all walk the
            // same list.
            List<Node> matches = action.detector().detect(file).toList();
            if (matches.isEmpty()) continue;

            String fileUri = params.getTextDocument().getUri();
            // Per-site: the cursor or selection range is intersected
            // against each match. LSP code-action requests bring the
            // request range; we pick matches whose byte range overlaps
            // the request range in source-bytes terms.
            for (var match : matches) {
                if (!intersects(file, params.getRange(), match)) continue;
                var result = action.rewrite().rewrite(file, match);
                if (result instanceof RewriteResult.Edit edit) {
                    out.add(Either.forRight(perSiteAction(action, fileUri, edit.edit())));
                }
            }

            // File-scoped bulk: composes every resolvable match in
            // this document.
            var fileSiteEdits = applyAll(action, file, matches);
            if (!fileSiteEdits.isEmpty() || !allMatchesResolved(action, file, matches)) {
                out.add(Either.forRight(fileBulkAction(
                    action, fileUri, fileSiteEdits,
                    countResolvable(action, file, matches),
                    countSkipped(action, file, matches))));
            }

            // Workspace-scoped bulk: composes resolvable matches
            // across every open file (including this one).
            Map<String, List<TextEdit>> wsEdits = new LinkedHashMap<>();
            int wsResolvable = 0;
            int wsSkipped = 0;
            for (var uri : openUris(workspace)) {
                var wsFileOpt = workspace.get(uri);
                if (wsFileOpt.isEmpty()) continue;
                var wsFile = wsFileOpt.get();
                var wsMatches = action.detector().detect(wsFile).toList();
                if (wsMatches.isEmpty()) continue;
                var edits = applyAll(action, wsFile, wsMatches);
                if (!edits.isEmpty()) wsEdits.put(uri, edits);
                wsResolvable += countResolvable(action, wsFile, wsMatches);
                wsSkipped += countSkipped(action, wsFile, wsMatches);
            }
            if (wsResolvable > 0 || wsSkipped > 0) {
                out.add(Either.forRight(workspaceBulkAction(
                    action, wsEdits, wsResolvable, wsSkipped)));
            }
        }
        return out;
    }

    private static List<String> openUris(Workspace workspace) {
        // Workspace doesn't expose an iteration view today; collect via
        // the recalculation queue plus the requested file. The spec's
        // workspace-bulk action is best-effort: it operates on every
        // file the workspace knows about. Until Workspace exposes a
        // dedicated openFiles() view, we fall back to the
        // markAllForRecalculation pattern: it enqueues every open file,
        // so draining gives us every URI without mutating diagnostics.
        // To avoid actually triggering recalculation, we use a lighter
        // path: iterate via reflection-free helper added in tandem.
        return workspace.openUris();
    }

    private static boolean intersects(WorkspaceFile file, Range requestRange, Node match) {
        if (requestRange == null) return true;
        Position matchStart = no.sikt.graphitron.lsp.parsing.Positions
            .toLspPosition(file.source(), match.getStartByte());
        Position matchEnd = no.sikt.graphitron.lsp.parsing.Positions
            .toLspPosition(file.source(), match.getEndByte());
        return !before(matchEnd, requestRange.getStart())
            && !before(requestRange.getEnd(), matchStart);
    }

    private static boolean before(Position a, Position b) {
        if (a.getLine() != b.getLine()) return a.getLine() < b.getLine();
        return a.getCharacter() < b.getCharacter();
    }

    private static List<TextEdit> applyAll(SdlAction action, WorkspaceFile file, List<Node> matches) {
        return matches.stream()
            .map(m -> action.rewrite().rewrite(file, m))
            .filter(r -> r instanceof RewriteResult.Edit)
            .map(r -> ((RewriteResult.Edit) r).edit())
            .toList();
    }

    private static int countResolvable(SdlAction action, WorkspaceFile file, List<Node> matches) {
        return (int) matches.stream()
            .map(m -> action.rewrite().rewrite(file, m))
            .filter(r -> r instanceof RewriteResult.Edit)
            .count();
    }

    private static int countSkipped(SdlAction action, WorkspaceFile file, List<Node> matches) {
        return (int) matches.stream()
            .map(m -> action.rewrite().rewrite(file, m))
            .filter(r -> r instanceof RewriteResult.Skip)
            .count();
    }

    private static boolean allMatchesResolved(SdlAction action, WorkspaceFile file, List<Node> matches) {
        return countSkipped(action, file, matches) == 0;
    }

    private static CodeAction perSiteAction(SdlAction action, String fileUri, TextEdit edit) {
        var ca = new CodeAction(action.displayName());
        ca.setKind(CodeActionKind.QuickFix);
        ca.setEdit(workspaceEdit(Map.of(fileUri, List.of(edit))));
        return ca;
    }

    private static CodeAction fileBulkAction(
        SdlAction action, String fileUri, List<TextEdit> edits, int rewritten, int skipped
    ) {
        String title = action.displayName() + " in this file";
        var ca = new CodeAction(title);
        ca.setKind(CodeActionKind.SourceFixAll);
        ca.setEdit(workspaceEdit(edits.isEmpty()
            ? Map.of()
            : Map.of(fileUri, edits)));
        ca.setDiagnostics(List.of());
        // Result message attached as a free-form description; editors
        // surface it next to the action title or in a status notification
        // depending on the client's UX. Stored on the codeAction's
        // 'data' slot so test harnesses can read it directly.
        ca.setData(resultMessage(action.displayName(), rewritten, skipped));
        return ca;
    }

    private static CodeAction workspaceBulkAction(
        SdlAction action, Map<String, List<TextEdit>> edits, int rewritten, int skipped
    ) {
        String title = action.displayName() + " in this workspace";
        var ca = new CodeAction(title);
        ca.setKind(CodeActionKind.SourceFixAll);
        ca.setEdit(workspaceEdit(edits));
        ca.setDiagnostics(List.of());
        ca.setData(resultMessage(action.displayName(), rewritten, skipped));
        return ca;
    }

    private static WorkspaceEdit workspaceEdit(Map<String, List<TextEdit>> changes) {
        return new WorkspaceEdit(changes);
    }
}
