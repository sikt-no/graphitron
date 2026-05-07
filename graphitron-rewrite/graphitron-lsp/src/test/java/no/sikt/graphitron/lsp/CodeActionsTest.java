package no.sikt.graphitron.lsp;

import no.sikt.graphitron.lsp.code_action.CodeActions;
import no.sikt.graphitron.lsp.state.Workspace;
import no.sikt.graphitron.rewrite.catalog.CompletionData;
import org.eclipse.lsp4j.CodeAction;
import org.eclipse.lsp4j.CodeActionContext;
import org.eclipse.lsp4j.CodeActionParams;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Code-action provider exercised end-to-end against an in-memory
 * {@link Workspace}. Three activation points: per-site quick-fix,
 * file-scoped bulk, workspace-scoped bulk; each produces a
 * {@link org.eclipse.lsp4j.WorkspaceEdit} on resolvable matches and
 * partitions skips into the result message.
 */
class CodeActionsTest {

    private static final Map<String, String> NAMED_REFS = Map.of(
        "FilmService", "com.example.FilmService",
        "Conditions", "com.example.Conditions"
    );

    @Test
    void perSiteQuickFix_offeredOnResolvableLiteral() {
        var workspace = workspaceWith("file:///a.graphqls", """
            type Query {
                x: Int @service(service: {name: "FilmService", method: "list"})
            }
            """);

        var actions = invoke(workspace, "file:///a.graphqls", cursorAt(1, 38));

        var perSite = perSiteOnly(actions);
        assertThat(perSite).hasSize(1);
        var workspaceEdit = perSite.get(0).getEdit();
        assertThat(workspaceEdit.getChanges()).containsOnlyKeys("file:///a.graphqls");
        var edits = workspaceEdit.getChanges().get("file:///a.graphqls");
        assertThat(edits).hasSize(1);
        assertThat(edits.get(0).getNewText()).isEqualTo("className: \"com.example.FilmService\"");
    }

    @Test
    void perSiteQuickFix_notOfferedOnUnresolvableLiteral() {
        var workspace = workspaceWith("file:///a.graphqls", """
            type Query {
                x: Int @service(service: {name: "Unknown", method: "list"})
            }
            """);

        var actions = invoke(workspace, "file:///a.graphqls", cursorAt(1, 38));

        assertThat(perSiteOnly(actions)).isEmpty();
    }

    @Test
    void fileBulk_composesEveryResolvableSiteIntoOneWorkspaceEdit() {
        var workspace = workspaceWith("file:///a.graphqls", """
            type Query {
                a: Int @service(service: {name: "FilmService", method: "list"})
                b: Int @condition(condition: {name: "Conditions"})
                c: Int @service(service: {name: "Unknown", method: "list"})
            }
            """);

        var actions = invoke(workspace, "file:///a.graphqls", fullDocRange());

        var fileBulk = bulkByTitle(actions, "Migrate `name:` to `className:` in this file");
        assertThat(fileBulk).isNotNull();
        var edits = fileBulk.getEdit().getChanges().get("file:///a.graphqls");
        assertThat(edits).hasSize(2);
        assertThat(fileBulk.getData()).asString()
            .isEqualTo("Migrated 2 legacy ExternalCodeReference.name sites; "
                + "1 unresolvable, see problems panel.");
    }

    @Test
    void fileBulk_resolvableOnlyMessage() {
        var workspace = workspaceWith("file:///a.graphqls", """
            type Query {
                a: Int @service(service: {name: "FilmService", method: "list"})
                b: Int @condition(condition: {name: "Conditions"})
            }
            """);

        var actions = invoke(workspace, "file:///a.graphqls", fullDocRange());

        var fileBulk = bulkByTitle(actions, "Migrate `name:` to `className:` in this file");
        assertThat(fileBulk.getData()).asString()
            .isEqualTo("Migrated 2 legacy ExternalCodeReference.name sites.");
    }

    @Test
    void fileBulk_unresolvableOnlyMessage() {
        var workspace = workspaceWith("file:///a.graphqls", """
            type Query {
                a: Int @service(service: {name: "Ghost", method: "list"})
            }
            """);

        var actions = invoke(workspace, "file:///a.graphqls", fullDocRange());

        var fileBulk = bulkByTitle(actions, "Migrate `name:` to `className:` in this file");
        assertThat(fileBulk.getData()).asString()
            .isEqualTo("No resolvable legacy sites; 1 unresolvable, see problems panel.");
    }

    @Test
    void workspaceBulk_composesAcrossOpenFiles() {
        var workspace = new Workspace(catalog());
        workspace.didOpen("file:///a.graphqls", 1, """
            type Query {
                a: Int @service(service: {name: "FilmService", method: "list"})
            }
            """);
        workspace.didOpen("file:///b.graphqls", 1, """
            type Query {
                b: Int @condition(condition: {name: "Conditions"})
            }
            """);

        var actions = invoke(workspace, "file:///a.graphqls", fullDocRange());

        var wsBulk = bulkByTitle(actions, "Migrate `name:` to `className:` in this workspace");
        assertThat(wsBulk).isNotNull();
        assertThat(wsBulk.getEdit().getChanges()).containsOnlyKeys(
            "file:///a.graphqls", "file:///b.graphqls");
        assertThat(wsBulk.getData()).asString()
            .isEqualTo("Migrated 2 legacy ExternalCodeReference.name sites.");
    }

    @Test
    void noActivationsWhenFileHasNoLegacySites() {
        var workspace = workspaceWith("file:///a.graphqls", """
            type Query {
                x: Int @service(service: {className: "com.example.FilmService", method: "list"})
            }
            """);

        var actions = invoke(workspace, "file:///a.graphqls", fullDocRange());

        assertThat(actions).isEmpty();
    }

    private static List<CodeAction> perSiteOnly(List<? extends org.eclipse.lsp4j.jsonrpc.messages.Either<org.eclipse.lsp4j.Command, CodeAction>> actions) {
        return actions.stream()
            .filter(e -> e.isRight())
            .map(e -> e.getRight())
            .filter(ca -> "Migrate `name:` to `className:`".equals(ca.getTitle()))
            .toList();
    }

    private static CodeAction bulkByTitle(
        List<? extends org.eclipse.lsp4j.jsonrpc.messages.Either<org.eclipse.lsp4j.Command, CodeAction>> actions,
        String title
    ) {
        return actions.stream()
            .filter(e -> e.isRight())
            .map(e -> e.getRight())
            .filter(ca -> title.equals(ca.getTitle()))
            .findFirst()
            .orElse(null);
    }

    private static Workspace workspaceWith(String uri, String source) {
        var workspace = new Workspace(catalog());
        workspace.didOpen(uri, 1, source);
        return workspace;
    }

    private static CompletionData catalog() {
        return new CompletionData(List.of(), List.of(), List.of(), NAMED_REFS);
    }

    private static List<? extends org.eclipse.lsp4j.jsonrpc.messages.Either<org.eclipse.lsp4j.Command, CodeAction>> invoke(
        Workspace workspace, String uri, Range range
    ) {
        var params = new CodeActionParams(
            new TextDocumentIdentifier(uri), range, new CodeActionContext(List.of()));
        return CodeActions.compute(params, workspace);
    }

    private static Range cursorAt(int line, int character) {
        var p = new Position(line, character);
        return new Range(p, p);
    }

    private static Range fullDocRange() {
        return new Range(new Position(0, 0), new Position(1000, 0));
    }
}
