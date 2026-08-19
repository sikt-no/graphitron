package no.sikt.graphitron.lsp.state;

import no.sikt.graphitron.lsp.parsing.GraphqlLanguage;
import no.sikt.graphitron.lsp.trace.LspTrace;
import io.github.treesitter.jtreesitter.InputEdit;
import io.github.treesitter.jtreesitter.Parser;
import io.github.treesitter.jtreesitter.Point;
import io.github.treesitter.jtreesitter.Tree;

import java.nio.charset.StandardCharsets;

/**
 * A schema file open in the workspace: source bytes + tree-sitter tree, both
 * kept in lockstep through {@link #applyEdit}. Mirrors the Rust LSP's
 * {@code state/file.rs} {@code File} struct.
 *
 * <p>Performance contract:
 *
 * <ul>
 *   <li>{@link #applyEdit} performs an incremental tree-sitter re-parse
 *       (passing the old tree to {@link Parser#parse(String, Tree)}); only
 *       the changed region is re-tokenised and re-parsed. The previous
 *       {@link Tree} is closed before the new one replaces it so its
 *       backing FFM arena releases promptly rather than waiting for GC.</li>
 *   <li>The parser instance is reused across edits to avoid the
 *       construction cost (loading the language + allocating native
 *       state).</li>
 * </ul>
 *
 * <p>Source storage is plain {@code byte[]}; tree-sitter's API is byte-offset
 * based, so this keeps slicing zero-copy. graphitron schema files are
 * typically small enough that array splicing is faster than rope traversal
 * until file size exceeds a few hundred KB.
 */
public final class WorkspaceFile {

    private final Parser parser;
    private byte[] source;
    private Tree tree;
    private int version;

    public WorkspaceFile(int version, String content) {
        try (var span = LspTrace.span("file.parseInitial")) {
            span.detail("chars", content.length());
            this.parser = new Parser(GraphqlLanguage.get());
            this.source = content.getBytes(StandardCharsets.UTF_8);
            this.tree = parser.parse(content).orElseThrow(WorkspaceFile::parseHalted);
        }
        this.version = version;
    }

    public Tree tree() {
        return tree;
    }

    public byte[] source() {
        return source;
    }

    public int version() {
        return version;
    }

    /**
     * Capture an immutable {@link FileSnapshot} of this file's current
     * generation: clone the native tree (a cheap refcounted {@code ts_tree_copy}
     * whose lifetime is independent of this file's, so it survives the eager
     * {@link Tree#close()} the next edit performs) and pair it with the current
     * {@code source} and {@code version}. Because {@code source} is never mutated
     * in place, capturing the three fields together yields an internally
     * consistent triple.
     *
     * <p>Package-private: only {@link Workspace} calls this, while holding its
     * lock, so the field reads get the happens-before edge from the same lock the
     * mutators run under. The returned clone is a native resource the caller must
     * {@link FileSnapshot#close() close}; {@link Workspace}'s lambda-scoped view
     * accessors own that lifecycle.
     */
    FileSnapshot snapshot() {
        return new FileSnapshot(tree.clone(), source, version);
    }

    /**
     * Apply a range edit. Incremental: the old tree informs the new parse
     * so unchanged subtrees are reused.
     *
     * @param newVersion editor version stamp
     * @param startByte  byte offset where the edit begins
     * @param oldEndByte byte offset where the replaced text ended
     * @param startPoint row/column where the edit begins
     * @param oldEndPoint row/column where the replaced text ended
     * @param newText    the inserted text (may be empty for pure deletion)
     */
    public void applyEdit(
        int newVersion,
        int startByte,
        int oldEndByte,
        Point startPoint,
        Point oldEndPoint,
        String newText
    ) {
        if (newVersion < version) {
            return;
        }
        byte[] newBytes = newText.getBytes(StandardCharsets.UTF_8);
        int newEndByte = startByte + newBytes.length;

        byte[] updated = new byte[source.length - (oldEndByte - startByte) + newBytes.length];
        System.arraycopy(source, 0, updated, 0, startByte);
        System.arraycopy(newBytes, 0, updated, startByte, newBytes.length);
        System.arraycopy(source, oldEndByte, updated, newEndByte, source.length - oldEndByte);

        Point newEndPoint = computeNewEndPoint(startPoint, newText);

        tree.edit(new InputEdit(startByte, oldEndByte, newEndByte, startPoint, oldEndPoint, newEndPoint));
        this.source = updated;
        Tree previous = tree;
        // The whole per-edit cost, and the reason it is timed: the reparse is incremental in
        // tree-sitter, but it pays a whole-buffer decode to hand the parser a String, so it
        // scales with file size rather than with the edit.
        try (var span = LspTrace.span("file.reparse")) {
            span.detail("bytes", updated.length);
            this.tree = parser.parse(new String(updated, StandardCharsets.UTF_8), previous)
                .orElseThrow(WorkspaceFile::parseHalted);
        }
        previous.close();
        this.version = newVersion;
    }

    /**
     * Replace the file's contents wholesale. Used for the LSP "full sync"
     * change variant (no range, just new text) and for tests.
     */
    public void replaceContent(int newVersion, String content) {
        if (newVersion < version) {
            return;
        }
        this.source = content.getBytes(StandardCharsets.UTF_8);
        Tree previous = tree;
        try (var span = LspTrace.span("file.reparseFull")) {
            span.detail("chars", content.length());
            this.tree = parser.parse(content).orElseThrow(WorkspaceFile::parseHalted);
        }
        previous.close();
        this.version = newVersion;
    }

    private static IllegalStateException parseHalted() {
        // jtreesitter returns Optional.empty() only when parsing was halted (cancellation,
        // resource limit). The dev mojo does not configure either, so an empty Optional
        // indicates a tree-sitter bug or out-of-memory condition; surface it loudly.
        return new IllegalStateException("tree-sitter parse halted unexpectedly");
    }

    private static Point computeNewEndPoint(Point startPoint, String inserted) {
        int row = startPoint.row();
        int column = startPoint.column();
        int lineStartIdx = 0;
        for (int i = 0; i < inserted.length(); i++) {
            if (inserted.charAt(i) == '\n') {
                row++;
                column = 0;
                lineStartIdx = i + 1;
            }
        }
        column += inserted.length() - lineStartIdx;
        return new Point(row, column);
    }
}
