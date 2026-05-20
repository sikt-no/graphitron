package no.sikt.graphitron.lsp.server;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import no.sikt.graphitron.lsp.state.InlayHintConfig;
import no.sikt.graphitron.lsp.state.Workspace;
import org.eclipse.lsp4j.DidChangeConfigurationParams;
import org.eclipse.lsp4j.DidChangeWatchedFilesParams;
import org.eclipse.lsp4j.services.WorkspaceService;

/**
 * Workspace-level notifications. R160 wires
 * {@link #didChangeConfiguration(DidChangeConfigurationParams)} to refresh the
 * client's {@link InlayHintConfig} toggles on the workspace so the inlay-hint
 * provider sees the new state on the next request.
 *
 * <p>The settings shape mirrors the spec's config-key namespacing:
 * {@code graphitron.inlayHints.inferredDirectives},
 * {@code graphitron.inlayHints.classification}, and
 * {@code graphitron.hover.classification}. A client that does not send these keys
 * leaves the defaults in effect (all toggles off).
 */
public class GraphitronWorkspaceService implements WorkspaceService {

    private final Workspace workspace;

    public GraphitronWorkspaceService() {
        this(null);
    }

    public GraphitronWorkspaceService(Workspace workspace) {
        this.workspace = workspace;
    }

    @Override
    public void didChangeConfiguration(DidChangeConfigurationParams params) {
        if (workspace == null || params == null) return;
        InlayHintConfig parsed = parseInlayHintConfig(params.getSettings());
        if (parsed != null) {
            workspace.setInlayHintConfig(parsed);
        }
    }

    @Override
    public void didChangeWatchedFiles(DidChangeWatchedFilesParams params) {}

    /**
     * Pulls the three R160 toggles off a {@code workspace/didChangeConfiguration}
     * settings payload. lsp4j wires the raw JSON through Gson so the value comes in
     * as a {@link JsonElement}; we walk down through {@code graphitron.inlayHints.*}
     * and {@code graphitron.hover.*} keys defensively (any layer missing reverts to
     * the default {@code false}).
     */
    static InlayHintConfig parseInlayHintConfig(Object settings) {
        if (!(settings instanceof JsonElement element) || !element.isJsonObject()) {
            return null;
        }
        JsonObject root = element.getAsJsonObject();
        JsonObject graphitron = optObject(root, "graphitron");
        if (graphitron == null) return InlayHintConfig.defaults();
        JsonObject inlay = optObject(graphitron, "inlayHints");
        JsonObject hover = optObject(graphitron, "hover");
        boolean inferred = inlay != null && optBoolean(inlay, "inferredDirectives", false);
        boolean classification = inlay != null && optBoolean(inlay, "classification", false);
        boolean hoverClassification = hover != null && optBoolean(hover, "classification", false);
        return new InlayHintConfig(inferred, classification, hoverClassification);
    }

    private static JsonObject optObject(JsonObject parent, String key) {
        if (parent == null) return null;
        JsonElement e = parent.get(key);
        return e != null && e.isJsonObject() ? e.getAsJsonObject() : null;
    }

    private static boolean optBoolean(JsonObject parent, String key, boolean fallback) {
        JsonElement e = parent.get(key);
        if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isBoolean()) return fallback;
        return e.getAsBoolean();
    }
}
