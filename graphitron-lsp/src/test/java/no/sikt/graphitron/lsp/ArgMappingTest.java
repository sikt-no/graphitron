package no.sikt.graphitron.lsp;

import no.sikt.graphitron.lsp.parsing.ArgMapping;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Cursor-decomposition and structural-parse coverage for {@link ArgMapping}
 * against well-formed and malformed inputs. No workspace; pure string slicing.
 */
class ArgMappingTest {

    @Test
    void parsesTwoEntriesIgnoringWhitespace() {
        var entries = ArgMapping.parse("city: cityNames, country: countryId");
        assertThat(entries).hasSize(2);
        assertThat(entries.get(0).java().text()).isEqualTo("city");
        assertThat(entries.get(0).graphql().text()).isEqualTo("cityNames");
        assertThat(entries.get(1).java().text()).isEqualTo("country");
        assertThat(entries.get(1).graphql().text()).isEqualTo("countryId");
    }

    @Test
    void blankContentIsIdentityWithNoEntries() {
        assertThat(ArgMapping.parse("")).isEmpty();
        assertThat(ArgMapping.parse("   ")).isEmpty();
    }

    @Test
    void trailingCommaYieldsEmptyEntry() {
        var entries = ArgMapping.parse("a: b,");
        assertThat(entries).hasSize(2);
        assertThat(entries.get(1).isBlank()).isTrue();
    }

    @Test
    void doubleCommaYieldsEmptyMiddleEntry() {
        var entries = ArgMapping.parse("a: b,, c: d");
        assertThat(entries).hasSize(3);
        assertThat(entries.get(1).isBlank()).isTrue();
    }

    @Test
    void danglingColonHasEmptySide() {
        var left = ArgMapping.parse(": value").get(0);
        assertThat(left.hasColon()).isTrue();
        assertThat(left.java().isEmpty()).isTrue();
        assertThat(left.graphql().text()).isEqualTo("value");

        var right = ArgMapping.parse("param:").get(0);
        assertThat(right.hasColon()).isTrue();
        assertThat(right.java().text()).isEqualTo("param");
        assertThat(right.graphql().isEmpty()).isTrue();
    }

    @Test
    void noColonEntryIsLeftOnly() {
        var entry = ArgMapping.parse("param").get(0);
        assertThat(entry.hasColon()).isFalse();
        assertThat(entry.java().text()).isEqualTo("param");
        assertThat(entry.graphql().isEmpty()).isTrue();
    }

    @Test
    void dotPathRightSegmentPreserved() {
        var entry = ArgMapping.parse("p: input.nested.leaf").get(0);
        assertThat(entry.graphql().text()).isEqualTo("input.nested.leaf");
    }

    @Test
    void locateCursorOnLeftSide() {
        String content = "city: cityNames";
        var cursor = ArgMapping.locate(content, "ci".length()).orElseThrow();
        assertThat(cursor.entryIndex()).isZero();
        assertThat(cursor.side()).isEqualTo(ArgMapping.Side.LEFT);
        assertThat(cursor.token().text()).isEqualTo("city");
    }

    @Test
    void locateCursorOnRightSide() {
        String content = "city: cityNames";
        var cursor = ArgMapping.locate(content, content.indexOf("cityNames") + 3).orElseThrow();
        assertThat(cursor.side()).isEqualTo(ArgMapping.Side.RIGHT);
        assertThat(cursor.token().text()).isEqualTo("cityNames");
    }

    @Test
    void locateCursorInSecondEntry() {
        String content = "a: b, country: countryId";
        var cursor = ArgMapping.locate(content, content.indexOf("country")).orElseThrow();
        assertThat(cursor.entryIndex()).isEqualTo(1);
        assertThat(cursor.side()).isEqualTo(ArgMapping.Side.LEFT);
    }

    @Test
    void locateCursorRightAfterColonIsEmptyRightToken() {
        String content = "param: ";
        var cursor = ArgMapping.locate(content, content.length()).orElseThrow();
        assertThat(cursor.side()).isEqualTo(ArgMapping.Side.RIGHT);
        assertThat(cursor.token().isEmpty()).isTrue();
        assertThat(cursor.token().start()).isEqualTo(content.length());
    }

    @Test
    void locateOnBlankContentIsLeftAtCursor() {
        var cursor = ArgMapping.locate("", 0).orElseThrow();
        assertThat(cursor.side()).isEqualTo(ArgMapping.Side.LEFT);
        assertThat(cursor.token().isEmpty()).isTrue();
    }
}
