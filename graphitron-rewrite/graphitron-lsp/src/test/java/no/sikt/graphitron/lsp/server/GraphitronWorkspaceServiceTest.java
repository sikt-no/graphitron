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
}
