package no.sikt.graphitron.rewrite.test.internal;

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
import no.sikt.graphitron.rewrite.test.tier.CompilationTier;

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
@CompilationTier
class GeneratedSourcesLintTest {

    /** Emitted by {@code graphitron-maven-plugin} into this package path. The plugin
     *  writes under {@code target/generated-sources/graphitron/} — note the
     *  {@code graphitron/} source-folder segment that precedes the Java package path. */
    private static final Path GENERATED_REWRITE_ROOT = Paths.get(
        "target", "generated-sources", "graphitron",
        "no", "sikt", "graphitron", "generated");

    private static final Pattern VAR_DECLARATION = Pattern.compile("\\bvar\\b\\s+\\w+");

    /** Conservative lower bound on the number of emitted {@code .java} files the dunder scan must
     *  see. The Sakila + fixtures pipeline emits well over this; the floor only catches a tree
     *  that is empty or near-empty because generation did not run. */
    private static final int DUNDER_SCAN_FILE_FLOOR = 20;

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

    /**
     * Identifier token that begins with a double underscore. After comments and string/char
     * literals are masked out (see {@link #maskCommentsAndLiterals}), any surviving match is a
     * Java identifier (local, parameter, or field reference) that leads with {@code __}. The
     * negative lookbehind keeps mid-token {@code __} (jOOQ foreign-key constants like
     * {@code FILM__FILM_LANGUAGE_ID_FKEY}) from matching: only an identifier that <em>starts</em>
     * with {@code __} is a lazy dunder.
     */
    private static final Pattern DUNDER_IDENTIFIER = Pattern.compile("(?<![\\w$])__\\w+");

    /**
     * External tokens we consume but do not own, allowed to appear as emitted identifiers: jOOQ's
     * reflective NodeId metadata constants. The Apollo-Federation {@code federation__} /
     * {@code link__} SDL scalar names are not double-underscore-leading and so never match
     * {@link #DUNDER_IDENTIFIER} in the first place; the GraphQL introspection {@code __typename}
     * meta-field (and the synthetic {@code __typename} SQL column sharing its spelling) reaches
     * generated code only as a string literal, so it is masked before the scan and needs no
     * allowlist entry. R436's {@code __src_<col>__} full-parent-row aliases are the same case:
     * string-literal-only ({@code table.COL.as("__src_col__")} / {@code source.get("__src_col__",
     * …)}), masked before the scan, no allowlist entry needed. See development-principles.adoc,
     * "Readability rules".
     */
    private static final List<String> EXTERNAL_TOKEN_PREFIXES = List.of("__NODE_");

    /**
     * The no-regression guard for R271: no emitted Java <em>identifier</em> (local, parameter, or
     * field) may lead with {@code __}. The generator emits every name in scope, including the
     * method signature, so a collision is always knowable at generation time; the {@code __}
     * prefix buys no safety that a readable name plus generation-time awareness does not provide.
     * Author-derived identifiers (a GraphQL argument or input-component name becoming a local)
     * namespace with a readable, deterministic prefix ({@code arg_<name>}, {@code c_<name>}),
     * never a blanket {@code __}.
     *
     * <p>Synthetic SQL column aliases ({@code __sort__}, {@code __idx__}, {@code __rn__},
     * {@code __typename}, {@code __pkN__}, and R436's {@code __src_<col>__} full-parent-row
     * aliases) live in the result-set column namespace alongside consumer-controlled table
     * columns and wrap in {@code __} precisely to avoid colliding with a real column. They are
     * deliberate collision-avoidance names that reach generated code only as string literals, so
     * masking literals (and comments) before the scan leaves them alone; the discriminator is
     * exactly "Java identifier vs string literal in the emitted output". By convention each alias
     * is declared as a named constant carrying its collision rationale at the declaration site;
     * this test does not pin the constant form, only the identifier-vs-literal boundary, so the
     * constant discipline is a readability convention, not an enforced invariant. A reintroduced
     * lazy dunder local surfaces as a bare identifier and trips this with file and line.
     *
     * <p>Scans the real pipeline output over the Sakila + fixtures schemas, which exercise every
     * renamed emitter (batch loaders, the validator pre-step, DML decode locals, the multi-table
     * polymorphic and split-rows pagination machinery, input-record factories). The file-floor
     * assertion guards against a vacuous pass: were generation skipped or the jOOQ catalog jar
     * clobbered (the {@code -Plocal-db} footgun), the tree would be empty and a content-only scan
     * would pass trivially.
     */
    @Test
    void emittedSourcesHaveNoDunderIdentifiers() throws IOException {
        assertThat(GENERATED_REWRITE_ROOT).exists();
        var offenders = new ArrayList<String>();
        var scannedFiles = new java.util.concurrent.atomic.AtomicInteger();
        try (Stream<Path> paths = Files.walk(GENERATED_REWRITE_ROOT)) {
            paths.filter(p -> p.toString().endsWith(".java")).forEach(p -> {
                scannedFiles.incrementAndGet();
                try {
                    String masked = maskCommentsAndLiterals(Files.readString(p));
                    String[] lines = masked.split("\n", -1);
                    for (int i = 0; i < lines.length; i++) {
                        Matcher m = DUNDER_IDENTIFIER.matcher(lines[i]);
                        while (m.find()) {
                            String token = m.group();
                            if (EXTERNAL_TOKEN_PREFIXES.stream().anyMatch(token::startsWith)) continue;
                            offenders.add(p.getFileName() + ":" + (i + 1) + "  " + token);
                        }
                    }
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
        }
        assertThat(scannedFiles.get())
            .as("Generated-sources tree is suspiciously small — generation may not have run, or the\n"
                + "jOOQ catalog jar was clobbered (the -Plocal-db footgun). A dunder scan over an\n"
                + "empty tree passes vacuously; this floor makes that failure loud instead.")
            .isGreaterThanOrEqualTo(DUNDER_SCAN_FILE_FLOOR);
        assertThat(offenders)
            .as("Emitted code must not declare or reference Java identifiers that lead with `__`.\n"
                + "The `__`-prefix is reserved for synthetic SQL column aliases (string literals\n"
                + "with the DB-column-collision rationale), never for Java locals/params/fields.\n"
                + "Pick a readable name (`row`, `byPk`, `fetched`, `violations`); for author-derived\n"
                + "locals use a readable deterministic prefix (`arg_<name>`, `c_<name>`).\n"
                + "See development-principles.adoc, \"Readability rules\".")
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

    /**
     * Returns {@code src} with every line comment, block comment, javadoc, string literal, and
     * char literal replaced by spaces (newlines preserved, so line numbers and column offsets
     * survive). A single left-to-right scan tracks which lexical mode each character is in, so a
     * {@code //} inside a string is not treated as a comment and a {@code "} inside a comment is
     * not treated as a string. After masking, only genuine code characters remain, so a
     * subsequent identifier scan cannot be fooled by a dunder that lives in a comment (e.g. an
     * inline {@code // re-key by __idx__} note) or a synthetic-column string literal
     * ({@code .as("__sort__")}).
     */
    static String maskCommentsAndLiterals(String src) {
        StringBuilder out = new StringBuilder(src.length());
        int mode = 0; // 0 code, 1 line comment, 2 block comment, 3 string, 4 char
        for (int i = 0; i < src.length(); i++) {
            char c = src.charAt(i);
            char next = i + 1 < src.length() ? src.charAt(i + 1) : '\0';
            switch (mode) {
                case 0 -> {
                    if (c == '/' && next == '/') { mode = 1; out.append("  "); i++; }
                    else if (c == '/' && next == '*') { mode = 2; out.append("  "); i++; }
                    else if (c == '"') { mode = 3; out.append(' '); }
                    else if (c == '\'') { mode = 4; out.append(' '); }
                    else out.append(c);
                }
                case 1 -> { // line comment until newline
                    if (c == '\n') { mode = 0; out.append('\n'); }
                    else out.append(c == '\t' ? '\t' : ' ');
                }
                case 2 -> { // block comment / javadoc until */
                    if (c == '*' && next == '/') { mode = 0; out.append("  "); i++; }
                    else out.append(c == '\n' ? '\n' : (c == '\t' ? '\t' : ' '));
                }
                case 3 -> { // string literal until unescaped "
                    if (c == '\\') { out.append("  "); i++; }
                    else if (c == '"') { mode = 0; out.append(' '); }
                    else out.append(c == '\n' ? '\n' : ' ');
                }
                case 4 -> { // char literal until unescaped '
                    if (c == '\\') { out.append("  "); i++; }
                    else if (c == '\'') { mode = 0; out.append(' '); }
                    else out.append(' ');
                }
                default -> out.append(c);
            }
        }
        return out.toString();
    }
}
