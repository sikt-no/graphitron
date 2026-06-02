package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.javapoet.JavaFile;
import no.sikt.graphitron.javapoet.TypeSpec;
import no.sikt.graphitron.rewrite.generators.TypeClassGenerator;
import no.sikt.graphitron.rewrite.generators.TypeFetcherGenerator;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static no.sikt.graphitron.common.configuration.TestConfiguration.DEFAULT_OUTPUT_PACKAGE;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * R271 no-regression guard, in-process variant. Generates {@code TypeSpec}s over a representative
 * schema and asserts that no emitted Java <em>identifier</em> (local, parameter, field) leads with
 * {@code __}. Synthetic SQL column aliases ({@code __sort__}, {@code __idx__}, {@code __typename},
 * {@code __rn__}, {@code __pkN__}) are deliberate collision-avoidance names that reach generated
 * code only as string literals; masking literals (and comments) before the scan leaves them alone.
 * The discriminator is exactly "Java identifier vs string literal in the emitted output".
 *
 * <p>This pipeline-tier scan generates in-process, so it cannot pass over an empty tree the way a
 * generated-sources walk could when the jOOQ catalog jar is clobbered (the {@code -Plocal-db}
 * footgun). {@link no.sikt.graphitron.rewrite.test.internal.GeneratedSourcesLintTest} runs the
 * complementary compile-tier walk over the full Sakila pipeline output with a file-floor; together
 * the two pin the invariant in a live test rather than a comment.
 *
 * <p>This is a blanket structural lint over all rendered output, not a per-body string assertion:
 * it asserts a single property (no {@code __}-led identifier), never the content of a specific
 * fetcher body, so it stays clear of the banned-body-assertion rule.
 */
@PipelineTier
class DunderFreeEmissionPipelineTest {

    /** A schema exercising FK references (batch-loader emission), nested table fields, and an
     *  ID-typed leaf, so the rendered fetcher and type-class bodies carry the kinds of locals the
     *  R271 rename touched. */
    private static final String REPRESENTATIVE_SDL = """
        type Language @table(name: "language") { name: String }
        type Film @table(name: "film") {
            title: String
            language: Language @reference(path: [{key: "film_language_id_fkey"}])
        }
        type Query {
            film: Film
            films: [Film]
        }
        """;

    private static final Pattern DUNDER_IDENTIFIER = Pattern.compile("(?<![\\w$])__\\w+");
    private static final List<String> EXTERNAL_TOKEN_PREFIXES = List.of("__NODE_");

    @Test
    void emittedTypeSpecsHaveNoDunderIdentifiers() {
        GraphitronSchema schema = TestSchemaHelper.buildSchema(REPRESENTATIVE_SDL);

        List<TypeSpec> emitted = new ArrayList<>();
        emitted.addAll(TypeFetcherGenerator.generate(schema, DEFAULT_OUTPUT_PACKAGE));
        emitted.addAll(TypeClassGenerator.generate(schema, DEFAULT_OUTPUT_PACKAGE));

        assertThat(emitted)
            .as("In-process generation produced no TypeSpecs — the scan would pass vacuously.")
            .isNotEmpty();

        var offenders = new ArrayList<String>();
        for (TypeSpec type : emitted) {
            String rendered = JavaFile.builder(DEFAULT_OUTPUT_PACKAGE, type).build().toString();
            String masked = maskCommentsAndLiterals(rendered);
            Matcher m = DUNDER_IDENTIFIER.matcher(masked);
            while (m.find()) {
                String token = m.group();
                if (EXTERNAL_TOKEN_PREFIXES.stream().anyMatch(token::startsWith)) continue;
                offenders.add(type.name() + ": " + token);
            }
        }

        assertThat(offenders)
            .as("Emitted code must not declare or reference Java identifiers that lead with `__`.\n"
                + "The `__`-prefix is reserved for synthetic SQL column aliases (string literals\n"
                + "with the DB-column-collision rationale), never for Java locals/params/fields.\n"
                + "See rewrite-design-principles.adoc, \"Generated code is read and debugged\".")
            .isEmpty();
    }

    /**
     * Masks line comments, block comments, javadoc, string literals, and char literals with spaces
     * (newlines preserved) in one left-to-right scan, so a {@code //} inside a string is not read
     * as a comment and a {@code "} inside a comment is not read as a string. After masking, only
     * genuine code remains, so the identifier scan cannot be fooled by a dunder living in a comment
     * or a synthetic-column string literal.
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
                case 1 -> {
                    if (c == '\n') { mode = 0; out.append('\n'); }
                    else out.append(c == '\t' ? '\t' : ' ');
                }
                case 2 -> {
                    if (c == '*' && next == '/') { mode = 0; out.append("  "); i++; }
                    else out.append(c == '\n' ? '\n' : (c == '\t' ? '\t' : ' '));
                }
                case 3 -> {
                    if (c == '\\') { out.append("  "); i++; }
                    else if (c == '"') { mode = 0; out.append(' '); }
                    else out.append(c == '\n' ? '\n' : ' ');
                }
                case 4 -> {
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
