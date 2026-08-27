package no.sikt.graphitron.rewrite;

import java.util.ArrayList;
import java.util.List;

/**
 * Single lexer over Java source, projecting per-line views of one lexical habitat at a time:
 * comment / javadoc regions, string / character / text-block literal regions, or code regions.
 * Shared by the prose guards ({@link RoadmapReferenceScanner} and the retired-vocabulary guard),
 * which each pattern-match over exactly one habitat.
 *
 * <p>The lexer tracks code / line-comment / block-comment / string / char / text-block states
 * and appends a character to its line's projected view only when the current state belongs to
 * the requested habitat; every other character is dropped. Comment delimiters and string quotes
 * are never part of any projection, so habitats never bleed into each other and a detector can
 * neither see nor corrupt a neighbouring region. Block comments and text blocks carry lexer
 * state across line boundaries; a line comment ends at its newline, and string / char literals
 * reset there defensively (they cannot legally span lines).
 *
 * <p>Two granularities of the literal habitat are exposed, and the difference is load-bearing.
 * {@link #strings} concatenates every literal on a line into one view, which is what a detector
 * matching <em>within</em> a literal wants. {@link #literalsByLine} keeps each literal as its own
 * element, which is what a detector matching an <em>entire</em> literal needs: two adjacent
 * literals project into one indistinguishable run under {@code strings}, so a whole-value rule
 * run over it would see a value neither literal has.
 */
final class JavaSourceRegions {

    /** Which lexer states a projection collects. */
    enum Habitat { CODE, COMMENT, STRING_LITERAL }

    /**
     * Marks a literal boundary inside the literal projection. A Java literal cannot contain a
     * NUL: {@code \0} is an octal escape, and the escape handler below appends the escaped
     * character ({@code '0'}) rather than decoding it, so this sentinel cannot collide with
     * literal content. It never escapes the class; {@link #literalsByLine} splits on it.
     */
    private static final char LITERAL_BOUNDARY = '\0';

    private JavaSourceRegions() {}

    /** One string per line holding only that line's comment / javadoc characters. */
    static String[] comments(String source) {
        return project(source, Habitat.COMMENT);
    }

    /**
     * One string per line holding only that line's string, character, and text-block literal
     * content, with adjacent literals concatenated. For a rule about an entire literal value use
     * {@link #literalsByLine} instead.
     */
    static String[] strings(String source) {
        return stripBoundaries(project(source, Habitat.STRING_LITERAL));
    }

    /**
     * One list per line of that line's string, character, and text-block literals, each literal
     * its own element and empty literals dropped. The list is empty for a line holding none.
     *
     * <p>This is the projection a whole-literal rule needs. {@code Set.of("a/b", "c/d")} puts two
     * literals on one line; {@link #strings} renders them as the single run {@code a/bc/d}, in
     * which neither original value can be recognised and a spurious third one appears.
     */
    static List<List<String>> literalsByLine(String source) {
        String[] joined = project(source, Habitat.STRING_LITERAL);
        List<List<String>> out = new ArrayList<>(joined.length);
        for (String line : joined) {
            List<String> literals = new ArrayList<>();
            for (String literal : line.split("\0", -1)) {
                if (!literal.isEmpty()) literals.add(literal);
            }
            out.add(literals);
        }
        return out;
    }

    /**
     * One string per line holding only that line's code characters, with all comment and literal
     * content excluded. Delimiters and quotes are dropped like everywhere else; identifiers and
     * operators survive, which is all a token-level detector needs.
     */
    static String[] code(String source) {
        return project(source, Habitat.CODE);
    }

    private static String[] project(String source, Habitat habitat) {
        String[] lines = source.split("\n", -1);
        StringBuilder[] out = new StringBuilder[lines.length];
        for (int i = 0; i < out.length; i++) out[i] = new StringBuilder();
        boolean wantCode = habitat == Habitat.CODE;
        boolean wantComment = habitat == Habitat.COMMENT;
        boolean wantString = habitat == Habitat.STRING_LITERAL;
        final int CODE = 0, LINE = 1, BLOCK = 2, STRING = 3, CHAR = 4, TEXT = 5;
        int state = CODE, line = 0, n = source.length();
        for (int i = 0; i < n; i++) {
            char c = source.charAt(i);
            char c2 = i + 1 < n ? source.charAt(i + 1) : '\0';
            char c3 = i + 2 < n ? source.charAt(i + 2) : '\0';
            if (c == '\n') {
                // A line comment ends at the newline; string / char literals cannot legally
                // span lines, so reset those defensively. Block comments and text blocks
                // carry across the boundary, so leave BLOCK / TEXT state intact.
                if (state == LINE || state == STRING || state == CHAR) state = CODE;
                line++;
                continue;
            }
            switch (state) {
                case CODE:
                    if (c == '/' && c2 == '/') { state = LINE; i++; }
                    else if (c == '/' && c2 == '*') { state = BLOCK; i++; }
                    else if (c == '"' && c2 == '"' && c3 == '"') { state = TEXT; i += 2; }
                    else if (c == '"') state = STRING;
                    else if (c == '\'') state = CHAR;
                    else if (wantCode) out[line].append(c);
                    break;
                case LINE: if (wantComment) out[line].append(c); break;
                case BLOCK:
                    if (c == '*' && c2 == '/') { state = CODE; i++; }
                    else if (wantComment) out[line].append(c);
                    break;
                case STRING:
                    if (c == '\\') { if (wantString && c2 != '\0') out[line].append(c2); i++; }
                    else if (c == '"') { state = CODE; if (wantString) out[line].append(LITERAL_BOUNDARY); }
                    else if (wantString) out[line].append(c);
                    break;
                case CHAR:
                    if (c == '\\') i++;
                    else if (c == '\'') { state = CODE; if (wantString) out[line].append(LITERAL_BOUNDARY); }
                    else if (wantString) out[line].append(c);
                    break;
                case TEXT:
                    if (c == '"' && c2 == '"' && c3 == '"') {
                        state = CODE;
                        i += 2;
                        if (wantString) out[line].append(LITERAL_BOUNDARY);
                    }
                    else if (wantString) out[line].append(c);
                    break;
            }
        }
        String[] result = new String[lines.length];
        for (int i = 0; i < lines.length; i++) result[i] = out[i].toString();
        return result;
    }

    /**
     * Drops the literal boundaries so {@link #strings} keeps the concatenated view its callers
     * have always seen. Only the literal habitat carries boundaries; the other two are untouched
     * by construction, since nothing appends the sentinel outside the literal states.
     */
    private static String[] stripBoundaries(String[] byLine) {
        for (int i = 0; i < byLine.length; i++) {
            if (byLine[i].indexOf(LITERAL_BOUNDARY) >= 0) {
                byLine[i] = byLine[i].replace(String.valueOf(LITERAL_BOUNDARY), "");
            }
        }
        return byLine;
    }
}
