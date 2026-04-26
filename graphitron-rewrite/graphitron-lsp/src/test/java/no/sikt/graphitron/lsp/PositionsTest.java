package no.sikt.graphitron.lsp;

import no.sikt.graphitron.lsp.parsing.Positions;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies UTF-16 (LSP wire format) → UTF-8 byte-offset conversion against
 * mixed Norwegian / supplementary-plane content. GraphQL source text is
 * UTF-8 by spec recommendation, but LSP positions arrive in UTF-16 code
 * units unless the client negotiates otherwise; the helper has to bridge
 * the two encodings.
 */
class PositionsTest {

    @Test
    void asciiPositionsMapBytewise() {
        byte[] source = "type Foo {}\n".getBytes(StandardCharsets.UTF_8);
        var resolved = Positions.resolve(source, 0, 5);
        assertThat(resolved.byteOffset()).isEqualTo(5);
        assertThat(resolved.tsPoint().getRow()).isEqualTo(0);
        assertThat(resolved.tsPoint().getColumn()).isEqualTo(5);
    }

    @Test
    void norwegianTwoByteCharactersAdvanceTwoBytesPerUtf16Unit() {
        // "håndtering" — 10 UTF-16 units, 11 UTF-8 bytes (å is 2 bytes).
        byte[] source = "håndtering".getBytes(StandardCharsets.UTF_8);
        assertThat(source).hasSize(11);

        // UTF-16 char index 0 → byte 0 (start of 'h').
        assertThat(Positions.resolve(source, 0, 0).byteOffset()).isEqualTo(0);
        // UTF-16 char index 1 → byte 1 (start of 'å').
        assertThat(Positions.resolve(source, 0, 1).byteOffset()).isEqualTo(1);
        // UTF-16 char index 2 → byte 3 (past 'å').
        assertThat(Positions.resolve(source, 0, 2).byteOffset()).isEqualTo(3);
        // UTF-16 char index 10 → byte 11 (end of string).
        assertThat(Positions.resolve(source, 0, 10).byteOffset()).isEqualTo(11);
    }

    @Test
    void surrogatePairCountsAsTwoUtf16Units() {
        // 🦀 (U+1F980, CRAB) is 4 UTF-8 bytes / 2 UTF-16 code units.
        byte[] source = "🦀x".getBytes(StandardCharsets.UTF_8);
        assertThat(source).hasSize(5);

        // UTF-16 char index 2 → byte 4 (just past the crab).
        assertThat(Positions.resolve(source, 0, 2).byteOffset()).isEqualTo(4);
        // UTF-16 char index 3 → byte 5 (just past 'x').
        assertThat(Positions.resolve(source, 0, 3).byteOffset()).isEqualTo(5);
    }

    @Test
    void positionMidSurrogatePairClampsToCodepointStart() {
        // Asking for char 1 (mid surrogate pair) rounds down to byte 0;
        // splitting a codepoint would corrupt the source buffer.
        byte[] source = "🦀".getBytes(StandardCharsets.UTF_8);
        assertThat(Positions.resolve(source, 0, 1).byteOffset()).isEqualTo(0);
    }

    @Test
    void multiLineWalkSkipsAcrossNewlines() {
        // Line 0 has a multi-byte char; line 1 indexes from the line start.
        String src = "type å { f: Int }\ntype Bar { f: Int }\n";
        byte[] source = src.getBytes(StandardCharsets.UTF_8);

        // Line 1, char 5 → byte offset of 'B' minus initial line bytes.
        var resolved = Positions.resolve(source, 1, 5);
        // Line 0 occupies bytes 0..18 (UTF-8 length of "type å { f: Int }\n").
        int line0Bytes = "type å { f: Int }\n".getBytes(StandardCharsets.UTF_8).length;
        assertThat(resolved.byteOffset()).isEqualTo(line0Bytes + 5);
        assertThat(resolved.tsPoint().getRow()).isEqualTo(1);
        assertThat(resolved.tsPoint().getColumn()).isEqualTo(5);
    }

    @Test
    void positionPastEndOfLineClampsToLineEnd() {
        byte[] source = "ab\ncd\n".getBytes(StandardCharsets.UTF_8);
        // Asking for char 99 on line 0 stops at the newline.
        var resolved = Positions.resolve(source, 0, 99);
        assertThat(resolved.byteOffset()).isEqualTo(2);
    }

    @Test
    void inverseConversionRoundTripsAscii() {
        byte[] source = "type Foo {}\nbar\n".getBytes(StandardCharsets.UTF_8);
        var p = Positions.toLspPosition(source, 12);
        assertThat(p.getLine()).isEqualTo(1);
        assertThat(p.getCharacter()).isZero();
    }

    @Test
    void inverseConversionShrinksUtf8BytesToUtf16Units() {
        // håndtering: byte 11 is one past the last byte; UTF-16 char 10.
        byte[] source = "håndtering".getBytes(StandardCharsets.UTF_8);
        var p = Positions.toLspPosition(source, 11);
        assertThat(p.getLine()).isZero();
        assertThat(p.getCharacter()).isEqualTo(10);
    }

    @Test
    void inverseConversionCountsSurrogatesAsTwoUtf16Units() {
        // 🦀 (4 UTF-8 bytes / 2 UTF-16 code units) followed by 'x'.
        byte[] source = "🦀x".getBytes(StandardCharsets.UTF_8);
        // Byte offset 4 is just past the crab; that's UTF-16 char 2.
        assertThat(Positions.toLspPosition(source, 4).getCharacter()).isEqualTo(2);
        // Byte offset 5 is just past 'x'; UTF-16 char 3.
        assertThat(Positions.toLspPosition(source, 5).getCharacter()).isEqualTo(3);
    }
}
