package no.sikt.graphitron.lsp.code_action;

import io.github.treesitter.jtreesitter.Node;
import no.sikt.graphitron.lsp.parsing.SchemaCoordinate;
import no.sikt.graphitron.lsp.state.WorkspaceFile;
import org.eclipse.lsp4j.TextEdit;

import java.util.Set;
import java.util.stream.Stream;

/**
 * The reusable shape for an LSP-side SDL refactor: detection of
 * matched literals plus per-match rewriting that produces a
 * {@link RewriteResult}. R93's first instantiation is the
 * {@code ExternalCodeReference.name → className} migration; future
 * deprecation migrations or directive renames instantiate it
 * differently.
 *
 * <p>A single {@code SdlAction} drives all three activation points the
 * code-action provider exposes (per-site, file-scoped bulk,
 * workspace-scoped bulk). Per-site invocations call {@link #detector()}
 * once and {@link #rewrite()} for whichever match contains the cursor;
 * bulk invocations call {@link #rewrite()} for every match the
 * detector emits and partition the results by {@link RewriteResult}
 * arm to drive the result message.
 *
 * @param displayName the code-action title shown in the editor (e.g.
 *                    "Migrate `name:` to `className:`").
 * @param targets     the deprecation sites this action migrates,
 *                    keyed by {@link SchemaCoordinate}. Drift-test
 *                    invariants assert every target points at a real
 *                    deprecation marker in {@code directives.graphqls}
 *                    and (going the other direction) every marker is
 *                    covered by an action or the
 *                    {@code MANUAL_MIGRATION_DEPRECATIONS} allow-list.
 * @param detector    finds matched literals in a file, in source
 *                    order. Eager / finite stream; the consumer
 *                    materialises before iterating multiple times.
 * @param rewrite     per-match rewrite. Returns
 *                    {@link RewriteResult.Edit} with the TextEdit to
 *                    apply, or {@link RewriteResult.Skip} when the
 *                    rewrite cannot proceed (e.g. unresolvable
 *                    {@code name:} value); skip reasons feed the bulk
 *                    action's result message.
 */
public record SdlAction(
    String displayName,
    Set<SchemaCoordinate> targets,
    Detector detector,
    Rewrite rewrite
) {

    /**
     * Detects matched literals in a workspace file. Implementations
     * walk the parsed tree and emit one {@link Node} per match in
     * source order. The contract is: finite, eager-friendly stream
     * scoped to a single file.
     */
    @FunctionalInterface
    public interface Detector {
        Stream<Node> detect(WorkspaceFile file);
    }

    /**
     * Produces a per-match rewrite. The {@code match} node is one
     * {@link Detector} emitted on the same {@code file}; pairing
     * across files is undefined and would yield wrong byte offsets.
     */
    @FunctionalInterface
    public interface Rewrite {
        RewriteResult rewrite(WorkspaceFile file, Node match);
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
