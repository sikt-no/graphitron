package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.rewrite.lint.LintFix;
import no.sikt.graphitron.rewrite.lint.LintRule;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Lint-engine fix slice, classifier arm: the two classifier-owned advisories whose ignored directive can be
 * safely deleted carry a {@link LintFix} directly at their emit site (the classifier stays their sole
 * producer; the lint engine does not re-derive them). Pins the produced deletion edit and the
 * bare-only guard: graphql-java records a node's start location but no end, so only the argument-less
 * {@code @record} / {@code @splitQuery} form has a computable span; the {@code @record(record: {...})}
 * form reports without a fix.
 */
@PipelineTier
class ClassifierAdvisoryFixPipelineTest {

    private static Optional<LintFix> fixFor(GraphitronSchema schema, LintRule rule, String messageNeedle) {
        return schema.warnings().stream()
            .filter(w -> w instanceof BuildWarning.LintFinding lf
                && lf.rule() == rule
                && lf.message().contains(messageNeedle))
            .map(w -> ((BuildWarning.LintFinding) w).fix())
            .flatMap(Optional::stream)
            .findFirst();
    }

    @Test
    void bareRedundantRecord_carriesDeletionFix() {
        var schema = TestSchemaHelper.buildSchema("""
            type FilmDetails @record {
                title: String
            }
            type Query {
                film: FilmDetails
                    @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "getFilm"})
            }
            """);

        // The bare @record matches the inferred backing class, so it is redundant; a bare directive
        // is exactly '@record' (7 characters), a computable deletion.
        var fix = fixFor(schema, LintRule.REDUNDANT_RECORD_DIRECTIVE, "FilmDetails");
        assertThat(fix).as("bare redundant @record carries a deletion fix").isPresent();
        assertThat(fix.get().edits()).singleElement().satisfies(e -> {
            assertThat(e.replacement()).as("a deletion replaces with nothing").isEmpty();
            assertThat(e.end().getLine()).isEqualTo(e.start().getLine());
            assertThat(e.end().getColumn() - e.start().getColumn())
                .as("the deleted span is exactly '@record'").isEqualTo(7);
        });
    }

    @Test
    void recordWithArguments_reportsWithoutFix() {
        var schema = TestSchemaHelper.buildSchema("""
            type FilmDetails @record(record: {className: "no.sikt.graphitron.rewrite.test.jooq.tables.records.FilmRecord"}) {
                title: String
            }
            type Query {
                film: FilmDetails
                    @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "getFilm"})
            }
            """);

        boolean findingPresent = schema.warnings().stream()
            .anyMatch(w -> w instanceof BuildWarning.LintFinding lf
                && lf.rule() == LintRule.REDUNDANT_RECORD_DIRECTIVE
                && lf.message().contains("FilmDetails"));
        assertThat(findingPresent).as("the redundant-@record advisory still fires").isTrue();
        assertThat(fixFor(schema, LintRule.REDUNDANT_RECORD_DIRECTIVE, "FilmDetails"))
            .as("@record with arguments has no computable end, so no deletion fix").isEmpty();
    }

    @Test
    void splitQueryOnRecordParent_carriesDeletionFix() {
        var schema = TestSchemaHelper.buildSchema("""
            type Language @table(name: "language") { name: String }
            type FilmDetails {
              language: Language @splitQuery @reference(path: [{key: "film_language_id_fkey"}])
            }
            type Film @table(name: "film") { details: FilmDetails }
            type Query {
                film: Film
                prodFilmDetails: FilmDetails @service(service: {className: "no.sikt.graphitron.codereferences.dummyreferences.DummyService", method: "makeDummyRecord"})
            }
            """);

        // @splitQuery is a marker directive (no arguments), so its span is always computable: exactly
        // '@splitQuery' (11 characters).
        var fix = fixFor(schema, LintRule.SPLITQUERY_REDUNDANT_ON_RECORD_PARENT, "FilmDetails.language");
        assertThat(fix).as("redundant @splitQuery carries a deletion fix").isPresent();
        assertThat(fix.get().edits()).singleElement().satisfies(e -> {
            assertThat(e.replacement()).isEmpty();
            assertThat(e.end().getLine()).isEqualTo(e.start().getLine());
            assertThat(e.end().getColumn() - e.start().getColumn())
                .as("the deleted span is exactly '@splitQuery'").isEqualTo(11);
        });
    }
}
