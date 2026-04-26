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

    /** Emitted by {@code graphitron-maven-plugin} into this package path. The plugin
     *  writes under {@code target/generated-sources/graphitron/} — note the
     *  {@code graphitron/} source-folder segment that precedes the Java package path. */
    private static final Path GENERATED_REWRITE_ROOT = Paths.get(
        "target", "generated-sources", "graphitron",
        "no", "sikt", "graphitron", "generated");

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
                        String codeOnly = stripInlineComment(line);
                        Matcher m = VAR_DECLARATION.matcher(codeOnly);
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
    void entityConditionsClassesHaveNoGraphqlJavaImports() throws IOException {
        Path conditionsRoot = GENERATED_REWRITE_ROOT.resolve("conditions");
        assertThat(conditionsRoot).exists();
        var offenders = new ArrayList<String>();
        try (Stream<Path> paths = Files.walk(conditionsRoot)) {
            paths.filter(p -> p.toString().endsWith("Conditions.java"))
                .filter(p -> !p.getFileName().toString().equals("QueryConditions.java"))
                .filter(p -> !p.getFileName().toString().equals("MutationConditions.java"))
                .forEach(p -> {
                    try {
                        for (String line : Files.readAllLines(p)) {
                            String trimmed = line.trim();
                            if (trimmed.startsWith("import graphql.")
                                    || trimmed.startsWith("import static graphql.")) {
                                offenders.add(p.getFileName() + "  " + trimmed);
                            }
                        }
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                });
        }
        assertThat(offenders)
            .as("Entity-scoped *Conditions classes (FilmConditions, LanguageConditions, …)\n"
                + "are pure functions — they must not depend on graphql-java runtime types.\n"
                + "Env-aware argument extraction and composition live in QueryConditions /\n"
                + "MutationConditions (the env-aware shim layer), not inside the entity classes.")
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
                        String codeOnly = stripInlineComment(line);
                        if (codeOnly.contains(JOOQ_TABLES_PACKAGE_PREFIX)) {
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

    /** FQNs that must not appear as imports in any emitted source file. Plan
     *  §Tests — no legacy runtime dependency may reach the emitted output:
     *  {@code RuntimeWiring} / {@code TypeRuntimeWiring} / {@code SchemaGenerator}
     *  are the SDL-driven schema path Commit C replaced with a programmatic
     *  {@code GraphQLSchema.Builder}; {@code SchemaReadingHelper} is the
     *  {@code graphitron-common} SDL parser the generator no longer needs at
     *  runtime; {@code no.sikt.graphql.GraphitronContext} is the upstream
     *  interface now replaced by the generated
     *  {@code <outputPackage>.schema.GraphitronContext}. Match is on
     *  FQN so the generated context interface is unaffected. */
    private static final List<String> FORBIDDEN_IMPORTS = List.of(
        "graphql.schema.idl.RuntimeWiring",
        "graphql.schema.idl.TypeRuntimeWiring",
        "graphql.schema.idl.SchemaGenerator",
        "no.sikt.graphql.schema.SchemaReadingHelper",
        "no.sikt.graphql.GraphitronContext"
    );

    @Test
    void emittedSourcesDoNotImportLegacyRuntimeTypes() throws IOException {
        assertThat(GENERATED_REWRITE_ROOT).exists();
        var offenders = new ArrayList<String>();
        try (Stream<Path> paths = Files.walk(GENERATED_REWRITE_ROOT)) {
            paths.filter(p -> p.toString().endsWith(".java")).forEach(p -> {
                try {
                    for (String line : Files.readAllLines(p)) {
                        String trimmed = line.trim();
                        if (!trimmed.startsWith("import ")) continue;
                        String imported = trimmed
                            .replaceFirst("^import\\s+(static\\s+)?", "")
                            .replaceFirst(";.*$", "")
                            .trim();
                        for (String forbidden : FORBIDDEN_IMPORTS) {
                            if (imported.equals(forbidden) || imported.startsWith(forbidden + ".")) {
                                offenders.add(p.getFileName() + "  " + trimmed);
                                break;
                            }
                        }
                    }
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
        }
        assertThat(offenders)
            .as("Emitted sources must not import legacy runtime types — the programmatic\n"
                + "schema path is the only one supported. The ratchet guards against\n"
                + "regressions into the SDL + RuntimeWiring shape or re-introduction of\n"
                + "the upstream GraphitronContext interface.")
            .isEmpty();
    }

    private static boolean isCommentLine(String line) {
        String trimmed = line.trim();
        return trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*");
    }

    /** Strips a trailing {@code //}-style comment so a mid-line comment can't trip a
     *  regex match (e.g. {@code foo(); // some var bar}). Doesn't attempt to handle
     *  {@code //} inside a string literal — generator-emitted code doesn't produce
     *  string literals with a {@code //} sequence today. */
    private static String stripInlineComment(String line) {
        int idx = line.indexOf("//");
        return idx < 0 ? line : line.substring(0, idx);
    }
}
