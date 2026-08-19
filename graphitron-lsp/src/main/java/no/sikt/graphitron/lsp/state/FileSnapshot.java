package no.sikt.graphitron.lsp.state;

import io.github.treesitter.jtreesitter.Tree;

/**
 * An immutable, off-thread-safe view of one open {@link WorkspaceFile} at a
 * single generation: the {@code (tree, source, version)} triple captured
 * atomically under the {@link Workspace} lock. Handed to request handlers and
 * feature {@code compute()} methods so they walk a syntax tree and read source
 * bytes on a pool thread without racing the dispatch thread's
 * {@code didChange}-driven edit / tree-swap / eager {@code close()}.
 *
 * <p>Deliberately shares no read interface with {@link WorkspaceFile}: a method
 * that accepts a {@code FileSnapshot} cannot be handed the live, mutable file,
 * so "safe to read off the dispatch thread" is compiler-enforced, not
 * convention.
 *
 * <p>The {@link #tree()} is a {@code ts_tree_copy} clone with a native lifetime
 * independent of the live file's: it stays valid after the live file closes its
 * original tree on the next edit, and is released here. jtreesitter registers no
 * {@link java.lang.ref.Cleaner}, so an unclosed clone leaks native memory until
 * process exit; {@link #close()} must run. Production callers receive snapshots
 * only inside a {@link Workspace} lambda scope ({@code withView} /
 * {@code withAllViews}) that closes them in a {@code finally}.
 *
 * <p>The {@code byte[] source} is shared, not copied: {@link WorkspaceFile}
 * never mutates a published array in place (every edit reassigns a fresh one),
 * so the reference captured here stays paired with the {@code tree} it was
 * parsed from.
 * */
public record FileSnapshot(Tree tree, byte[] source, int version) implements AutoCloseable {

    /** Releases the cloned tree's native memory. Idempotent-unsafe; call exactly once. */
    @Override
    public void close() {
        tree.close();
    }
}
