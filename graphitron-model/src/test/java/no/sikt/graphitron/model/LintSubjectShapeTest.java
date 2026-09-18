package no.sikt.graphitron.model;

import no.sikt.graphitron.model.capture.document.GraphQLAssemblyCapture;
import no.sikt.graphitron.model.capture.document.GraphQLAstCapture;
import no.sikt.graphitron.model.capture.document.GraphQLSourceCapture;
import no.sikt.graphitron.model.capture.document.GraphitronAstCapture;
import no.sikt.graphitron.model.run.GraphIdentity;
import no.sikt.graphitron.model.run.SubjectConfig;
import no.sikt.graphitron.model.schema.input.SchemaRecipe;
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
import static no.sikt.graphitron.model.test.SeededStore.withSeededStore;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a finding is about, and whether that is enough for a reader to word it.
 *
 * <p>A rule does not determine its own wording. {@code types-and-fields-have-descriptions} reads one
 * way for a type and another for a root operation's field; {@code no-deprecated-directive-usage}
 * reads three ways, for a directive, an argument of one and a field of an input object. Nine rules
 * carry twelve templates between them, so a reader holding one template per rule would word three
 * findings wrongly and no test of the rules themselves would notice: the row is at the right
 * position, under the right rule, saying the wrong thing about it.
 *
 * <p>What makes the wording decidable is the pair. This pins that the pair is total, that it lands
 * inside the recorded domain, and that the second name is present exactly where a template asks for
 * one. The domain is written out rather than counted, because a reader will hold the same list and
 * the failure worth catching is an arm that starts producing a pair nobody wrote a template for.
 */
class LintSubjectShapeTest {

    private static final String GRAPH = "lint-subject-shape";

    /** Something for each arm that a single document can reach without the bundled vocabulary. */
    private static final String SDL = """
        type Widget {
          widgetName: String
          Colour: String
          "Its hue."
          Hue: String
          arg(Locale: String): String
          old: String @deprecated
        }

        enum Kind { first }

        input WidgetFilter { Prefix: String }

        type Query { widgets: String }
        """;

    /**
     * Every pair a reader must hold a template for. Eight rules word one way; the ninth is reached
     * here twice, as a type and as a root operation's field.
     */
    private static final Set<String> DOMAIN = Set.of(
        "input-object-name-suffix/NAMED_TYPE",
        "type-names-pascal-case/NAMED_TYPE",
        "enum-values-screaming-snake-case/ENUM_VALUE",
        "input-and-argument-names-camel-case/INPUT_FIELD",
        "input-and-argument-names-camel-case/FIELD_ARGUMENT",
        "field-names-camel-case/FIELD",
        "no-typename-prefix/FIELD",
        "types-and-fields-have-descriptions/NAMED_TYPE",
        "types-and-fields-have-descriptions/FIELD",
        "deprecations-have-a-reason/DIRECTIVE",
        "no-deprecated-directive-usage/DIRECTIVE",
        "no-deprecated-directive-usage/DIRECTIVE_ARGUMENT",
        "no-deprecated-directive-usage/INPUT_FIELD");

    /** The pairs whose template interpolates a second name, which is what the parent column is for. */
    private static final Set<String> TWO_NAMES = Set.of(
        "no-typename-prefix/FIELD",
        "types-and-fields-have-descriptions/FIELD",
        "no-deprecated-directive-usage/DIRECTIVE_ARGUMENT",
        "no-deprecated-directive-usage/INPUT_FIELD");

    @Test
    @DisplayName("every finding says what it is about, in a pair some template covers")
    void theSubjectIsTotalAndInsideTheDomain() {
        withCapture(dsl -> {
            assertThat(dsl.fetchCount(LINT_VIOLATION,
                LINT_VIOLATION.GRAPH_NAME.eq(GRAPH)
                    .and(LINT_VIOLATION.SUBJECT.isNull()
                        .or(LINT_VIOLATION.SUBJECT_KIND.isNull()))))
                .as("findings a reader could not word, having nothing to word them about")
                .isZero();
            assertThat(pairs(dsl))
                .as("pairs no template covers, which is an arm wording a finding by accident")
                .isSubsetOf(DOMAIN);
            assertThat(pairs(dsl))
                .as("the corpus has to reach several pairs, or being inside the domain is vacuous")
                .hasSizeGreaterThanOrEqualTo(7);
        });
    }

    /**
     * The second name is an attribute of the pair and not of the row, so a rule that carries one
     * carries it at every occurrence. A pair that sometimes has one is a template that would render
     * the word null into a build warning.
     */
    @Test
    @DisplayName("the second name is present exactly where a template asks for one")
    void theParentFollowsTheTemplate() {
        withCapture(dsl -> {
            var withParent = new TreeSet<String>();
            var withoutParent = new TreeSet<String>();
            dsl.select(LINT_VIOLATION.LINT_RULE, LINT_VIOLATION.SUBJECT_KIND,
                    LINT_VIOLATION.SUBJECT_PARENT)
                .from(LINT_VIOLATION)
                .where(LINT_VIOLATION.GRAPH_NAME.eq(GRAPH))
                .fetch()
                .forEach(row -> (row.value3() == null ? withoutParent : withParent)
                    .add(row.value1() + "/" + row.value2()));

            assertThat(withParent)
                .as("pairs carrying a second name that no template interpolates")
                .isSubsetOf(TWO_NAMES);
            // Both halves above are satisfied by a corpus that reaches no two-name pair at all, so
            // the two this one does reach are named. The other two need the bundled vocabulary's
            // retired directives and are covered where a corpus has them.
            assertThat(withParent)
                .as("the corpus has to reach a two-name pair, or the split above asserts nothing")
                .contains("no-typename-prefix/FIELD",
                    "types-and-fields-have-descriptions/FIELD");
            assertThat(withoutParent)
                .as("pairs whose template interpolates a second name and whose row has none")
                .doesNotContainAnyElementsOf(TWO_NAMES);
        });
    }

    /**
     * A described declaration is reported at its description, so the characters at the position are
     * the author's prose. Both halves are asserted over one corpus and one rule, the two fields
     * differing in nothing but whether somebody documented them: a flag that is always true passes
     * every fix through, and one that is always false silently retires the rename fixes altogether.
     */
    @Test
    @DisplayName("the position names the subject only where no description displaced it")
    void theFlagFollowsTheDescription() {
        withCapture(dsl -> {
            assertThat(atPosition(dsl, "Colour"))
                .as("an undescribed field is reported at its own name token")
                .containsExactly(true);
            assertThat(atPosition(dsl, "Hue"))
                .as("a described field is reported at the description instead")
                .containsExactly(false);
        });
    }

    /** What the rows for one offending name say about their own position. */
    private static List<Boolean> atPosition(DSLContext dsl, String subject) {
        return dsl.select(LINT_VIOLATION.SUBJECT_AT_POSITION)
            .from(LINT_VIOLATION)
            .where(LINT_VIOLATION.GRAPH_NAME.eq(GRAPH))
            .and(LINT_VIOLATION.LINT_RULE.eq("field-names-camel-case"))
            .and(LINT_VIOLATION.SUBJECT.eq(subject))
            .fetch(LINT_VIOLATION.SUBJECT_AT_POSITION);
    }

    private static Set<String> pairs(DSLContext dsl) {
        var found = new TreeSet<String>();
        dsl.select(LINT_VIOLATION.LINT_RULE, LINT_VIOLATION.SUBJECT_KIND)
            .from(LINT_VIOLATION)
            .where(LINT_VIOLATION.GRAPH_NAME.eq(GRAPH))
            .fetch()
            .forEach(row -> found.add(row.value1() + "/" + row.value2()));
        return found;
    }

    private static void withCapture(java.util.function.Consumer<DSLContext> body) {
        withSeededStore(GRAPH, dsl -> {
            Path directory = temporaryDirectory();
            try {
                Files.writeString(directory.resolve("schema.graphqls"), SDL, StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            var graph = new GraphIdentity(GRAPH, directory);
            var config = SubjectConfig.of(new SchemaRecipe(directory.resolve("pom.xml"),
                List.of(SchemaRecipe.Binding.pattern("*.graphqls")), List.of("graphqls")));
            var readAt = LocalDateTime.now();
            var documents = GraphQLSourceCapture.capture(dsl, graph, config, readAt);
            GraphQLAstCapture.capture(dsl, graph, documents, readAt);
            GraphitronAstCapture.capture(dsl, graph, documents, readAt);
            GraphQLAssemblyCapture.capture(dsl, graph, documents, readAt);
            body.accept(dsl);
        });
    }

    private static Path temporaryDirectory() {
        try {
            return Files.createTempDirectory("lint-subject-shape");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
