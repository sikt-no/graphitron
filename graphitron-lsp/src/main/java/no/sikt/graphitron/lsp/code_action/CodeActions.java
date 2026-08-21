package no.sikt.graphitron.lsp.code_action;

import io.github.treesitter.jtreesitter.Node;
import no.sikt.graphitron.lsp.code_action.SdlAction.RewriteResult;
import no.sikt.graphitron.lsp.parsing.Nodes;
import no.sikt.graphitron.lsp.state.FileSnapshot;
import no.sikt.graphitron.lsp.state.StoreRead;
import no.sikt.graphitron.lsp.state.Workspace;
import no.sikt.graphitron.model.boot.StoreAnswer;
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
        // Phrase the displayName for use after a count. Deliberately neutral, so the
        // wording does not have to be revisited per registered action.
        return "rewrite sites";
    }

    public static List<Either<Command, CodeAction>> compute(
        CodeActionParams params, Workspace workspace
    ) {
        return compute(params, workspace, SdlActions.all());
    }

    /**
     * Seam taking the {@link SdlAction} list explicitly, so {@code CodeActionsTest} can
     * exercise the three activation points and the result-message branches against a
     * test-local action. The public overload supplies {@link SdlActions#all}.
     */
    static List<Either<Command, CodeAction>> compute(
        CodeActionParams params, Workspace workspace, List<SdlAction> actions
    ) {
        String fileUri = params.getTextDocument().getUri();

        // One consistent generation of every open file: the cursor file's
        // per-site and file-bulk actions and the workspace-bulk action all read
        // the same snapshots, so a composed multi-document WorkspaceEdit can never
        // pair a stale byte offset in one file with a fresh tree in another.
        var out = workspace.withAllViews(views -> {
            var file = views.get(fileUri);
            if (file == null) return null;
            var acc = new ArrayList<Either<Command, CodeAction>>();
            for (var action : actions) {
                // Materialise the detector eagerly: per-site filter, plus
                // bulk activation, plus the empty-list guard, all walk the
                // same list.
                List<Node> matches = action.detector().detect(file).toList();
                if (matches.isEmpty()) continue;

                // Per-site: the cursor or selection range is intersected
                // against each match. LSP code-action requests bring the
                // request range; we pick matches whose byte range overlaps
                // the request range in source-bytes terms.
                for (var match : matches) {
                    if (!intersects(file, params.getRange(), match)) continue;
                    var result = action.rewrite().rewrite(file, match);
                    if (result instanceof RewriteResult.Edit edit) {
                        acc.add(Either.forRight(perSiteAction(action, fileUri, edit.edit())));
                    }
                }

                // File-scoped bulk: composes every resolvable match in
                // this document.
                var fileSiteEdits = applyAll(action, file, matches);
                if (!fileSiteEdits.isEmpty() || !allMatchesResolved(action, file, matches)) {
                    acc.add(Either.forRight(fileBulkAction(
                        action, fileUri, fileSiteEdits,
                        countResolvable(action, file, matches),
                        countSkipped(action, file, matches))));
                }

                // Workspace-scoped bulk: composes resolvable matches
                // across every open file (including this one).
                Map<String, List<TextEdit>> wsEdits = new LinkedHashMap<>();
                int wsResolvable = 0;
                int wsSkipped = 0;
                for (var entry : views.entrySet()) {
                    var wsFile = entry.getValue();
                    var wsMatches = action.detector().detect(wsFile).toList();
                    if (wsMatches.isEmpty()) continue;
                    var edits = applyAll(action, wsFile, wsMatches);
                    if (!edits.isEmpty()) wsEdits.put(entry.getKey(), edits);
                    wsResolvable += countResolvable(action, wsFile, wsMatches);
                    wsSkipped += countSkipped(action, wsFile, wsMatches);
                }
                if (wsResolvable > 0 || wsSkipped > 0) {
                    acc.add(Either.forRight(workspaceBulkAction(
                        action, wsEdits, wsResolvable, wsSkipped)));
                }
            }
            return acc;
        });
        // Cursor file not open: no SDL actions and, matching the prior behaviour,
        // no lint quick-fixes either.
        if (out == null) return List.of();
        // The finding-keyed lint QuickFix branch, alongside the detector-driven SdlActions above. It
        // offers the fixes the rules computed build-side, sharing only the WorkspaceEdit / TextEdit /
        // QuickFix emit primitives. Its own read rather than one wrapped around the whole request: the
        // two branches read disjoint material, the detectors nothing but buffers and this nothing but
        // rows, so there is no pair of reads a shared transaction would keep consistent.
        out.addAll(workspace.withView(fileUri, List.<Either<Command, CodeAction>>of(), file ->
            switch (workspace.answering(StoreRead.CODE_ACTIONS, fileUri, store ->
                LintQuickFixes.compute(params, store, file.source()))) {
                case StoreAnswer.Answered<List<Either<Command, CodeAction>>> answered ->
                    answered.value();
                // The SDL actions computed above still ship; only the row-backed quick-fixes are
                // missing, which is what a file the store holds no findings for already offers. The
                // store boundary has already warned, naming the read.
                case StoreAnswer.OutOfBudget<List<Either<Command, CodeAction>>> ignored ->
                    List.<Either<Command, CodeAction>>of();
            }));
        return out;
    }

    private static boolean intersects(FileSnapshot file, Range requestRange, Node match) {
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

    private static List<TextEdit> applyAll(SdlAction action, FileSnapshot file, List<Node> matches) {
        return matches.stream()
            .map(m -> action.rewrite().rewrite(file, m))
            .filter(r -> r instanceof RewriteResult.Edit)
            .map(r -> ((RewriteResult.Edit) r).edit())
            .toList();
    }

    private static int countResolvable(SdlAction action, FileSnapshot file, List<Node> matches) {
        return (int) matches.stream()
            .map(m -> action.rewrite().rewrite(file, m))
            .filter(r -> r instanceof RewriteResult.Edit)
            .count();
    }

    private static int countSkipped(SdlAction action, FileSnapshot file, List<Node> matches) {
        return (int) matches.stream()
            .map(m -> action.rewrite().rewrite(file, m))
            .filter(r -> r instanceof RewriteResult.Skip)
            .count();
    }

    private static boolean allMatchesResolved(SdlAction action, FileSnapshot file, List<Node> matches) {
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
