package no.sikt.graphitron.lsp.code_action;

import io.github.treesitter.jtreesitter.Node;
import no.sikt.graphitron.lsp.parsing.SchemaCoordinate;
import no.sikt.graphitron.lsp.state.FileSnapshot;
import org.eclipse.lsp4j.TextEdit;

import java.util.Set;
import java.util.stream.Stream;

/**
 * The reusable shape for an LSP-side SDL refactor: detection of
 * matched literals plus per-match rewriting that produces a
 * {@link RewriteResult}. Each SDL migration (directive rename,
 * deprecation migration) is one instance.
 *
 * <p>A single {@code SdlAction} drives all three activation points the
 * code-action provider exposes (per-site, file-scoped bulk,
 * workspace-scoped bulk). Per-site invocations rewrite the match
 * containing the cursor; bulk invocations rewrite every detected match
 * and partition the results by {@link RewriteResult} arm to drive the
 * result message.
 *
 * @param displayName the code-action title shown in the editor (e.g.
 *                    "Migrate `name:` to `className:`").
 * @param targets     the deprecation sites this action migrates,
 *                    keyed by {@link SchemaCoordinate}.
 *                    {@code SdlActionDriftTest} asserts each target
 *                    points at a real deprecation marker in
 *                    {@code directives.graphqls}, so a renamed or
 *                    removed marker cannot leave a stale action; a
 *                    deprecation is not required to have an action.
 * @param detector    per-file match detection; see {@link Detector}
 *                    for the stream contract.
 * @param rewrite     per-match rewrite; {@link RewriteResult.Skip}
 *                    reasons feed the bulk action's result message.
 */
public record SdlAction(
    String displayName,
    Set<SchemaCoordinate> targets,
    Detector detector,
    Rewrite rewrite
) {

    /**
     * Detects matched literals in a workspace file: one {@link Node}
     * per match, in source order. Finite, eager-friendly stream scoped
     * to a single file.
     */
    @FunctionalInterface
    public interface Detector {
        Stream<Node> detect(FileSnapshot file);
    }

    /**
     * Produces a per-match rewrite. The {@code match} node is one
     * {@link Detector} emitted on the same {@code file}; pairing
     * across files is undefined and would yield wrong byte offsets.
     */
    @FunctionalInterface
    public interface Rewrite {
        RewriteResult rewrite(FileSnapshot file, Node match);
    }

    /**
     * Outcome of a single per-match rewrite. Sealed so consumers can
     * partition by arm without {@code null}-driven branching.
     */
    public sealed interface RewriteResult permits RewriteResult.Edit, RewriteResult.Skip {

        /** A TextEdit ready to apply. */
        record Edit(TextEdit edit) implements RewriteResult {}

        /** Rewrite cannot proceed; the reason is reported back to the user. */
        record Skip(String reason) implements RewriteResult {}
    }
}
