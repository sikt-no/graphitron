package no.sikt.graphitron.model.lint;

import graphql.language.ObjectTypeDefinition;
import graphql.language.SourceLocation;
import graphql.parser.InvalidSyntaxException;
import graphql.parser.Parser;
import no.sikt.graphitron.model.capture.document.GraphQLAssemblyCapture;
import no.sikt.graphitron.model.capture.document.GraphQLAstCapture;
import no.sikt.graphitron.model.capture.document.GraphQLSourceCapture;
import no.sikt.graphitron.model.capture.document.GraphitronAstCapture;
import no.sikt.graphitron.model.diagnostics.BuildWarning;
import no.sikt.graphitron.model.read.StoreHandle;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.TreeSet;
import java.util.function.Consumer;

import static no.sikt.graphitron.model.test.SeededStore.withSeededStore;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Where the description placeholder may be written, and the one place it may not.
 *
 * <p>{@code types-and-fields-have-descriptions} draws a row for a declaration nobody documented and
 * for one documented with nothing, and those two are not the same edit. An absent description means
 * the position is the declaration's own, so a line inserted above it is a description. A blank one
 * means a description node exists and the position is that node, so the same insert writes a second
 * string in front of the first. The rule's own {@code subject_at_position} is what tells them apart,
 * and it was added carrying this case and not used by it.
 *
 * <p>The third case is why this withholds rather than offering something cleverer. It applies the
 * edit the rule would otherwise have offered and hands the result back to the parser, which states
 * the claim as the parser's verdict rather than as a sentence about grammar. Asserting only that no
 * fix is offered would pass just as well if somebody withheld it for the wrong reason, or withheld
 * every fix this rule has.
 */
class DescriptionPlaceholderTest {

    private static final String GRAPH = "description-placeholder";

    private static final String RULE = "types-and-fields-have-descriptions";

    /** The blank is written as a plain string of spaces, there being no way to spell one that is not. */
    private static final String SDL = """
        type Undescribed {
          id: ID
        }

        "   "
        type Blank {
          id: ID
        }

        "A described type."
        type Described {
          id: ID
        }

        type Query {
          widgets: String
        }
        """;

    /**
     * The declaration nobody documented. The insert goes above it, and what it leaves behind is a
     * document the parser accepts with the placeholder attached to the type the finding named.
     */
    @Test
    @DisplayName("an undocumented declaration is offered the placeholder, and applying it parses")
    void theAbsentDescriptionIsOfferedTheInsert() {
        withFindings(findings -> {
            var finding = findingAbout(findings, "Undescribed");
            assertThat(finding.location().getLine())
                .as("reported at the declaration, nothing having displaced it")
                .isEqualTo(lineOf("type Undescribed {"));

            var fix = finding.fix().orElseThrow(
                () -> new AssertionError("no fix offered where the description is absent"));
            assertThat(fix.edits()).hasSize(1);

            var rewritten = insert(finding.location(), fix.edits().getFirst().replacement());
            assertThat(Parser.parse(rewritten).getDefinitions())
                .as("the document still holds every declaration it started with")
                .hasSameSizeAs(Parser.parse(SDL).getDefinitions());
            assertThat(descriptionOf(rewritten, "Undescribed"))
                .as("the placeholder documents the declaration the finding was about")
                .contains("TODO: describe.");
        });
    }

    /**
     * The declaration documented with nothing. The finding stands, a description that says nothing
     * being a defect its author can fix, and it stands at the description because that is the line
     * they would go to. What does not stand is an edit offered on top of it.
     */
    @Test
    @DisplayName("a blank description draws the finding and no edit")
    void theBlankDescriptionIsOfferedNothing() {
        withFindings(findings -> {
            var finding = findingAbout(findings, "Blank");
            assertThat(finding.location().getLine())
                .as("reported at the description, which is the line holding the blank")
                .isEqualTo(lineOf("\"   \""));
            assertThat(finding.fix())
                .as("an edit here would be written on top of the description already there")
                .isEmpty();
        });
    }

    /**
     * What the withheld edit would have done, witnessed rather than asserted. The placeholder is
     * inserted at the position the finding carries, which is what a rule not consulting the flag
     * does, and the parser refuses the result.
     */
    @Test
    @DisplayName("the withheld edit would leave a document that no longer parses")
    void theWithheldEditBreaksTheDocument() {
        withFindings(findings -> {
            var at = findingAbout(findings, "Blank").location();
            String indent = " ".repeat(Math.max(0, at.getColumn() - 1));
            var rewritten = insert(at, "\"\"\"TODO: describe.\"\"\"" + "\n" + indent);

            assertThat(rewritten)
                .as("the two strings this case is about are both in front of the declaration")
                .contains("TODO: describe.")
                .contains("\"   \"");
            assertThatThrownBy(() -> Parser.parse(rewritten))
                .as("two descriptions before one declaration are not a document")
                .isInstanceOf(InvalidSyntaxException.class);
        });
    }

    /**
     * The rule's population, which the two cases above depend on and neither states: a blank
     * description is inside it and a written one is outside, so the withholding above is about which
     * edit to offer and not about which declarations the rule reaches.
     */
    @Test
    @DisplayName("a documented declaration draws no finding and a blank one does")
    void thePopulationIsTheUndocumented() {
        withFindings(findings -> assertThat(subjectsOfTheRule(findings))
            .as("the rule reaches the blank declaration and leaves the described one alone")
            .contains("Undescribed", "Blank")
            .doesNotContain("Described"));
    }

    /** The named types this rule drew a row about, as the wordings name them. */
    private static TreeSet<String> subjectsOfTheRule(List<BuildWarning.LintFinding> findings) {
        var named = new TreeSet<String>();
        for (var finding : findings) {
            if (!finding.rule().id().equals(RULE)) {
                continue;
            }
            for (String candidate : List.of("Undescribed", "Blank", "Described", "Query")) {
                if (finding.message().contains("'" + candidate + "'")) {
                    named.add(candidate);
                }
            }
        }
        return named;
    }

    private static BuildWarning.LintFinding findingAbout(
            List<BuildWarning.LintFinding> findings, String subject) {
        return findings.stream()
            .filter(f -> f.rule().id().equals(RULE))
            .filter(f -> f.message().contains("'" + subject + "'"))
            .findFirst()
            .orElseThrow(() -> new AssertionError("no finding about " + subject));
    }

    /** The text with one insertion applied, which is what an editor accepting the fix does. */
    private static String insert(SourceLocation at, String text) {
        var lines = new ArrayList<>(List.of(SDL.split("\n", -1)));
        String line = lines.get(at.getLine() - 1);
        int column = Math.min(at.getColumn() - 1, line.length());
        lines.set(at.getLine() - 1,
            line.substring(0, column) + text + line.substring(column));
        return String.join("\n", lines);
    }

    /** The one-based line a snippet is written on, so no expectation here counts lines by hand. */
    private static int lineOf(String snippet) {
        String[] lines = SDL.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].contains(snippet)) {
                return i + 1;
            }
        }
        throw new IllegalStateException("the fixture does not hold " + snippet);
    }

    /** The description the parser attaches to a named type in the rewritten text. */
    private static Optional<String> descriptionOf(String text, String typeName) {
        return Parser.parse(text).getDefinitions().stream()
            .filter(ObjectTypeDefinition.class::isInstance)
            .map(ObjectTypeDefinition.class::cast)
            .filter(d -> d.getName().equals(typeName))
            .findFirst()
            .map(d -> d.getDescription() == null ? null : d.getDescription().getContent());
    }

    private static void withFindings(Consumer<List<BuildWarning.LintFinding>> body) {
        withSeededStore(GRAPH, dsl -> {
            capture(dsl);
            body.accept(LintFindings.of(new StoreHandle(dsl, GRAPH)));
        });
    }

    /** One reading of a directory holding the fixture, which is what a run does. */
    private static void capture(DSLContext dsl) {
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
    }

    private static Path temporaryDirectory() {
        try {
            return Files.createTempDirectory("description-placeholder");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
