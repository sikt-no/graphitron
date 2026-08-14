package no.sikt.graphitron.lsp.code_action;

import no.sikt.graphitron.lsp.facts.LintFixes;
import no.sikt.graphitron.model.read.StoreHandle;
import org.eclipse.lsp4j.CodeAction;
import org.eclipse.lsp4j.CodeActionKind;
import org.eclipse.lsp4j.CodeActionParams;
import org.eclipse.lsp4j.Command;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.TextEdit;
import org.eclipse.lsp4j.WorkspaceEdit;
import org.eclipse.lsp4j.jsonrpc.messages.Either;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The finding-keyed {@code QuickFix} branch: projects the corrections the graph's linter computed
 * into editor {@link CodeAction}s, reading them through {@link LintFixes}. This is deliberately
 * <em>not</em> a reuse of the detector-driven {@link SdlActions} path: that path re-scans each open
 * document through an action's detector, whereas this branch offers a fix the rule already worked
 * out. The build stays the single evaluator; the LSP only projects, sharing the
 * {@link WorkspaceEdit} / {@link TextEdit} emit primitives and never recomputing how to fix a rule.
 *
 * <p>The store is what makes a fix offerable at all here, so a session without one offers none. Which
 * fixes those are is a question about one document's text, and {@link LintFixes} answers it: a stored
 * edit is a span in the text the rule read, so it is offered only while the buffer still holds that
 * text. The incumbent gated on the build's snapshot being the freshest one instead, which is a
 * coarser question and the wrong one: a snapshot stays fresh across every keystroke after the build
 * that produced it, so the gate passed exactly when the ranges had moved.
 */
public final class LintQuickFixes {

    private LintQuickFixes() {}

    /**
     * @param buffer the cursor document's text as the workspace holds it, which is both what the
     *               stored fixes are checked against and what the edits will be applied to
     */
    public static List<Either<Command, CodeAction>> compute(
        CodeActionParams params, Optional<StoreHandle> store, byte[] buffer
    ) {
        if (store.isEmpty()) {
            return List.of();
        }
        String uri = params.getTextDocument().getUri();
        var out = new ArrayList<Either<Command, CodeAction>>();
        for (var fix : LintFixes.forDocument(store.get(), uri, buffer)) {
            if (!intersectsRequest(params.getRange(), fix.findingLine())) {
                continue;
            }
            out.add(Either.forRight(toCodeAction(uri, fix, params)));
        }
        return out;
    }

    /**
     * The request range typically arrives as the diagnostic's own range (column-to-end-of-line on the
     * finding's line) when the user invokes a quick fix on a squiggle. A line-level overlap is the
     * forgiving check: offer the fix when the request spans the finding's line. A null range (the
     * whole document) always matches.
     */
    private static boolean intersectsRequest(Range request, int findingLine) {
        if (request == null) return true;
        int line = findingLine - 1;
        return request.getStart().getLine() <= line && line <= request.getEnd().getLine();
    }

    private static CodeAction toCodeAction(String uri, LintFixes.Fix fix, CodeActionParams params) {
        var edits = new ArrayList<TextEdit>();
        for (LintFixes.Edit edit : fix.edits()) {
            edits.add(new TextEdit(
                new Range(position(edit.startLine(), edit.startColumn()),
                    position(edit.endLine(), edit.endColumn())),
                edit.replacement()));
        }
        var ca = new CodeAction(fix.description());
        ca.setKind(CodeActionKind.QuickFix);
        ca.setEdit(new WorkspaceEdit(Map.of(uri, edits)));
        ca.setDiagnostics(matchingRequestDiagnostics(params, fix.findingLine()));
        return ca;
    }

    /**
     * Request-context diagnostics on the finding's line, attached so the client links the action to
     * the squiggle it fixes. Empty when the request carried no diagnostics (some clients invoke code
     * actions without context); the action is still offered.
     */
    private static List<Diagnostic> matchingRequestDiagnostics(CodeActionParams params, int findingLine) {
        if (params.getContext() == null || params.getContext().getDiagnostics() == null) {
            return List.of();
        }
        int line = findingLine - 1;
        var matched = new ArrayList<Diagnostic>();
        for (Diagnostic d : params.getContext().getDiagnostics()) {
            if (d.getRange() != null
                && d.getRange().getStart().getLine() <= line
                && line <= d.getRange().getEnd().getLine()) {
                matched.add(d);
            }
        }
        return matched;
    }

    /** A stored position (1-based line and column) as an lsp4j {@link Position} (0-based). */
    private static Position position(int line, int column) {
        return new Position(Math.max(0, line - 1), Math.max(0, column - 1));
    }
}
