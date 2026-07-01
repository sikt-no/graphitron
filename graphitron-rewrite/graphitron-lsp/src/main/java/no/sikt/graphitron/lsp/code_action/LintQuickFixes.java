package no.sikt.graphitron.lsp.code_action;

import graphql.language.SourceLocation;
import no.sikt.graphitron.lsp.state.Workspace;
import no.sikt.graphitron.rewrite.BuildWarning;
import no.sikt.graphitron.rewrite.ValidationReport;
import no.sikt.graphitron.rewrite.catalog.LspSchemaSnapshot;
import no.sikt.graphitron.rewrite.lint.LintFix;
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

/**
 * The finding-keyed {@code QuickFix} branch (R398): projects a fix-bearing lint
 * {@link BuildWarning.LintFinding} in the build's {@link ValidationReport} into an editor
 * {@code QuickFix} {@link CodeAction}. This is deliberately <em>not</em> a reuse of the
 * detector-driven {@link SdlActions} path: that path re-scans each open document through an action's
 * detector and ignores the request's diagnostics, whereas this branch reads the finding (and the
 * {@link LintFix} the rule already computed build-side) straight off the report. The build stays the
 * single evaluator; the LSP only projects the fix, sharing the {@link WorkspaceEdit} / {@link TextEdit}
 * emit primitives, never recomputing how to fix a rule.
 *
 * <p>Respects the R139 freshness-aware silence policy exactly as the diagnostics replay does: fixes
 * are offered only when the snapshot is {@link LspSchemaSnapshot.Built.Current}, because a finding
 * from a stale build may not reflect the buffer the user is editing, and an edit derived from a stale
 * range could corrupt the document.
 */
public final class LintQuickFixes {

    private LintQuickFixes() {}

    public static List<Either<Command, CodeAction>> compute(CodeActionParams params, Workspace workspace) {
        // R139: only a current snapshot's findings reflect the edited buffer.
        if (!(workspace.snapshot() instanceof LspSchemaSnapshot.Built.Current)) {
            return List.of();
        }
        String uri = params.getTextDocument().getUri();
        ValidationReport report = workspace.validationReport();
        if (!report.sourceUris().contains(uri)) {
            return List.of();
        }
        var out = new ArrayList<Either<Command, CodeAction>>();
        for (BuildWarning warning : report.warnings()) {
            if (!(warning instanceof BuildWarning.LintFinding finding) || finding.fix().isEmpty()) {
                continue;
            }
            SourceLocation loc = finding.location();
            if (!sameFile(uri, loc) || !intersectsRequest(params.getRange(), loc)) {
                continue;
            }
            out.add(Either.forRight(toCodeAction(uri, finding, finding.fix().get(), params)));
        }
        return out;
    }

    private static boolean sameFile(String uri, SourceLocation loc) {
        if (loc == null || loc.getLine() <= 0) return false;
        String sourceName = loc.getSourceName();
        if (sourceName == null || sourceName.isEmpty()) return false;
        return uri.equals(ValidationReport.canonicalUri(sourceName));
    }

    /**
     * The request range typically arrives as the diagnostic's own range (column-to-end-of-line on the
     * finding's line) when the user invokes a quick fix on a squiggle. A line-level overlap is the
     * forgiving check: offer the fix when the request spans the finding's line. A null range (the
     * whole document) always matches.
     */
    private static boolean intersectsRequest(Range request, SourceLocation loc) {
        if (request == null) return true;
        int findingLine = loc.getLine() - 1;
        return request.getStart().getLine() <= findingLine && findingLine <= request.getEnd().getLine();
    }

    private static CodeAction toCodeAction(
        String uri, BuildWarning.LintFinding finding, LintFix fix, CodeActionParams params
    ) {
        var edits = new ArrayList<TextEdit>();
        for (LintFix.Edit edit : fix.edits()) {
            edits.add(new TextEdit(new Range(position(edit.start()), position(edit.end())), edit.replacement()));
        }
        var ca = new CodeAction(fix.description());
        ca.setKind(CodeActionKind.QuickFix);
        ca.setEdit(new WorkspaceEdit(Map.of(uri, edits)));
        ca.setDiagnostics(matchingRequestDiagnostics(params, finding.location()));
        return ca;
    }

    /**
     * Request-context diagnostics on the finding's line, attached so the client links the action to
     * the squiggle it fixes. Empty when the request carried no diagnostics (some clients invoke code
     * actions without context); the action is still offered.
     */
    private static List<Diagnostic> matchingRequestDiagnostics(CodeActionParams params, SourceLocation loc) {
        if (params.getContext() == null || params.getContext().getDiagnostics() == null) {
            return List.of();
        }
        int findingLine = loc.getLine() - 1;
        var matched = new ArrayList<Diagnostic>();
        for (Diagnostic d : params.getContext().getDiagnostics()) {
            if (d.getRange() != null
                && d.getRange().getStart().getLine() <= findingLine
                && findingLine <= d.getRange().getEnd().getLine()) {
                matched.add(d);
            }
        }
        return matched;
    }

    /** graphql-java {@link SourceLocation} (1-based line/column) to lsp4j {@link Position} (0-based). */
    private static Position position(SourceLocation loc) {
        return new Position(Math.max(0, loc.getLine() - 1), Math.max(0, loc.getColumn() - 1));
    }
}
