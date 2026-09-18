package no.sikt.graphitron.rewrite.test.internal;

import graphql.schema.idl.SchemaParser;
import no.sikt.graphitron.model.capture.document.GraphQLAssemblyCapture;
import no.sikt.graphitron.model.capture.document.GraphQLAstCapture;
import no.sikt.graphitron.model.capture.document.GraphQLSourceCapture;
import no.sikt.graphitron.model.capture.document.GraphitronAstCapture;
import no.sikt.graphitron.model.diagnostics.BuildWarning;
import no.sikt.graphitron.model.read.StoreHandle;
import no.sikt.graphitron.model.run.GraphIdentity;
import no.sikt.graphitron.model.run.SubjectConfig;
import no.sikt.graphitron.model.schema.SchemaLoader;
import no.sikt.graphitron.model.schema.input.SchemaRecipe;
import no.sikt.graphitron.model.test.FactStores;
import no.sikt.graphitron.model.test.SeededStore;
import no.sikt.graphitron.rewrite.lint.LintEngine;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.jooq.DSLContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static no.sikt.graphitron.model.Tables.LINT_VIOLATION;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The lint statements against the visitors they replace, over this module's own schema.
 *
 * <p>The comparison beside the statements uses a corpus written to exercise every rule, which
 * proves agreement on the shapes its author thought of and is exactly the assurance a rewrite
 * should not rest on. This one reads the schema the example module already ships and generates
 * from: some five thousand lines nobody wrote for a lint test, carrying whatever the authors
 * happened to do. What it can show that a hand-built fixture cannot is a disagreement in a shape
 * nobody predicted.
 *
 * <p>Both halves answer one text. The store is captured from the file on disk and the statements
 * run over the captured rows; the walk parses the same file, merged with the bundled vocabulary as
 * its own document so that positions stay the file's own. Findings reduce to rule and position, so
 * a statement firing where the visitor does not, or missing where it does, names the coordinate.
 *
 * <p>This dissolves with the walk. It compares two implementations of one rule set, so it has
 * nothing to say once there is one, and it is not a test of the rules themselves: those are pinned
 * by the per-rule cases beside the statements, which outlive both.
 */
@PipelineTier
class LintSakilaShadowTest {

    private static final String GRAPH = "lint-sakila-shadow";

    /**
     * The rules this schema breaks, pinned so that one it stops breaking fails here rather than
     * quietly shrinking what the comparison covers.
     *
     * <p>Six of the nine, and the three missing are the point of a found corpus rather than a
     * defect in it: every type here is already PascalCase, and the deprecations already carry
     * reasons and name nothing the vocabulary has retired. A constructed corpus can be made to
     * break all nine and is, beside the statements; this one says what a real schema does, and
     * demanding nine of it would mean writing violations into a schema that does not have them.
     *
     * <p>The two cover each other. field-names-camel-case is reached here and was the one the
     * constructed corpus originally missed, which is the case for keeping both.
     */
    private static final List<String> RULES_REACHED = List.of(
        "enum-values-screaming-snake-case",
        "field-names-camel-case",
        "input-and-argument-names-camel-case",
        "input-object-name-suffix",
        "no-typename-prefix",
        "types-and-fields-have-descriptions");

    /** The module's own schema, as the generator reads it. */
    private static final Path SCHEMA =
        Path.of("src", "main", "resources", "graphql", "schema.graphqls");

    @Test
    @DisplayName("over a real schema, the statements and the visitors agree finding for finding")
    void theRowsAgreeWithTheWalkOnTheExampleSchema() {
        String sdl = read(SCHEMA);
        assertThat(sdl.lines().count())
            .as("the corpus is the real schema rather than a fixture that shrank")
            .isGreaterThan(1000L);

        Path directory = temporaryDirectory();
        write(directory, sdl);
        try (var store = FactStores.inMemory()) {
            SeededStore.seedGraph(store.dsl(), GRAPH);
            // No writer to run: the rules are the view's arms, so capturing the
            // corpus is the whole of making the findings exist.
            var readAt = LocalDateTime.now();
            var identity = new GraphIdentity(GRAPH, directory);
            var documents = GraphQLSourceCapture.capture(
                store.dsl(), identity, config(directory), readAt);
            GraphQLAstCapture.capture(store.dsl(), identity, documents, readAt);
            GraphitronAstCapture.capture(store.dsl(), identity, documents, readAt);
            GraphQLAssemblyCapture.capture(store.dsl(), identity, documents, readAt);

            var walked = fromTheWalk(store.dsl(), sdl);
            var stored = fromTheRows(store.dsl());

            assertThat(stored)
                .as("every finding the walk reports over the example schema, at the position it"
                    + " reports it, and nothing the walk does not")
                .containsExactlyInAnyOrderElementsOf(walked);
            assertThat(rulesIn(walked))
                .as("the rules this schema still reaches, without which two empty sets would agree"
                    + " and the comparison would report a parity it never made")
                .containsExactlyInAnyOrderElementsOf(RULES_REACHED);
        }
    }

    /**
     * What the surviving engine reports. The bundled vocabulary is merged as its own document
     * rather than concatenated, which would shift every line of the corpus by its length and turn
     * a comparison of positions into one of offsets.
     */
    private static Set<String> fromTheWalk(DSLContext dsl, String sdl) {
        var registry = new SchemaParser().parse(sdl);
        registry.merge(new SchemaParser().parse(SchemaLoader.directivesSdl()));
        var findings = new TreeSet<String>();
        LintEngine.builtIn().run(registry, new StoreHandle(dsl, GRAPH)).stream()
            .map(BuildWarning.LintFinding.class::cast)
            .forEach(f -> findings.add(f.rule().id() + " @ " + f.location().getLine()
                + ":" + f.location().getColumn()));
        return findings;
    }

    /** The rules a set of findings names, each finding being {@code rule @ line:column}. */
    private static Set<String> rulesIn(Set<String> findings) {
        var rules = new TreeSet<String>();
        findings.forEach(finding -> rules.add(finding.substring(0, finding.indexOf(" @ "))));
        return rules;
    }

    /** What the statements wrote, in the same spelling. */
    private static Set<String> fromTheRows(DSLContext dsl) {
        var rows = new TreeSet<String>();
        dsl.select(LINT_VIOLATION.LINT_RULE, LINT_VIOLATION.SOURCE_LINE,
                LINT_VIOLATION.SOURCE_COLUMN)
            .from(LINT_VIOLATION)
            .where(LINT_VIOLATION.GRAPH_NAME.eq(GRAPH))
            .fetch()
            .forEach(row -> rows.add(row.value1() + " @ " + row.value2() + ":" + row.value3()));
        return rows;
    }


    private static SubjectConfig config(Path directory) {
        return SubjectConfig.of(new SchemaRecipe(directory.resolve("pom.xml"),
            List.of(SchemaRecipe.Binding.pattern("*.graphqls")), List.of("graphqls")));
    }

    private static String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("the example schema is read from the module's own"
                + " resources, so this runs from the module directory", e);
        }
    }

    private static void write(Path directory, String sdl) {
        try {
            Files.writeString(directory.resolve("schema.graphqls"), sdl, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Path temporaryDirectory() {
        try {
            return Files.createTempDirectory("lint-sakila-shadow");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
