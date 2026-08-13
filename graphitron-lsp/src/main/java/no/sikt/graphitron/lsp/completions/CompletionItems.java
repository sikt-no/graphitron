package no.sikt.graphitron.lsp.completions;

import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionItemKind;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.TextEdit;
import org.eclipse.lsp4j.jsonrpc.messages.Either;

/**
 * Factory for the {@link CompletionItem} shape every provider builds: a label
 * that is also the inserted text, a kind, and an explicit {@link TextEdit}
 * replacing {@code range} (clients otherwise apply per-client word-boundary
 * heuristics to dotted candidates). Previously the four lines were open-coded
 * in eight providers; they live here now. Signature rendering went the other way: it was shared by
 * the two method providers that have since collapsed into one, so it sits in that provider beside
 * the rows it renders rather than in a factory nothing else calls it from.
 */
public final class CompletionItems {

    private CompletionItems() {}

    /** Item whose inserted text equals {@code label}, replacing {@code range}. */
    public static CompletionItem replacing(String label, CompletionItemKind kind, Range range) {
        var item = new CompletionItem(label);
        item.setKind(kind);
        item.setTextEdit(Either.forLeft(new TextEdit(range, label)));
        return item;
    }

    /** {@link #replacing(String, CompletionItemKind, Range)} plus a detail line. */
    public static CompletionItem replacing(String label, CompletionItemKind kind, Range range, String detail) {
        var item = replacing(label, kind, range);
        item.setDetail(detail);
        return item;
    }
}
