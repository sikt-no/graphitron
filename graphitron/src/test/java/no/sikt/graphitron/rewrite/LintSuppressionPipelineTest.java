package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.rewrite.lint.LintConfig;
import no.sikt.graphitron.rewrite.lint.LintRule;
import no.sikt.graphitron.rewrite.schema.input.SchemaInput;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static no.sikt.graphitron.common.configuration.TestConfiguration.DEFAULT_JOOQ_PACKAGE;
import static no.sikt.graphitron.common.configuration.TestConfiguration.DEFAULT_OUTPUT_PACKAGE;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * R408: primary (pipeline-tier) coverage for lint-finding suppression. Drives the real
 * {@link GraphQLRewriteGenerator#buildOutput()} against the test jOOQ catalog and asserts on the
 * {@link ValidationReport} it produces, the same object the LSP replays and the MCP {@code diagnostics}
 * tool projects, so suppression is verified where it is applied (the single build evaluator) rather
 * than at a downstream filter.
 *
 * <p>Assertions are keyed on the typed {@link LintRule}, with a message substring only to disambiguate
 * <em>which</em> node a finding is about (never as the rule's identity), per the design principles.
 */
@PipelineTier
class LintSuppressionPipelineTest {

    private static ValidationReport report(Path schema, String sdl, LintConfig lintConfig) throws IOException {
        Files.writeString(schema, sdl);
        var ctx = new RewriteContext(
            List.of(new SchemaInput(schema.toString(), Optional.empty(), Optional.empty())),
            schema.getParent(), schema.getParent(), DEFAULT_OUTPUT_PACKAGE, DEFAULT_JOOQ_PACKAGE, Map.of()
        ).withLintConfig(lintConfig);
        return new GraphQLRewriteGenerator(ctx).buildOutput().report();
    }

    private static List<BuildWarning.LintFinding> lintFindings(ValidationReport report) {
        return report.warnings().stream()
            .filter(BuildWarning.LintFinding.class::isInstance)
            .map(BuildWarning.LintFinding.class::cast)
            .toList();
    }

    private static boolean hasRuleForNode(ValidationReport report, LintRule rule, String nodeMarker) {
        return lintFindings(report).stream()
            .anyMatch(f -> f.rule() == rule && f.message().contains(nodeMarker));
    }

    @Test
    void disabledRule_dropsThatRuleWhileOtherRulesStillFire(@TempDir Path tmp) throws IOException {
        // Film.original_language_id (snake_case, maps to the real column) trips field-names-camel-case;
        // the Film / Query types (no descriptions) trip types-and-fields-have-descriptions.
        String sdl = """
            type Film @table(name: "film") {
              original_language_id: Int
            }
            type Query { film: Film }
            """;

        var enabled = report(tmp.resolve("a.graphqls"), sdl, LintConfig.empty());
        assertThat(hasRuleForNode(enabled, LintRule.FIELD_NAMES_CAMEL_CASE, "original_language_id"))
            .as("control: the rule fires when nothing is disabled").isTrue();

        var disabled = report(tmp.resolve("b.graphqls"), sdl,
            LintConfig.validated(Set.of("field-names-camel-case"), List.of()));
        assertThat(lintFindings(disabled))
            .as("the disabled rule no longer appears in the report")
            .noneMatch(f -> f.rule() == LintRule.FIELD_NAMES_CAMEL_CASE);
        assertThat(lintFindings(disabled))
            .as("other rules still fire")
            .anyMatch(f -> f.rule() == LintRule.TYPES_AND_FIELDS_HAVE_DESCRIPTIONS);
    }

    @Test
    void excludedTypes_skipsMatchingTypeButNotItsSiblings(@TempDir Path tmp) throws IOException {
        // Two table-backed types with snake_case fields; only Film is excluded.
        String sdl = """
            type Film @table(name: "film") {
              original_language_id: Int
            }
            type Actor @table(name: "actor") {
              first_name: String
            }
            type Query { film: Film actor: Actor }
            """;

        var report = report(tmp.resolve("c.graphqls"), sdl,
            LintConfig.validated(Set.of(), List.of("Film")));

        assertThat(hasRuleForNode(report, LintRule.FIELD_NAMES_CAMEL_CASE, "original_language_id"))
            .as("the excluded type produces no engine finding").isFalse();
        assertThat(hasRuleForNode(report, LintRule.FIELD_NAMES_CAMEL_CASE, "first_name"))
            .as("a non-matching sibling type still does").isTrue();
    }

    @Test
    void excludedTypes_globMatchesByPattern(@TempDir Path tmp) throws IOException {
        String sdl = """
            type Film @table(name: "film") {
              original_language_id: Int
            }
            type Query { film: Film }
            """;

        var report = report(tmp.resolve("d.graphqls"), sdl,
            LintConfig.validated(Set.of(), List.of("Fi*m")));
        assertThat(hasRuleForNode(report, LintRule.FIELD_NAMES_CAMEL_CASE, "original_language_id"))
            .as("a glob pattern excludes the matching type").isFalse();
    }

    @Test
    void disabledRule_suppressesClassifierAdvisoryToo(@TempDir Path tmp) throws IOException {
        // FilmDetails binds to the @service producer's reflected record; @splitQuery on a record-backed
        // parent is a Source.CLASSIFIER advisory (splitquery-redundant-on-record-parent), emitted on
        // schema.warnings() rather than by the engine's AST walk.
        String sdl = """
            type Language @table(name: "language") { name: String }
            type FilmDetails {
              language: Language @splitQuery @reference(path: [{key: "film_language_id_fkey"}])
            }
            type Query {
              details: FilmDetails
                @service(service: {className: "no.sikt.graphitron.codereferences.dummyreferences.DummyService", method: "makeDummyRecord"})
            }
            """;

        var enabled = report(tmp.resolve("e.graphqls"), sdl, LintConfig.empty());
        assertThat(hasRuleForNode(enabled, LintRule.SPLITQUERY_REDUNDANT_ON_RECORD_PARENT, "FilmDetails.language"))
            .as("control: the classifier advisory fires").isTrue();

        var disabled = report(tmp.resolve("f.graphqls"), sdl,
            LintConfig.validated(Set.of("splitquery-redundant-on-record-parent"), List.of()));
        assertThat(lintFindings(disabled))
            .as("disabling a Source.CLASSIFIER rule id suppresses its advisory, so the filter sits on "
                + "the combined BuildWarning channel and not inside the engine's AST walk")
            .noneMatch(f -> f.rule() == LintRule.SPLITQUERY_REDUNDANT_ON_RECORD_PARENT);
    }

    @Test
    void excludedTypes_isEngineScoped_classifierAdvisoryOnExcludedTypeStillFires(@TempDir Path tmp) throws IOException {
        // FilmDetails trips both an engine rule (types-and-fields-have-descriptions on the type) and a
        // classifier advisory (splitquery-redundant on FilmDetails.language). Excluding the type must
        // silence the engine finding while leaving the classifier advisory, pinning that excludedTypes
        // reaches only the engine's AST walk (R408). A future refactor extending it to the classifier
        // channel would need the fragile owning-type reverse-map option C rejects.
        String sdl = """
            type Language @table(name: "language") { name: String }
            type FilmDetails {
              language: Language @splitQuery @reference(path: [{key: "film_language_id_fkey"}])
            }
            type Query {
              details: FilmDetails
                @service(service: {className: "no.sikt.graphitron.codereferences.dummyreferences.DummyService", method: "makeDummyRecord"})
            }
            """;

        var report = report(tmp.resolve("g.graphqls"), sdl,
            LintConfig.validated(Set.of(), List.of("FilmDetails")));

        assertThat(hasRuleForNode(report, LintRule.TYPES_AND_FIELDS_HAVE_DESCRIPTIONS, "FilmDetails"))
            .as("the engine finding on the excluded type is gone").isFalse();
        assertThat(hasRuleForNode(report, LintRule.SPLITQUERY_REDUNDANT_ON_RECORD_PARENT, "FilmDetails.language"))
            .as("the classifier advisory on the same excluded type still fires").isTrue();
    }
}
