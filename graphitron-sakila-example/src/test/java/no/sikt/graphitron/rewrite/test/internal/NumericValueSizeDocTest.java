package no.sikt.graphitron.rewrite.test.internal;

import no.sikt.graphitron.generated.util.ColumnValues;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Drift protection between the size bound the generated {@link ColumnValues} applies before it
 * parses a value and the numbers the user manual states for it. The manual states them once, in the
 * paragraph the federation reference anchors as {@code numeric-value-size}; other pages link there.
 * Both halves are pinned: the paragraph names the emitted constants' current values, and no other
 * manual page restates the bound, so a change to either constant fails here until the one paragraph
 * says what the runtime does.
 */
@UnitTier
class NumericValueSizeDocTest {

    private static final String MANUAL = "docs/manual";
    private static final String REFERENCE = "reference/federation.adoc";
    private static final String ANCHOR = "[#numeric-value-size]";

    @Test
    void theReferenceParagraph_namesTheBoundColumnValuesApplies() throws IOException {
        String page = Files.readString(locateManual().resolve(REFERENCE), StandardCharsets.UTF_8);
        int at = page.indexOf(ANCHOR);
        assertThat(at).as(REFERENCE + " anchors the bound as " + ANCHOR).isNotNegative();
        int end = page.indexOf("\n\n", at);
        String paragraph = page.substring(at, end < 0 ? page.length() : end);
        assertThat(paragraph)
            .contains("more than " + ColumnValues.MAX_DIGITS + " fraction digits")
            .contains("longer than " + ColumnValues.MAX_NUMBER_TEXT + " characters");
    }

    @Test
    void noOtherManualPage_restatesTheBound() throws IOException {
        Path manual = locateManual();
        List<String> restating;
        try (Stream<Path> pages = Files.walk(manual)) {
            restating = pages
                .filter(p -> p.toString().endsWith(".adoc"))
                .filter(p -> !p.equals(manual.resolve(REFERENCE)))
                .filter(p -> read(p).contains("fraction digits"))
                .map(p -> manual.relativize(p).toString())
                .toList();
        }
        assertThat(restating).as("pages that state the bound rather than linking to " + REFERENCE + "#numeric-value-size")
            .isEmpty();
    }

    private static String read(Path page) {
        try {
            return Files.readString(page, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private static Path locateManual() {
        Path cwd = Path.of("").toAbsolutePath();
        for (Path p = cwd; p != null; p = p.getParent()) {
            Path candidate = p.resolve(MANUAL);
            if (Files.isDirectory(candidate)) return candidate;
        }
        throw new IllegalStateException("Could not locate " + MANUAL + " by walking up from " + cwd);
    }
}
