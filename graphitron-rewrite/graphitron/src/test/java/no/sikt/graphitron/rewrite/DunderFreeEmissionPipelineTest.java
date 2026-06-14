package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.javapoet.JavaFile;
import no.sikt.graphitron.javapoet.TypeSpec;
import no.sikt.graphitron.rewrite.generators.TypeClassGenerator;
import no.sikt.graphitron.rewrite.generators.TypeFetcherGenerator;
import no.sikt.graphitron.rewrite.generators.schema.InputRecordGenerator;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static no.sikt.graphitron.common.configuration.TestConfiguration.DEFAULT_OUTPUT_PACKAGE;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * R271 no-regression guard, in-process variant. Generates {@code TypeSpec}s over schemas that
 * exercise the emitters the rename touched, and asserts that no emitted Java <em>identifier</em>
 * (local, parameter, field) leads with {@code __}. Synthetic SQL column aliases ({@code __sort__},
 * {@code __idx__}, {@code __typename}, {@code __rn__}, {@code __pkN__}) are deliberate
 * collision-avoidance names that reach generated code only as string literals; masking literals
 * (and comments) before the scan leaves them alone. The discriminator is exactly "Java identifier
 * vs string literal in the emitted output".
 *
 * <p>This pipeline-tier scan generates in-process, so it cannot pass over an empty tree the way a
 * generated-sources walk could when the jOOQ catalog jar is clobbered (the {@code -Plocal-db}
 * footgun). It covers the renamed-identifier paths directly:
 * <ul>
 *   <li>the {@code FetcherEmitter} batch-loader and record-copy locals ({@code row}, {@code byPk},
 *       {@code fetched}, {@code ordered}, {@code sourceRow}) and the {@code GeneratorUtils}
 *       accessor-key locals, via the {@code @reference} group;</li>
 *   <li>the {@code TypeFetcherGenerator} validator pre-step ({@code validator}, {@code violations},
 *       {@code violation}, and the de-dundered author-derived {@code arg_<name>} local) and the
 *       {@code InputRecordGenerator.fromMap} locals ({@code c_<name>}, the {@code element} stream
 *       parameter), via the validator + input group;</li>
 *   <li>the {@code MultiTablePolymorphicEmitter} synthetic-column machinery, via the polymorphic
 *       interface group.</li>
 * </ul>
 *
 * <p>{@code GeneratedSourcesLintTest#emittedSourcesHaveNoDunderIdentifiers} runs the complementary
 * compile-tier walk over the <em>full</em> Sakila pipeline output (every generator, with a
 * file-floor), which is the breadth guard for any emitter not reached by the schemas here. The two
 * together pin the invariant in a live test rather than a comment.
 *
 * <p>This is a blanket structural lint over all rendered output, not a per-body string assertion:
 * it asserts a single property (no {@code __}-led identifier), never the content of a specific
 * fetcher body, so it stays clear of the banned-body-assertion rule.
 */
@PipelineTier
class DunderFreeEmissionPipelineTest {

    /** FK-reference + nested-table-field group: drives the FetcherEmitter batch loaders and the
     *  GeneratorUtils accessor-key extraction. */
    private static final String REFERENCE_SDL = """
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

    /** Validator + input group: drives the TypeFetcherGenerator validator pre-step (validator /
     *  violations / arg_ locals) and InputRecordGenerator.fromMap (c_ locals, element lambda). */
    private static final String VALIDATOR_INPUT_SDL = """
        enum TestInputBeanEnum { LOW HIGH }
        input TestInputNested { key: String, value: String }
        input TestInputBean { title: String, rating: TestInputBeanEnum, nested: [TestInputNested!] }
        type ValidationErr @error(handlers: [{handler: VALIDATION}]) {
            path: [String!]!
            message: String!
        }
        type DbErr @error(handlers: [{handler: DATABASE, sqlState: "23503"}]) {
            path: [String!]!
            message: String!
        }
        union SakError = ValidationErr | DbErr
        type SakPayload {
            data: String
            errors: [SakError]
        }
        type Query { x: String }
        type Mutation {
            runWithInputBean(input: TestInputBean): SakPayload
                @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "runSakWithInputBean"})
        }
        """;

    /** Polymorphic interface group: drives MultiTablePolymorphicEmitter's stage-1 projection. */
    private static final String POLYMORPHIC_SDL = """
        interface FilmReferrer { rowId: Int }
        type Inventory implements FilmReferrer @table(name: "inventory") {
          rowId: Int @field(name: "inventory_id")
        }
        type Content implements FilmReferrer @table(name: "content") {
          rowId: Int @field(name: "content_id")
        }
        type Film @table(name: "film") { referrers: [FilmReferrer!]! }
        type Query { film: Film }
        """;

    private static final Pattern DUNDER_IDENTIFIER = Pattern.compile("(?<![\\w$])__\\w+");
    private static final List<String> EXTERNAL_TOKEN_PREFIXES = List.of("__NODE_");

    @Test
    void emittedTypeSpecsHaveNoDunderIdentifiers() {
        List<TypeSpec> emitted = new ArrayList<>();

        GraphitronSchema referenceSchema = TestSchemaHelper.buildSchema(REFERENCE_SDL);
        emitted.addAll(TypeFetcherGenerator.generate(referenceSchema, DEFAULT_OUTPUT_PACKAGE));
        emitted.addAll(TypeClassGenerator.generate(referenceSchema, DEFAULT_OUTPUT_PACKAGE));

        var validatorBundle = TestSchemaHelper.buildBundle(VALIDATOR_INPUT_SDL);
        emitted.addAll(TypeFetcherGenerator.generate(
            validatorBundle.model(), validatorBundle.assembled(), DEFAULT_OUTPUT_PACKAGE));
        emitted.addAll(InputRecordGenerator.generate(
            validatorBundle.model(), validatorBundle.assembled(), DEFAULT_OUTPUT_PACKAGE));

        GraphitronSchema polymorphicSchema = TestSchemaHelper.buildSchema(POLYMORPHIC_SDL);
        emitted.addAll(TypeFetcherGenerator.generate(polymorphicSchema, DEFAULT_OUTPUT_PACKAGE));

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
     *
     * <p>Assumes JavaPoet does not emit Java text blocks ({@code triple-quote} strings): it renders
     * multi-line content as concatenated {@code "..."} literals, so the scanner does not track a
     * text-block mode. If that ever changes, a text block containing {@code //} or a {@code __foo}
     * token could be mis-masked and would need a fifth lexical mode here.
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
