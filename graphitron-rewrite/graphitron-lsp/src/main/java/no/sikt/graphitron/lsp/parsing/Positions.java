package no.sikt.graphitron.lsp.parsing;

import org.treesitter.TSPoint;

/**
 * Converts LSP positions (line + UTF-16 code-unit character offset) into
 * the UTF-8 byte offsets and byte-column tree-sitter points the rest of
 * the LSP works in.
 *
 * <p>GraphQL source text is recommended UTF-8 by the October 2021 spec
 * (§2.1.1) and we encode {@code WorkspaceFile.source} that way; LSP
 * clients default to UTF-16 character offsets unless they negotiate
 * {@code positionEncodingKind}, which we don't yet. Multi-byte UTF-8
 * characters in descriptions (think Norwegian {@code æ ø å}) make the
 * direct character-as-byte mapping wrong, so we walk UTF-8 lead bytes
 * and count UTF-16 code units explicitly.
 *
 * <p>Surrogate pairs (codepoints above U+FFFF, which take 4 UTF-8 bytes)
 * count as 2 UTF-16 code units; everything else as 1.
 */
public final class Positions {

    private Positions() {}

    /** Result of resolving an LSP position against a UTF-8 source buffer. */
    public record Resolved(int byteOffset, TSPoint tsPoint) {}

    /**
     * Resolve an LSP {@code (line, charUtf16)} pair against the source
     * buffer. {@code line} is zero-based and counted in {@code \n}
     * separators; {@code charUtf16} is zero-based and counted in UTF-16
     * code units within the line. Positions past the end of the line
     * clamp to the line's end byte; positions past the end of the file
     * clamp to {@code source.length}.
     */
    public static Resolved resolve(byte[] source, int line, int charUtf16) {
        int byteOffset = 0;
        int currentLine = 0;
        while (currentLine < line && byteOffset < source.length) {
            if (source[byteOffset] == '\n') {
                currentLine++;
            }
            byteOffset++;
        }
        int lineStart = byteOffset;

        int utf16Consumed = 0;
        while (utf16Consumed < charUtf16
            && byteOffset < source.length
            && source[byteOffset] != '\n') {
            int lead = source[byteOffset] & 0xFF;
            int codepointBytes = utf8CodepointLength(lead);
            int utf16Units = lead < 0xF0 ? 1 : 2;
            if (utf16Consumed + utf16Units > charUtf16) {
                // Stopping mid-surrogate-pair would split a codepoint;
                // round down to the start of this codepoint.
                break;
            }
            utf16Consumed += utf16Units;
            byteOffset += codepointBytes;
        }
        return new Resolved(byteOffset, new TSPoint(line, byteOffset - lineStart));
    }

    /**
     * Inverse of {@link #resolve}: convert a UTF-8 byte offset within
     * {@code source} back to an LSP {@code (line, character)} pair where
     * {@code character} is in UTF-16 code units. Used by diagnostics and
     * goto-definition to report ranges in the wire format clients expect.
     */
    public static org.eclipse.lsp4j.Position toLspPosition(byte[] source, int byteOffset) {
        int clamped = Math.max(0, Math.min(byteOffset, source.length));
        int line = 0;
        int lineStart = 0;
        for (int i = 0; i < clamped; i++) {
            if (source[i] == '\n') {
                line++;
                lineStart = i + 1;
            }
        }
        int charUtf16 = 0;
        int b = lineStart;
        while (b < clamped) {
            int lead = source[b] & 0xFF;
            int codepointBytes = utf8CodepointLength(lead);
            charUtf16 += lead < 0xF0 ? 1 : 2;
            b += codepointBytes;
        }
        return new org.eclipse.lsp4j.Position(line, charUtf16);
    }

    private static int utf8CodepointLength(int leadByte) {
        if (leadByte < 0x80) return 1;
        if ((leadByte & 0xE0) == 0xC0) return 2;
        if ((leadByte & 0xF0) == 0xE0) return 3;
        if ((leadByte & 0xF8) == 0xF0) return 4;
        return 1;
    }
}
