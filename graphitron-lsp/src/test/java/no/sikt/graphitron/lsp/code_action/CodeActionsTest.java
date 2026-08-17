package no.sikt.graphitron.lsp.code_action;

import io.github.treesitter.jtreesitter.Node;
import no.sikt.graphitron.lsp.code_action.SdlAction.RewriteResult;
import no.sikt.graphitron.lsp.parsing.Directives;
import no.sikt.graphitron.lsp.parsing.LspVocabulary;
import no.sikt.graphitron.lsp.parsing.Nodes;
import no.sikt.graphitron.lsp.parsing.Positions;
import no.sikt.graphitron.lsp.parsing.SchemaCoordinate;
import no.sikt.graphitron.lsp.state.FileSnapshot;
import no.sikt.graphitron.lsp.state.Workspace;
import no.sikt.graphitron.rewrite.catalog.CompletionData;
import org.eclipse.lsp4j.CodeAction;
import org.eclipse.lsp4j.CodeActionContext;
import org.eclipse.lsp4j.CodeActionParams;
import org.eclipse.lsp4j.Command;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DiagnosticSeverity;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.TextEdit;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Code-action provider exercised end-to-end against an in-memory
 * {@link Workspace}. Three activation points: per-site quick-fix,
 * file-scoped bulk, workspace-scoped bulk; each produces a
 * {@link org.eclipse.lsp4j.WorkspaceEdit} on resolvable matches and
 * partitions skips into the result message.
 *
 * <p>Driven through the {@link CodeActions#compute(CodeActionParams, Workspace, List)}
 * seam with the test-local {@link #renameConnectionAction()}, because
 * {@link SdlActions} currently registers none: the machinery under test is the
 * activation and message logic, which stays covered between registered migrations.
 * The action targets a live deprecation marker
 * ({@code @asConnection(connectionName:)}) so it remains consistent with the
 * coupling {@code SdlActionDriftTest} enforces on registered actions.
 */
class CodeActionsTest {

    private static final String TITLE = "Rename the connection";

    /** Values the test action resolves; anything else yields a {@link RewriteResult.Skip}. */
    private static final Map<String, String> KNOWN = Map.of(
        "FilmConn", "FilmConnection",
        "ActorConn", "ActorConnection"
    );

    @Test
    void perSiteQuickFix_offeredOnResolvableLiteral() {
        var workspace = workspaceWith("file:///a.graphqls", """
            type Query {
                x: Int @asConnection(connectionName: "FilmConn")
            }
            """);

        var actions = invoke(workspace, "file:///a.graphqls", cursorAt(1, 41));

        var perSite = perSiteOnly(actions);
        assertThat(perSite).hasSize(1);
        var workspaceEdit = perSite.get(0).getEdit();
        assertThat(workspaceEdit.getChanges()).containsOnlyKeys("file:///a.graphqls");
        var edits = workspaceEdit.getChanges().get("file:///a.graphqls");
        assertThat(edits).hasSize(1);
        assertThat(edits.get(0).getNewText()).isEqualTo("\"FilmConnection\"");
    }

    @Test
    void perSiteQuickFix_notOfferedOnUnresolvableLiteral() {
        var workspace = workspaceWith("file:///a.graphqls", """
            type Query {
                x: Int @asConnection(connectionName: "Unknown")
            }
            """);

        var actions = invoke(workspace, "file:///a.graphqls", cursorAt(1, 41));

        assertThat(perSiteOnly(actions)).isEmpty();
    }

    @Test
    void fileBulk_composesEveryResolvableSiteIntoOneWorkspaceEdit() {
        var workspace = workspaceWith("file:///a.graphqls", """
            type Query {
                a: Int @asConnection(connectionName: "FilmConn")
                b: Int @asConnection(connectionName: "ActorConn")
                c: Int @asConnection(connectionName: "Unknown")
            }
            """);

        var actions = invoke(workspace, "file:///a.graphqls", fullDocRange());

        var fileBulk = bulkByTitle(actions, TITLE + " in this file");
        assertThat(fileBulk).isNotNull();
        var edits = fileBulk.getEdit().getChanges().get("file:///a.graphqls");
        assertThat(edits).hasSize(2);
        assertThat(fileBulk.getData()).asString()
            .isEqualTo("Migrated 2 legacy rewrite sites; "
                + "1 unresolvable, see problems panel.");
    }

    @Test
    void fileBulk_resolvableOnlyMessage() {
        var workspace = workspaceWith("file:///a.graphqls", """
            type Query {
                a: Int @asConnection(connectionName: "FilmConn")
                b: Int @asConnection(connectionName: "ActorConn")
            }
            """);

        var actions = invoke(workspace, "file:///a.graphqls", fullDocRange());

        var fileBulk = bulkByTitle(actions, TITLE + " in this file");
        assertThat(fileBulk.getData()).asString()
            .isEqualTo("Migrated 2 legacy rewrite sites.");
    }

    @Test
    void fileBulk_unresolvableOnlyMessage() {
        var workspace = workspaceWith("file:///a.graphqls", """
            type Query {
                a: Int @asConnection(connectionName: "Missing")
            }
            """);

        var actions = invoke(workspace, "file:///a.graphqls", fullDocRange());

        var fileBulk = bulkByTitle(actions, TITLE + " in this file");
        assertThat(fileBulk.getData()).asString()
            .isEqualTo("No resolvable legacy sites; 1 unresolvable, see problems panel.");
    }

    @Test
    void workspaceBulk_composesAcrossOpenFiles() {
        var workspace = new Workspace(CompletionData.empty());
        workspace.didOpen("file:///a.graphqls", 1, """
            type Query {
                a: Int @asConnection(connectionName: "FilmConn")
            }
            """);
        workspace.didOpen("file:///b.graphqls", 1, """
            type Query {
                b: Int @asConnection(connectionName: "ActorConn")
            }
            """);

        var actions = invoke(workspace, "file:///a.graphqls", fullDocRange());

        var wsBulk = bulkByTitle(actions, TITLE + " in this workspace");
        assertThat(wsBulk).isNotNull();
        assertThat(wsBulk.getEdit().getChanges()).containsOnlyKeys(
            "file:///a.graphqls", "file:///b.graphqls");
        assertThat(wsBulk.getData()).asString()
            .isEqualTo("Migrated 2 legacy rewrite sites.");
    }

    @Test
    void perSiteQuickFix_offeredEvenWithUnrelatedSiblingDiagnosticInContext() {
        // The per-site quick-fix is mechanically safe; an unrelated
        // diagnostic on the same range should not gate it. Production
        // path's intersects(...) filter does not consult the
        // CodeActionContext's diagnostic list, so the assertion is a
        // regression seam: any future change that starts gating on
        // context.diagnostics would flip this red.
        var workspace = workspaceWith("file:///a.graphqls", """
            type Query {
                x: Int @asConnection(connectionName: "FilmConn")
            }
            """);
        var siblingDiagnostic = new Diagnostic(
            new Range(new Position(1, 0), new Position(1, 80)),
            "unrelated: malformed argMapping",
            DiagnosticSeverity.Warning, "graphitron-lsp");

        var actions = invokeWithDiagnostics(
            workspace, "file:///a.graphqls", cursorAt(1, 41),
            List.of(siblingDiagnostic));

        var perSite = perSiteOnly(actions);
        assertThat(perSite).hasSize(1);
        var edits = perSite.get(0).getEdit().getChanges().get("file:///a.graphqls");
        assertThat(edits).hasSize(1);
        assertThat(edits.get(0).getNewText()).isEqualTo("\"FilmConnection\"");
    }

    @Test
    void noActivationsWhenFileHasNoMatchingSites() {
        var workspace = workspaceWith("file:///a.graphqls", """
            type Query {
                x: Int @asConnection
            }
            """);

        var actions = invoke(workspace, "file:///a.graphqls", fullDocRange());

        assertThat(actions).isEmpty();
    }

    // ===== Test-local action =====

    /**
     * Rewrites {@code @asConnection(connectionName: "X")} to the mapped name when
     * {@code X} is in {@link #KNOWN}. Deliberately trivial: the assertions above are
     * about {@link CodeActions}, not about this rewrite.
     */
    private static SdlAction renameConnectionAction() {
        return new SdlAction(
            TITLE,
            Set.of(new SchemaCoordinate.DirectiveArg("asConnection", "connectionName")),
            CodeActionsTest::detectConnectionNames,
            CodeActionsTest::rewriteConnectionName
        );
    }

    private static final SchemaCoordinate CONNECTION_NAME_COORD =
        new SchemaCoordinate.DirectiveArg("asConnection", "connectionName");

    private static Stream<Node> detectConnectionNames(FileSnapshot file) {
        var vocab = LspVocabulary.load();
        var matches = new ArrayList<Node>();
        for (var directive : Directives.findAll(file.tree().getRootNode())) {
            for (var leaf : vocab.leafCoordinates(directive, file.source())) {
                if (CONNECTION_NAME_COORD.equals(leaf.coord())) {
                    matches.add(leaf.valueNode());
                }
            }
        }
        return matches.stream();
    }

    private static RewriteResult rewriteConnectionName(FileSnapshot file, Node match) {
        String raw = Nodes.unquote(Nodes.text(match, file.source()));
        String resolved = KNOWN.get(raw);
        if (resolved == null) return new RewriteResult.Skip(raw);
        Position start = Positions.toLspPosition(file.source(), match.getStartByte());
        Position end = Positions.toLspPosition(file.source(), match.getEndByte());
        return new RewriteResult.Edit(new TextEdit(new Range(start, end), "\"" + resolved + "\""));
    }

    // ===== Harness =====

    private static List<CodeAction> perSiteOnly(List<? extends Either<Command, CodeAction>> actions) {
        return actions.stream()
            .filter(Either::isRight)
            .map(Either::getRight)
            .filter(ca -> TITLE.equals(ca.getTitle()))
            .toList();
    }

    private static CodeAction bulkByTitle(
        List<? extends Either<Command, CodeAction>> actions, String title
    ) {
        return actions.stream()
            .filter(Either::isRight)
            .map(Either::getRight)
            .filter(ca -> title.equals(ca.getTitle()))
            .findFirst()
            .orElse(null);
    }

    private static Workspace workspaceWith(String uri, String source) {
        var workspace = new Workspace(CompletionData.empty());
        workspace.didOpen(uri, 1, source);
        return workspace;
    }

    private static List<? extends Either<Command, CodeAction>> invoke(
        Workspace workspace, String uri, Range range
    ) {
        return invokeWithDiagnostics(workspace, uri, range, List.of());
    }

    private static List<? extends Either<Command, CodeAction>> invokeWithDiagnostics(
        Workspace workspace, String uri, Range range, List<Diagnostic> diagnostics
    ) {
        var params = new CodeActionParams(
            new TextDocumentIdentifier(uri), range, new CodeActionContext(diagnostics));
        return CodeActions.compute(params, workspace, List.of(renameConnectionAction()));
    }

    private static Range cursorAt(int line, int character) {
        var p = new Position(line, character);
        return new Range(p, p);
    }

    private static Range fullDocRange() {
        return new Range(new Position(0, 0), new Position(1000, 0));
    }
}
