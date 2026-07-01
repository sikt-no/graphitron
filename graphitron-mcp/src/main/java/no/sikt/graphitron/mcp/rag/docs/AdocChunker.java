package no.sikt.graphitron.mcp.rag.docs;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Heading-aware structural chunker for AsciiDoc source (R385). A pure function
 * {@code (adoc, sourcePath) -> List<DocChunk>}: it reads the raw {@code .adoc} heading syntax line by
 * line, with no AsciiDoctor render, which is what keeps the docs RAG index off the docs module's
 * JRuby render cost.
 *
 * <p><strong>Boundaries.</strong> Every heading ({@code =} / {@code ==} / {@code ===} / ...) starts
 * a new chunk whose body runs to the next heading of any level; the heading stack gives each chunk
 * its full {@code headingPath} breadcrumb. An {@code // rag:split} comment line forces an extra
 * boundary inside a section, producing a second chunk that inherits the enclosing heading path, for
 * the cases where the section structure alone chunks too coarsely.
 *
 * <p><strong>What is not a split.</strong> Only the exact comment {@code // rag:split} fires the
 * override. A near-miss ({@code // rag:splitt}, {@code // rag:}, {@code //rag:split} with no space)
 * is treated as an ordinary AsciiDoc line comment: it is dropped from the body and does <em>not</em>
 * create a boundary, so a typo cannot silently masquerade as a split. The chunker is a pure function
 * with no logger, so the choice is ignore-not-warn; the unit test pins that a near-miss yields no
 * extra chunk.
 *
 * <p><strong>Fenced blocks are opaque.</strong> Lines inside a listing ({@code ----}), literal
 * ({@code ....}), passthrough ({@code ++++}), or table ({@code |===}) block are body, never parsed
 * for headings or split markers, so a {@code ==}-prefixed line in a shell or GraphQL sample is not
 * mistaken for a section.
 */
public final class AdocChunker {

    private AdocChunker() {}

    /** A heading: a run of one-or-more leading {@code =} followed by whitespace and the title text. */
    private static final Pattern HEADING = Pattern.compile("^(=+)\\s+(\\S.*)$");

    /** A block anchor on its own line: {@code [[id]]} or {@code [#id]} (an explicit section anchor). */
    private static final Pattern BLOCK_ANCHOR = Pattern.compile("^\\[(?:\\[([^\\]]+)\\]|#([^\\]]+))\\]$");

    /** The exact split-override comment. Trailing whitespace is tolerated; nothing else fires it. */
    private static final String SPLIT_MARKER = "// rag:split";

    public static List<DocChunk> chunk(String adoc, String sourcePath) {
        var chunks = new ArrayList<DocChunk>();
        // The heading stack: index i holds the level-(i+1) heading currently in scope. A heading at
        // level L truncates the stack to L-1 entries and pushes itself, so the breadcrumb is the
        // stack as it stands when a chunk is flushed.
        var headingStack = new ArrayList<String>();
        String currentAnchor = "";
        String pendingAnchor = null; // an explicit [[id]] / [#id] seen on the line before a heading
        var body = new StringBuilder();
        boolean sawHeading = false;
        String fence = null; // the active fence delimiter line, or null outside any fenced block

        for (String line : adoc.split("\n", -1)) {
            String trimmed = line.strip();

            if (fence != null) {
                // Inside a fenced block: the only line that matters is the matching closing fence.
                if (trimmed.equals(fence)) {
                    fence = null;
                }
                body.append(line).append('\n');
                continue;
            }

            String opened = fenceDelimiter(trimmed);
            if (opened != null) {
                fence = opened;
                body.append(line).append('\n');
                continue;
            }

            var heading = HEADING.matcher(line);
            if (heading.matches()) {
                // A new section: flush the chunk that just ended, then re-root the heading stack.
                flush(chunks, headingStack, sourcePath, currentAnchor, body, sawHeading);
                sawHeading = true;

                int level = heading.group(1).length(); // 1 = page title, 2 = top section, ...
                String title = heading.group(2).strip();
                while (headingStack.size() >= level) {
                    headingStack.remove(headingStack.size() - 1);
                }
                while (headingStack.size() < level - 1) {
                    headingStack.add(""); // pad over a skipped level so depth stays meaningful
                }
                headingStack.add(title);

                currentAnchor = pendingAnchor != null ? pendingAnchor : slug(title);
                pendingAnchor = null;
                continue;
            }

            var anchor = BLOCK_ANCHOR.matcher(trimmed);
            if (anchor.matches()) {
                pendingAnchor = anchor.group(1) != null ? anchor.group(1) : anchor.group(2);
                continue;
            }

            if (trimmed.equals(SPLIT_MARKER)) {
                // An explicit in-section boundary: flush the body so far under the same heading path,
                // then keep accumulating into a fresh body with the same breadcrumb and anchor.
                flush(chunks, headingStack, sourcePath, currentAnchor, body, sawHeading);
                continue;
            }

            if (trimmed.startsWith("//")) {
                // An ordinary AsciiDoc line comment (the split near-misses land here): drop it from
                // the body and create no boundary.
                continue;
            }

            body.append(line).append('\n');
        }

        flush(chunks, headingStack, sourcePath, currentAnchor, body, sawHeading);
        return List.copyOf(chunks);
    }

    /**
     * Emits a chunk for the body accumulated under the current heading path, unless there is nothing
     * to emit (no heading yet and no prose). The body is reset for the next chunk. A heading with an
     * empty body still emits, so a section that exists only to carry subsections keeps a presence in
     * the index under its own breadcrumb.
     */
    private static void flush(
        List<DocChunk> chunks, List<String> headingStack, String sourcePath,
        String anchor, StringBuilder body, boolean sawHeading
    ) {
        String text = body.toString().strip();
        body.setLength(0);
        if (!sawHeading && text.isEmpty()) {
            return; // leading whitespace before the first heading: nothing to index
        }
        if (!sawHeading) {
            // Body before any heading (an unusual .adoc with no title): no breadcrumb, no anchor.
            chunks.add(new DocChunk(List.of(), sourcePath, "", text));
            return;
        }
        var path = new ArrayList<String>(headingStack.size());
        for (String h : headingStack) {
            if (!h.isEmpty()) {
                path.add(h);
            }
        }
        chunks.add(new DocChunk(path, sourcePath, anchor, text));
    }

    /**
     * The fence delimiter a line opens, or null. A fence is a run of four-or-more of a single block
     * character ({@code - . + *}), or a table fence ({@code |===}). Heading underlines and example
     * blocks ({@code ====}) are excluded by the heading rule requiring whitespace and content after
     * the {@code =} run, so they never reach here as fences.
     */
    private static String fenceDelimiter(String trimmed) {
        if (trimmed.equals("|===")) {
            return "|===";
        }
        if (trimmed.length() >= 4) {
            char c = trimmed.charAt(0);
            if ((c == '-' || c == '.' || c == '+' || c == '*') && trimmed.chars().allMatch(ch -> ch == c)) {
                return trimmed;
            }
        }
        return null;
    }

    /**
     * Slugs a heading into an anchor the way AsciiDoctor does under the site's settings
     * ({@code idprefix} empty, {@code idseparator} {@code -}): lower-case, every run of non
     * alphanumeric characters collapsed to a single {@code -}, leading/trailing separators stripped.
     * An explicit {@code [[id]]} / {@code [#id]} preceding the heading overrides this.
     */
    static String slug(String title) {
        String s = title.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-");
        return s.replaceAll("^-+", "").replaceAll("-+$", "");
    }
}
