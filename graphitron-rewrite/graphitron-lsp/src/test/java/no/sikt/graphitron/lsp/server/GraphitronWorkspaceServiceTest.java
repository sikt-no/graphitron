package no.sikt.graphitron.lsp.server;

import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import no.sikt.graphitron.lsp.state.InlayHintConfig;
import no.sikt.graphitron.lsp.state.Workspace;
import org.eclipse.lsp4j.DidChangeConfigurationParams;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R160 — verifies the {@code workspace/didChangeConfiguration} notification routes the
 * three inlay-hint / hover toggles through to {@link Workspace#setInlayHintConfig}, and
 * that missing layers fall back to {@code false} per the no-behaviour-change-by-default
 * contract.
 */
class GraphitronWorkspaceServiceTest {

    @Test
    void parsesAllThreeTogglesFromGraphitronNamespace() {
        var inlay = new JsonObject();
        inlay.add("inferredDirectives", new JsonPrimitive(true));
        inlay.add("classification", new JsonPrimitive(true));
        var hover = new JsonObject();
        hover.add("classification", new JsonPrimitive(true));
        var graphitron = new JsonObject();
        graphitron.add("inlayHints", inlay);
        graphitron.add("hover", hover);
        var root = new JsonObject();
        root.add("graphitron", graphitron);

        var config = GraphitronWorkspaceService.parseInlayHintConfig(root);

        assertThat(config).isNotNull();
        assertThat(config.inferredDirectives()).isTrue();
        assertThat(config.classification()).isTrue();
        assertThat(config.hoverClassification()).isTrue();
    }

    @Test
    void missingTogglesDefaultToFalse() {
        var graphitron = new JsonObject();
        graphitron.add("inlayHints", new JsonObject()); // empty
        var root = new JsonObject();
        root.add("graphitron", graphitron);

        var config = GraphitronWorkspaceService.parseInlayHintConfig(root);

        assertThat(config).isNotNull();
        assertThat(config.inferredDirectives()).isFalse();
        assertThat(config.classification()).isFalse();
        assertThat(config.hoverClassification()).isFalse();
    }

    @Test
    void missingGraphitronNamespaceProducesDefaults() {
        var config = GraphitronWorkspaceService.parseInlayHintConfig(new JsonObject());
        assertThat(config).isEqualTo(InlayHintConfig.defaults());
    }

    @Test
    void nonJsonSettingsReturnsNull() {
        assertThat(GraphitronWorkspaceService.parseInlayHintConfig("not a json element")).isNull();
        assertThat(GraphitronWorkspaceService.parseInlayHintConfig(null)).isNull();
    }

    @Test
    void didChangeConfigurationSwapsWorkspaceConfig() {
        var workspace = new Workspace();
        assertThat(workspace.inlayHintConfig().anyEnabled()).isFalse();

        var inlay = new JsonObject();
        inlay.add("inferredDirectives", new JsonPrimitive(true));
        var graphitron = new JsonObject();
        graphitron.add("inlayHints", inlay);
        var root = new JsonObject();
        root.add("graphitron", graphitron);

        var service = new GraphitronWorkspaceService(workspace);
        service.didChangeConfiguration(new DidChangeConfigurationParams(root));

        assertThat(workspace.inlayHintConfig().inferredDirectives()).isTrue();
        assertThat(workspace.inlayHintConfig().classification()).isFalse();
    }

    @Test
    void applyPulledInlayHintConfigSwapsWorkspaceConfig() {
        // The pull path receives one JsonElement per requested ConfigurationItem; we ask for
        // "graphitron" so the response is the graphitron namespace's inner object (not
        // wrapped). The service re-wraps and routes through the same parser the push path
        // uses, so both directions land on the same Workspace.setInlayHintConfig.
        var workspace = new Workspace();
        var inlay = new JsonObject();
        inlay.add("classification", new JsonPrimitive(true));
        var hover = new JsonObject();
        hover.add("classification", new JsonPrimitive(true));
        var graphitronSection = new JsonObject();
        graphitronSection.add("inlayHints", inlay);
        graphitronSection.add("hover", hover);

        var service = new GraphitronWorkspaceService(workspace);
        service.applyPulledInlayHintConfig(java.util.List.of(graphitronSection));

        assertThat(workspace.inlayHintConfig().inferredDirectives()).isFalse();
        assertThat(workspace.inlayHintConfig().classification()).isTrue();
        assertThat(workspace.inlayHintConfig().hoverClassification()).isTrue();
    }

    @Test
    void applyPulledInlayHintConfigToleratesNullAndNonJsonResults() {
        var workspace = new Workspace();
        var service = new GraphitronWorkspaceService(workspace);
        // Client doesn't implement workspace/configuration (returns nulls) or returns the
        // wrong shape (a string); both fall through to default-off rather than throwing.
        service.applyPulledInlayHintConfig(java.util.Collections.singletonList(null));
        assertThat(workspace.inlayHintConfig().anyEnabled()).isFalse();
        service.applyPulledInlayHintConfig(java.util.List.of("not a json object"));
        assertThat(workspace.inlayHintConfig().anyEnabled()).isFalse();
        service.applyPulledInlayHintConfig(java.util.List.of());
        assertThat(workspace.inlayHintConfig().anyEnabled()).isFalse();
    }
}
