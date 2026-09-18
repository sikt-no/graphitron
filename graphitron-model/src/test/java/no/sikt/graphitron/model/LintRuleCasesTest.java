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
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

import static no.sikt.graphitron.model.Tables.LINT_VIOLATION;
import static no.sikt.graphitron.model.test.SeededStore.seedGraph;
import static no.sikt.graphitron.model.test.SeededStore.withSeededStore;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Each rule against a corpus that breaks it and one that does not.
 *
 * <p>These are what the rules are pinned by. Until now they were pinned by the walk's own cases and
 * by two shadows comparing the walk against the statements, and both of those go when the walk does:
 * a shadow says two implementations agree and says nothing about whether either is right, so a tree
 * holding only shadows is one where nine rules could drift together and stay green. The per-rule
 * cases the statements once had went with the writer they were named for, their assertions about the
 * rules leaving with the class rather than moving to the view that replaced it.
 *
 * <p>Asserted as the subjects each rule draws rather than as line numbers, because a corpus gains a
 * line whenever somebody adds a case and renumbering every expectation is how a suite stops being
 * edited. The position is asserted separately and structurally, which is the stronger claim anyway:
 * the line a finding names has to be a line the offending name is written on, so a rule pointing an
 * author at the wrong line fails without anybody having counted lines.
 *
 * <p>Both corpora are captured into one store as two graphs. A store per case is a second of boot
 * each and the budget is shared with every other case in this module.
 */
class LintRuleCasesTest {

    private static final String BREAKS = "lint-cases-breaks";
    private static final String CLEAN = "lint-cases-clean";

    /** Something for every rule, which the shadow beside the statements holds to reaching all nine. */
    private static final String BREAKING = """
        "A widget."
        type Widget {
          "Its name."
          widgetName: String
          widgetland: String
          bare: String
          Colour: String
          kind: Kind
          arg(Locale: String, fine: String): String
          old: String @deprecated
          older: String @deprecated(reason: "use name")
        }

        interface named { name: String }

        union anything = Widget

        enum Kind { first SECOND @index(name: "i") }

        input WidgetFilter { Prefix: String, suffix: String }

        input WidgetSearchInput { term: String }

        scalar dateTime

        type Query {
          widgets: String
          documented: String
        }
        """;

    /**
     * The same shapes written correctly. Every declaration is described, every name is in the shape
     * its grain asks for, the input object's name ends in Input, no field repeats its type's name,
     * and the one deprecation carries a reason.
     */
    private static final String CLEAN_SDL = """
        "A widget."
        type Widget {
          "Its name."
          name: String
          "Its colour."
          colour: String
          "An argument."
          arg("A locale." locale: String): String
          "Retired."
          old: String @deprecated(reason: "use name")
        }

        "Something named."
        interface Named {
          "Its name."
          name: String
        }

        "Anything at all."
        union Anything = Widget

        "A kind of widget."
        enum Kind { FIRST SECOND }

        "A filter."
        input WidgetFilterInput {
          "The prefix."
          prefix: String
        }

        "A date and time."
        scalar DateTime

        "The query root."
        type Query {
          "Every widget."
          widgets: String
        }
        """;

    @Test
    @DisplayName("each rule draws exactly the subjects its corpus offends it with")
    void eachRuleDrawsItsOwnSubjects() {
        withBothCorpora(dsl -> {
            var drawn = subjectsByRule(dsl, BREAKS);
            assertThat(drawn).as("the rules this corpus reaches").containsOnlyKeys(EXPECTED.keySet());
            EXPECTED.forEach((rule, subjects) -> assertThat(drawn.get(rule))
                .as("subjects of %s", rule)
                .containsExactlyInAnyOrderElementsOf(subjects));
        });
    }

    @Test
    @DisplayName("a corpus that offends nothing draws nothing")
    void theCleanCorpusIsSilent() {
        withBothCorpora(dsl -> assertThat(subjectsByRule(dsl, CLEAN))
            .as("findings against a corpus written the way every rule asks")
            .isEmpty());
    }

    /**
     * The position is the whole point of keying on the entry rather than the coordinate, so it is
     * asserted rather than assumed: where the row says the position names the subject, the subject
     * has to be written on that line. A rule pointing an author at a line the name is not on fails
     * here without any expectation having counted lines.
     */
    @Test
    @DisplayName("a finding names a line the offending name is written on")
    void everyPositionPointsAtItsSubject() {
        withBothCorpora(dsl -> {
            var lines = BREAKING.lines().toList();
            var wrong = new TreeSet<String>();
            dsl.select(LINT_VIOLATION.LINT_RULE, LINT_VIOLATION.SUBJECT,
                    LINT_VIOLATION.SOURCE_LINE)
                .from(LINT_VIOLATION)
                .where(LINT_VIOLATION.GRAPH_NAME.eq(BREAKS))
                .and(LINT_VIOLATION.SUBJECT_AT_POSITION.isTrue())
                .fetch()
                .forEach(row -> {
                    String line = lines.get(row.value3() - 1);
                    if (!line.contains(row.value2())) {
                        wrong.add(row.value1() + " sent an author to line " + row.value3()
                            + " for '" + row.value2() + "', which reads: " + line.trim());
                    }
                });
            assertThat(wrong).as("findings whose line does not carry the name they are about")
                .isEmpty();
        });
    }

    /** Every rule, and the names in the breaking corpus it has something to say about. */
    private static final Map<String, List<String>> EXPECTED = new TreeMap<>(Map.of(
        "type-names-pascal-case", List.of("named", "anything", "dateTime"),
        "field-names-camel-case", List.of("Colour"),
        "input-and-argument-names-camel-case", List.of("Locale", "Prefix"),
        "enum-values-screaming-snake-case", List.of("first"),
        "input-object-name-suffix", List.of("WidgetFilter"),
        "no-typename-prefix", List.of("widgetName"),
        "types-and-fields-have-descriptions", List.of("named", "anything", "Kind", "WidgetFilter",
            "WidgetSearchInput", "dateTime", "Query", "widgets", "documented"),
        "deprecations-have-a-reason", List.of("deprecated"),
        "no-deprecated-directive-usage", List.of("index")));

    private static Map<String, List<String>> subjectsByRule(DSLContext dsl, String graph) {
        var byRule = new TreeMap<String, List<String>>();
        dsl.select(LINT_VIOLATION.LINT_RULE, LINT_VIOLATION.SUBJECT)
            .from(LINT_VIOLATION)
            .where(LINT_VIOLATION.GRAPH_NAME.eq(graph))
            .fetch()
            .forEach(row -> byRule.computeIfAbsent(row.value1(), r -> new java.util.ArrayList<>())
                .add(row.value2()));
        return byRule;
    }

    private static void withBothCorpora(java.util.function.Consumer<DSLContext> body) {
        withSeededStore(BREAKS, dsl -> {
            seedGraph(dsl, CLEAN);
            capture(dsl, BREAKS, BREAKING);
            capture(dsl, CLEAN, CLEAN_SDL);
            body.accept(dsl);
        });
    }

    private static void capture(DSLContext dsl, String graph, String sdl) {
        Path directory = temporaryDirectory();
        try {
            Files.writeString(directory.resolve("schema.graphqls"), sdl, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        var identity = new GraphIdentity(graph, directory);
        var config = SubjectConfig.of(new SchemaRecipe(directory.resolve("pom.xml"),
            List.of(SchemaRecipe.Binding.pattern("*.graphqls")), List.of("graphqls")));
        var readAt = LocalDateTime.now();
        var documents = GraphQLSourceCapture.capture(dsl, identity, config, readAt);
        GraphQLAstCapture.capture(dsl, identity, documents, readAt);
        GraphitronAstCapture.capture(dsl, identity, documents, readAt);
        GraphQLAssemblyCapture.capture(dsl, identity, documents, readAt);
    }

    private static Path temporaryDirectory() {
        try {
            return Files.createTempDirectory("lint-rule-cases");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
