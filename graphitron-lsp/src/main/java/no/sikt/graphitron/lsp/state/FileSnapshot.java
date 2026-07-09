package no.sikt.graphitron.lsp.state;

import io.github.treesitter.jtreesitter.Tree;

/**
 * An immutable, off-thread-safe view of one open {@link WorkspaceFile} at a
 * single generation: the {@code (tree, source, version)} triple captured
 * atomically under the {@link Workspace} lock. Handed to request handlers and
 * feature {@code compute()} methods so they walk a syntax tree and read source
 * bytes on a pool thread without racing the dispatch thread's
 * {@code didChange}-driven edit / tree-swap / eager {@code close()} (R456).
 *
 * <p>Distinct from {@link WorkspaceFile} by design, with no shared read
 * interface: the type is the guarantee. A method that accepts a
 * {@code FileSnapshot} cannot be handed the live, mutable {@code WorkspaceFile}
 * by mistake, so "safe to read off the dispatch thread" is enforced by the
 * compiler rather than by convention.
 *
 * <p>The {@link #tree()} is a {@code ts_tree_copy} clone with a native lifetime
 * independent of the live file's: it stays valid after the live file closes its
 * original tree on the next edit, and is released here. jtreesitter registers no
 * {@link java.lang.ref.Cleaner}, so an unclosed clone leaks native memory until
 * process exit; {@link #close()} must run. Production callers never hold a
 * {@code FileSnapshot} directly, they receive it inside a {@link Workspace}
 * lambda scope ({@code withView} / {@code withAllViews}) that closes it in a
 * {@code finally}, making leak-by-omission structurally impossible.
 *
 * <p>The {@code byte[] source} is shared, not copied: {@link WorkspaceFile}
 * never mutates a published array in place (every edit reassigns a fresh one),
 * so the reference captured here stays paired with the {@code tree} it was
 * parsed from for the snapshot's lifetime.
 *
 * <p>Deliberately does not carry {@code declaredTypes()} /
 * {@code dependsOnDeclarations()}: those feed {@link Workspace}'s own mutators
 * under the lock ({@code enqueueTouched}), not off-thread readers. Add them only
 * when a concrete off-thread reader needs them.
 */
public record FileSnapshot(Tree tree, byte[] source, int version) implements AutoCloseable {

    /** Releases the cloned tree's native memory. Idempotent-unsafe; call exactly once. */
    @Override
    public void close() {
        tree.close();
    }
}
