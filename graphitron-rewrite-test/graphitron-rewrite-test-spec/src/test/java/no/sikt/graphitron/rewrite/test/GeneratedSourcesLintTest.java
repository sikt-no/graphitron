package no.sikt.graphitron.rewrite.test;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Lints emitted source files for generator-hygiene rules that would silently
 * degrade readability or grep-based structural assertions.
 *
 * <p>Currently enforces one rule: generator-emitted code must never declare a
 * variable with {@code var}. Explicit types keep the emitted source searchable
 * by type name and make inference surprises visible at review time. The rule
 * applies to both assignment LHS ({@code var x = ...}) and for-loop variables
 * ({@code for (var x : xs)}); the regex is intentionally loose enough to match
 * both.
 */
class GeneratedSourcesLintTest {

    /** Emitted by {@code graphitron-maven-plugin} into this package path. */
    private static final Path GENERATED_REWRITE_ROOT = Paths.get(
        "target", "generated-sources",
        "no", "sikt", "graphitron", "rewrite", "test", "generated", "rewrite");

    private static final Pattern VAR_DECLARATION = Pattern.compile("\\bvar\\b\\s+\\w+");

    /** The jOOQ tables package for the test fixtures. Full-package qualification of any
     *  class under this prefix inside an emitted fetcher body indicates an importer
     *  collision (two classes share a simple name) that the local-variable rename in
     *  {@code §4} is meant to prevent. */
    private static final String JOOQ_TABLES_PACKAGE_PREFIX = "no.sikt.graphitron.rewrite.test.jooq.tables.";

    @Test
    void emittedSourcesDoNotUseVar() throws IOException {
        assertThat(GENERATED_REWRITE_ROOT).exists();
        var offenders = new ArrayList<String>();
        try (Stream<Path> paths = Files.walk(GENERATED_REWRITE_ROOT)) {
            paths.filter(p -> p.toString().endsWith(".java")).forEach(p -> {
                try {
                    List<String> lines = Files.readAllLines(p);
                    for (int i = 0; i < lines.size(); i++) {
                        String line = lines.get(i);
                        if (isCommentLine(line)) continue;
                        Matcher m = VAR_DECLARATION.matcher(line);
                        while (m.find()) {
                            offenders.add(p.getFileName() + ":" + (i + 1) + "  " + line.trim());
                        }
                    }
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
        }
        assertThat(offenders)
            .as("Generated sources must not emit `var` declarations.\n"
                + "Explicit types keep emitted code searchable and make inference\n"
                + "surprises visible at emission time. Replace with the JavaPoet $T\n"
                + "substitution using the known type.")
            .isEmpty();
    }

    @Test
    void fetcherBodiesDoNotFullyQualifyJooqTables() throws IOException {
        Path fetchersRoot = GENERATED_REWRITE_ROOT.resolve("fetchers");
        assertThat(fetchersRoot).exists();
        var offenders = new ArrayList<String>();
        try (Stream<Path> paths = Files.walk(fetchersRoot)) {
            paths.filter(p -> p.toString().endsWith(".java")).forEach(p -> {
                try {
                    List<String> lines = Files.readAllLines(p);
                    boolean inImports = true;
                    for (int i = 0; i < lines.size(); i++) {
                        String line = lines.get(i);
                        String trimmed = line.trim();
                        if (trimmed.startsWith("class ") || trimmed.contains(" class ")) {
                            inImports = false;
                        }
                        if (inImports) continue;
                        if (isCommentLine(line)) continue;
                        if (line.contains(JOOQ_TABLES_PACKAGE_PREFIX)) {
                            offenders.add(p.getFileName() + ":" + (i + 1) + "  " + trimmed);
                        }
                    }
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
        }
        assertThat(offenders)
            .as("Fetcher bodies must not fully-qualify jOOQ table classes.\n"
                + "Full-package qualification inside a method body always means the\n"
                + "importer could not import the jOOQ table class because a sibling\n"
                + "simple-name (the generated mapper class) already occupies that slot.\n"
                + "The table-local rename to <entity>Table disambiguates the two.")
            .isEmpty();
    }

    private static boolean isCommentLine(String line) {
        String trimmed = line.trim();
        return trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*");
    }
}
