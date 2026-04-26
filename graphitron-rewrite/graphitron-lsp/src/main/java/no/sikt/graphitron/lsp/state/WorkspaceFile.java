package no.sikt.graphitron.lsp.state;

import no.sikt.graphitron.lsp.parsing.GraphqlLanguage;
import no.sikt.graphitron.lsp.parsing.TypeNames;
import org.treesitter.TSInputEdit;
import org.treesitter.TSParser;
import org.treesitter.TSPoint;
import org.treesitter.TSTree;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * A schema file open in the workspace: source bytes + tree-sitter tree, both
 * kept in lockstep through {@link #applyEdit}. Mirrors the Rust LSP's
 * {@code state/file.rs} {@code File} struct, minus the type-extraction layer
 * (which lands when the workspace plumbing does).
 *
 * <p>Performance contract:
 *
 * <ul>
 *   <li>{@link #applyEdit} performs an incremental tree-sitter re-parse
 *       (passing the old tree to {@code parseString}); only the changed
 *       region is re-tokenised and re-parsed.</li>
 *   <li>The parser instance is reused across edits to avoid the
 *       construction cost (loading the language + allocating native
 *       state).</li>
 * </ul>
 *
 * <p>Source storage is plain {@code byte[]} for now; tree-sitter's API is
 * byte-offset based, so this keeps slicing zero-copy. A rope (ropey-like)
 * is a future optimisation if profiling shows large-file edits dominate;
 * graphitron schema files are typically small enough that array splicing
 * is faster than rope traversal until file size exceeds a few hundred KB.
 */
public final class WorkspaceFile {

    private final TSParser parser;
    private byte[] source;
    private TSTree tree;
    private int version;
    private Set<String> declaredTypes;
    private Set<String> dependsOnDeclarations;

    public WorkspaceFile(int version, String content) {
        this.parser = new TSParser();
        this.parser.setLanguage(GraphqlLanguage.get());
        this.source = content.getBytes(StandardCharsets.UTF_8);
        this.tree = parser.parseString(null, content);
        this.version = version;
        refreshTypeIndex();
    }

    public TSTree tree() {
        return tree;
    }

    public byte[] source() {
        return source;
    }

    public int version() {
        return version;
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
        TSPoint startPoint,
        TSPoint oldEndPoint,
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

        TSPoint newEndPoint = computeNewEndPoint(startPoint, newText);

        tree.edit(new TSInputEdit(startByte, oldEndByte, newEndByte, startPoint, oldEndPoint, newEndPoint));
        this.source = updated;
        this.tree = parser.parseString(tree, new String(updated, StandardCharsets.UTF_8));
        this.version = newVersion;
        refreshTypeIndex();
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
        this.tree = parser.parseString(null, content);
        this.version = newVersion;
        refreshTypeIndex();
    }

    /** Type names declared in this file (objects, interfaces, unions, enums, inputs, scalars). */
    public Set<String> declaredTypes() {
        return declaredTypes;
    }

    /**
     * Type names this file references but does not declare, minus built-in
     * scalars. When another file's declarations change to add or remove one
     * of these names, this file's diagnostics need to be recomputed.
     */
    public Set<String> dependsOnDeclarations() {
        return dependsOnDeclarations;
    }

    private void refreshTypeIndex() {
        var extracted = TypeNames.extract(tree.getRootNode(), source);
        this.declaredTypes = Set.copyOf(extracted.declared());
        var deps = new LinkedHashSet<>(extracted.referenced());
        deps.removeAll(extracted.declared());
        deps.removeAll(TypeNames.BUILTIN_SCALARS);
        this.dependsOnDeclarations = Set.copyOf(deps);
    }

    private static TSPoint computeNewEndPoint(TSPoint startPoint, String inserted) {
        int row = startPoint.getRow();
        int column = startPoint.getColumn();
        int lineStartIdx = 0;
        for (int i = 0; i < inserted.length(); i++) {
            if (inserted.charAt(i) == '\n') {
                row++;
                column = 0;
                lineStartIdx = i + 1;
            }
        }
        column += inserted.length() - lineStartIdx;
        return new TSPoint(row, column);
    }
}
