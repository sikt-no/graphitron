package no.sikt.graphitron.lsp.completions;

import no.sikt.graphitron.rewrite.catalog.CompletionData;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionItemKind;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.TextEdit;
import org.eclipse.lsp4j.jsonrpc.messages.Either;

/**
 * Factory for the {@link CompletionItem} shape every provider builds: a label
 * that is also the inserted text, a kind, and an explicit {@link TextEdit}
 * replacing {@code range} (R153 — clients otherwise apply per-client
 * word-boundary heuristics to dotted candidates). Before R347 Slice 3 the four
 * lines were open-coded in eight providers and the {@code formatSignature}
 * method was byte-identical in two; both live here now.
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

    /**
     * Erased Java signature ({@code ReturnType name(Type arg0, ...)}) used as
     * the detail line for method completions. Parameters without a name (the
     * consumer compiled without {@code -parameters}) fall back to
     * {@code arg<i>}.
     */
    public static String formatSignature(CompletionData.Method method) {
        var sb = new StringBuilder();
        sb.append(method.returnType()).append(' ').append(method.name()).append('(');
        for (int i = 0; i < method.parameters().size(); i++) {
            if (i > 0) sb.append(", ");
            var p = method.parameters().get(i);
            sb.append(p.type()).append(' ')
                .append(p.name() != null ? p.name() : "arg" + i);
        }
        sb.append(')');
        return sb.toString();
    }
}
