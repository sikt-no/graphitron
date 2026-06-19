package no.sikt.graphitron.lsp.parsing;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Pure-text decomposition of an {@code argMapping} string's content (the bytes
 * between the quotes), shared by {@code ArgMappingCompletions} and the
 * {@code argMapping} diagnostics. The grammar is a comma-separated list of
 * {@code javaParam: graphqlArg} entries; the right side may be an R84 dot-path
 * ({@code input.nested.leaf}). Whitespace around {@code :} and {@code ,} is
 * permitted, and an all-blank content is identity (no entries).
 *
 * <p>This class does no catalog or schema lookup; it only slices the string
 * into entries and segments with content-relative offsets, so the cursor
 * decomposition the spec calls out (which entry, which side, what to replace)
 * and the structural diagnostics (empty entry, dangling {@code :}, extra
 * {@code ,}, duplicate Java parameter) are testable against malformed inputs
 * without a workspace.
 */
public final class ArgMapping {

    private ArgMapping() {}

    /** Which side of the {@code :} a cursor or segment sits on. */
    public enum Side { LEFT, RIGHT }

    /**
     * A trimmed token plus its content-relative offsets. For an empty token
     * (nothing typed on that side yet) {@code start == end} at the natural
     * insertion point, so a completion replace-range collapses to the cursor.
     */
    public record Segment(String text, int start, int end) {
        public boolean isEmpty() {
            return text.isEmpty();
        }
    }

    /**
     * One {@code javaParam: graphqlArg} entry. {@code hasColon} distinguishes a
     * dangling / half-typed entry from a complete one; {@code graphql} is still
     * present (possibly empty) when {@code hasColon} is true.
     *
     * @param java     the Java-parameter segment (left of {@code :})
     * @param graphql  the GraphQL-argument segment (right of {@code :}); an
     *                 empty segment at the entry end when {@code hasColon} is false
     * @param hasColon whether the entry carried a {@code :}
     * @param rawStart content offset of the entry start (after the preceding comma)
     * @param rawEnd   content offset of the entry end (before the next comma)
     */
    public record Entry(Segment java, Segment graphql, boolean hasColon, int rawStart, int rawEnd) {
        /** True when the whole entry (ignoring a lone {@code :}) is blank. */
        public boolean isBlank() {
            return java.isEmpty() && graphql.isEmpty();
        }
    }

    /** Cursor decomposition: the entry index, the side, and the token to replace. */
    public record Cursor(int entryIndex, Side side, Segment token) {}

    /**
     * Splits {@code content} into entries. Returns an empty list when the
     * content is blank (identity for every parameter). Each comma yields a new
     * entry, so {@code "a: b,"} produces a trailing empty entry and {@code ",,"}
     * produces empties the diagnostics flag as extra commas.
     */
    public static List<Entry> parse(String content) {
        if (content == null || content.isBlank()) return List.of();
        var entries = new ArrayList<Entry>();
        int start = 0;
        for (int i = 0; i <= content.length(); i++) {
            if (i == content.length() || content.charAt(i) == ',') {
                entries.add(entryOf(content, start, i));
                start = i + 1;
            }
        }
        return List.copyOf(entries);
    }

    private static Entry entryOf(String content, int rawStart, int rawEnd) {
        int colon = -1;
        for (int i = rawStart; i < rawEnd; i++) {
            if (content.charAt(i) == ':') {
                colon = i;
                break;
            }
        }
        if (colon < 0) {
            return new Entry(segment(content, rawStart, rawEnd),
                emptyAt(content, rawEnd), false, rawStart, rawEnd);
        }
        return new Entry(segment(content, rawStart, colon),
            segment(content, colon + 1, rawEnd), true, rawStart, rawEnd);
    }

    /**
     * Locates the cursor (a content-relative offset) within the entry list:
     * which entry it sits in, which side of the {@code :}, and the token whose
     * span a completion should replace. Empty for a blank content (no entries).
     */
    public static Optional<Cursor> locate(String content, int offset) {
        if (content == null) return Optional.empty();
        int clamped = Math.max(0, Math.min(offset, content.length()));
        var entries = parseSpans(content);
        if (entries.isEmpty()) {
            // Blank content: a single implicit left-side entry at the cursor.
            return Optional.of(new Cursor(0, Side.LEFT, emptyAt(content, clamped)));
        }
        for (int idx = 0; idx < entries.size(); idx++) {
            var entry = entries.get(idx);
            // The last entry owns the trailing edge; earlier entries own up to
            // (but not including) their comma boundary.
            boolean last = idx == entries.size() - 1;
            boolean inSpan = clamped >= entry.rawStart()
                && (clamped < entry.rawEnd() || (last && clamped <= entry.rawEnd()));
            if (!inSpan) continue;
            if (entry.hasColon()) {
                int colon = entry.rawStart() + indexOfColon(content, entry.rawStart(), entry.rawEnd());
                if (clamped > colon) {
                    return Optional.of(new Cursor(idx, Side.RIGHT, tokenAtCursor(content, entry.graphql(), clamped)));
                }
            }
            return Optional.of(new Cursor(idx, Side.LEFT, tokenAtCursor(content, entry.java(), clamped)));
        }
        return Optional.empty();
    }

    private static List<Entry> parseSpans(String content) {
        return parse(content);
    }

    private static int indexOfColon(String content, int start, int end) {
        for (int i = start; i < end; i++) {
            if (content.charAt(i) == ':') return i - start;
        }
        return -1;
    }

    /**
     * The token a completion should replace on the cursor's side: the existing
     * segment when the cursor is within or adjacent to it, else a zero-width
     * span at the cursor (so a completion inserts at the caret).
     */
    private static Segment tokenAtCursor(String content, Segment segment, int cursor) {
        if (!segment.isEmpty()) return segment;
        return emptyAt(content, cursor);
    }

    private static Segment segment(String content, int rawStart, int rawEnd) {
        int s = rawStart;
        while (s < rawEnd && Character.isWhitespace(content.charAt(s))) s++;
        int e = rawEnd;
        while (e > s && Character.isWhitespace(content.charAt(e - 1))) e--;
        return new Segment(content.substring(s, e), s, e);
    }

    private static Segment emptyAt(String content, int at) {
        int clamped = Math.max(0, Math.min(at, content == null ? 0 : content.length()));
        return new Segment("", clamped, clamped);
    }
}
