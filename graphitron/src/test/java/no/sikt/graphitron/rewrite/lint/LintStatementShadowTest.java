package no.sikt.graphitron.rewrite.lint;

import graphql.schema.idl.SchemaParser;
import no.sikt.graphitron.model.capture.document.GraphQLAssemblyCapture;
import no.sikt.graphitron.model.capture.document.GraphQLAstCapture;
import no.sikt.graphitron.model.capture.document.GraphQLSourceCapture;
import no.sikt.graphitron.model.capture.document.GraphitronAstCapture;
import no.sikt.graphitron.model.diagnostics.BuildWarning;
import no.sikt.graphitron.model.lint.LintFindings;
import no.sikt.graphitron.model.read.StoreHandle;
import no.sikt.graphitron.model.run.GraphIdentity;
import no.sikt.graphitron.model.run.SubjectConfig;
import no.sikt.graphitron.model.schema.SchemaLoader;
import no.sikt.graphitron.model.schema.input.SchemaRecipe;
import no.sikt.graphitron.model.test.FactStores;
import no.sikt.graphitron.model.test.SeededStore;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The statements against the visitors they replace, over one corpus read both ways.
 *
 * <p>This is the evidence the conversion turns on, and the per-rule tests beside the statements are
 * not it: those say each statement does what its author believes the rule means, which is exactly
 * the belief a rewrite can get wrong. Here the walk and the rows answer the same corpus and the
 * answers are compared as sets of rule and position, so a statement that fires somewhere the
 * visitor does not, or misses somewhere it does, fails with the coordinate rather than with a
 * count.
 *
 * <p>The corpus declares no type extensions, deliberately. Extensions are the one place the two are
 * known to disagree and meant to: a name spelled at a base declaration and at two extensions is one
 * finding to the walk, which sees a composed type, and three rows here, one per line the author has
 * to edit. Mixing that into this comparison would turn a deliberate divergence into noise, so it is
 * pinned separately, beside the statements, and kept out of here.
 */
@PipelineTier
class LintStatementShadowTest {

    private static final String GRAPH = "lint-shadow";

    /**
     * Something for every rule, and for several of them more than one shape: names wrong at each
     * grain, a documented type beside bare ones, a field repeating its type and another that merely
     * starts with it, deprecations with and without a reason, and a retired directive from the
     * bundled vocabulary.
     */
    private static final String SDL = """
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
     * Every rule the statements implement, which the corpus above is written to break at least
     * once each. Held here rather than read off the findings, that being the thing under test: a
     * corpus that stopped reaching a rule would otherwise narrow this list along with itself.
     */
    private static final List<String> EVERY_RULE = List.of(
        "input-object-name-suffix",
        "type-names-pascal-case",
        "enum-values-screaming-snake-case",
        "input-and-argument-names-camel-case",
        "field-names-camel-case",
        "no-typename-prefix",
        "types-and-fields-have-descriptions",
        "deprecations-have-a-reason",
        "no-deprecated-directive-usage");

    @Test
    @DisplayName("the statements and the visitors find the same things in the same places")
    void theRowsAgreeWithTheWalk() {
        Path directory = temporaryDirectory();
        write(directory, SDL);
        try (var store = FactStores.inMemory()) {
            SeededStore.seedGraph(store.dsl(), GRAPH);
            // No writer to run: the rules are the view's arms, so capturing the
            // corpus is the whole of making the findings exist.
            var readAt = LocalDateTime.now();
            // No writer to run: the rules are the view's arms, so capturing the corpus is the
            // whole of making the findings exist.
            var graph = new GraphIdentity(GRAPH, directory);
            var config = config(directory);
            var documents = GraphQLSourceCapture.capture(store.dsl(), graph, config, readAt);
            GraphQLAstCapture.capture(store.dsl(), graph, documents, readAt);
            GraphitronAstCapture.capture(store.dsl(), graph, documents, readAt);
            GraphQLAssemblyCapture.capture(store.dsl(), graph, documents, readAt);

            var walked = fromTheWalk(store.dsl());
            var stored = fromTheStore(store.dsl());

            assertThat(rulesIn(walked))
                .as("the corpus still exercises every rule, without which two empty sets would"
                    + " agree and the comparison would report a parity it never made")
                .containsExactlyInAnyOrderElementsOf(EVERY_RULE);
            assertThat(stored)
                .as("every finding the walk reports, worded and fixed the way the walk words and"
                    + " fixes it, at the position it reports it, and nothing the walk does not")
                .containsExactlyInAnyOrderElementsOf(walked);
            // The comparison now covers fixes, and a corpus reaching none of them would compare
            // the empty string against the empty string on every row.
            assertThat(walked.stream().filter(f -> f.contains(" fix[")).count())
                .as("the corpus has to reach the fix-bearing rules, or the edits go uncompared")
                .isGreaterThanOrEqualTo(4L);
        }
    }

    /** The rules a set of findings names, each finding being {@code rule @ line:column}. */
    private static Set<String> rulesIn(Set<String> findings) {
        var rules = new TreeSet<String>();
        findings.forEach(finding -> rules.add(finding.substring(0, finding.indexOf(" @ "))));
        return rules;
    }

    /**
     * What the surviving engine reports, as rule and position.
     *
     * <p>The two texts are parsed as the two documents they are and merged, rather than
     * concatenated into one. Concatenating is the obvious shortcut and it silently shifts every
     * line of the corpus by the length of the bundled vocabulary, which turns a comparison of
     * positions into a comparison of offsets: the sets then differ everywhere, uniformly, and the
     * failure reads as though no rule agreed about anything.
     */
    private static Set<String> fromTheWalk(DSLContext dsl) {
        var registry = new SchemaParser().parse(SDL);
        registry.merge(new SchemaParser().parse(SchemaLoader.directivesSdl()));
        var findings = new TreeSet<String>();
        LintEngine.builtIn().run(registry, new StoreHandle(dsl, GRAPH)).stream()
            .map(BuildWarning.LintFinding.class::cast)
            .forEach(f -> findings.add(render(f)));
        return findings;
    }

    /**
     * A finding in full, which is what this has to compare and for a while did not.
     *
     * <p>Reducing a finding to its rule and position compares what the rules decide and nothing
     * about what an author is shown. That was the right reduction while the rows were the whole of
     * the new side: a row has no wording. It stops being right the moment something turns rows into
     * findings, because the wording and the edit are exactly what that something produces, and a
     * comparison blind to them would pass while every message came out wrong.
     *
     * <p>The fix is rendered as its own description and its edits' spans and replacements, so an
     * edit offered one character to the left of where the walk offers it fails here. Absence is
     * rendered too: a fix withheld on one side and offered on the other is the difference between
     * an editor showing an action and not, which is a difference a reader would notice before any
     * of us would.
     */
    private static String render(BuildWarning.LintFinding f) {
        var edits = new StringBuilder();
        f.fix().ifPresent(fix -> {
            edits.append(" fix[").append(fix.description()).append(']');
            fix.edits().forEach(e -> edits.append(" (")
                .append(e.start().getLine()).append(':').append(e.start().getColumn())
                .append('-')
                .append(e.end().getLine()).append(':').append(e.end().getColumn())
                .append(" -> ").append(e.replacement().replace("\n", "\\n")).append(')'));
        });
        return f.rule().id() + " @ " + f.location().getLine() + ":" + f.location().getColumn()
            + " | " + f.message() + edits;
    }

    /** What the rows become once something turns them into findings, in the same spelling. */
    private static Set<String> fromTheStore(DSLContext dsl) {
        var findings = new TreeSet<String>();
        LintFindings.of(new StoreHandle(dsl, GRAPH)).forEach(f -> findings.add(render(f)));
        return findings;
    }

    private static SubjectConfig config(Path directory) {
        return SubjectConfig.of(new SchemaRecipe(directory.resolve("pom.xml"),
            List.of(SchemaRecipe.Binding.pattern("*.graphqls")), List.of("graphqls")));
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
            return Files.createTempDirectory("lint-shadow");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
