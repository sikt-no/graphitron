package no.sikt.graphitron.lsp;

import no.sikt.graphitron.lsp.code_action.SdlAction.RewriteResult;
import no.sikt.graphitron.lsp.code_action.SdlActions;
import no.sikt.graphitron.lsp.state.WorkspaceFile;
import no.sikt.graphitron.rewrite.catalog.CompletionData;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Detector + rewrite slot of the R93 {@code name → className} migration
 * exercised against synthetic SDL fixtures. Drift / activation-point
 * coverage lives in {@code SdlActionDriftTest} and
 * {@code CodeActionsTest}; this test is about the per-instance
 * primitive's contract.
 */
class SdlActionTest {

    private static final Map<String, String> NAMED_REFS = Map.of(
        "FilmService", "com.example.FilmService",
        "Conditions", "com.example.Conditions"
    );

    @Test
    void detector_findsLegacyNameSitesAcrossFlatAndNestedBindings() {
        var file = file("""
            type Query {
                a: Int @service(service: {name: "FilmService", method: "list"})
                b: Int @reference(path: [{condition: {name: "Conditions"}}])
            }
            """);
        var action = SdlActions.externalCodeReferenceNameToClassName(catalog());

        var matches = action.detector().detect(file).toList();

        assertThat(matches).hasSize(2);
    }

    @Test
    void detector_skipsSitesWhereClassNameIsAlreadySet() {
        var file = file("""
            type Query {
                a: Int @service(service: {className: "com.example.FilmService", method: "list"})
                b: Int @service(service: {name: "FilmService", className: "com.example.FilmService"})
            }
            """);
        var action = SdlActions.externalCodeReferenceNameToClassName(catalog());

        var matches = action.detector().detect(file).toList();

        assertThat(matches).isEmpty();
    }

    @Test
    void detector_skipsModernSites() {
        var file = file("""
            type Query {
                a: Int @service(service: {className: "com.example.FilmService", method: "list"})
            }
            """);
        var action = SdlActions.externalCodeReferenceNameToClassName(catalog());

        var matches = action.detector().detect(file).toList();

        assertThat(matches).isEmpty();
    }

    @Test
    void rewrite_yieldsEditWhenNameResolves() {
        var file = file("""
            type Query {
                x: Int @service(service: {name: "FilmService", method: "list"})
            }
            """);
        var action = SdlActions.externalCodeReferenceNameToClassName(catalog());
        var match = action.detector().detect(file).toList().get(0);

        var result = action.rewrite().rewrite(file, match);

        assertThat(result).isInstanceOf(RewriteResult.Edit.class);
        var edit = ((RewriteResult.Edit) result).edit();
        assertThat(edit.getNewText()).isEqualTo("className: \"com.example.FilmService\"");
    }

    @Test
    void rewrite_yieldsSkipWhenNameDoesNotResolve() {
        var file = file("""
            type Query {
                x: Int @service(service: {name: "Unknown", method: "list"})
            }
            """);
        var action = SdlActions.externalCodeReferenceNameToClassName(catalog());
        var match = action.detector().detect(file).toList().get(0);

        var result = action.rewrite().rewrite(file, match);

        assertThat(result).isInstanceOf(RewriteResult.Skip.class);
        assertThat(((RewriteResult.Skip) result).reason()).isEqualTo("Unknown");
    }

    @Test
    void rewrite_yieldsEditForNestedConditionPath() {
        var file = file("""
            type Query {
                x: Int @reference(path: [{condition: {name: "Conditions"}}])
            }
            """);
        var action = SdlActions.externalCodeReferenceNameToClassName(catalog());
        var match = action.detector().detect(file).toList().get(0);

        var result = action.rewrite().rewrite(file, match);

        assertThat(result).isInstanceOf(RewriteResult.Edit.class);
        assertThat(((RewriteResult.Edit) result).edit().getNewText())
            .isEqualTo("className: \"com.example.Conditions\"");
    }

    @Test
    void displayName_isStable() {
        var action = SdlActions.externalCodeReferenceNameToClassName(catalog());

        assertThat(action.displayName()).isEqualTo("Migrate `name:` to `className:`");
    }

    private static CompletionData catalog() {
        return new CompletionData(List.of(), List.of(), List.of(), NAMED_REFS);
    }

    private static WorkspaceFile file(String source) {
        return new WorkspaceFile(1, source);
    }
}
