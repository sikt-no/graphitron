package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.model.lint.LintFix;
import no.sikt.graphitron.model.lint.LintRule;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import no.sikt.graphitron.model.diagnostics.BuildWarning;

/**
 * Lint-engine fix slice, classifier arm: the classifier-owned advisories whose ignored directive can be
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
            type FilmDetails @record(record: {className: "no.sikt.graphitron.rewrite.test.jooq.public_.tables.records.FilmRecord"}) {
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

    /**
     * The discriminated interface child's sibling advisory, at both cardinalities: the arm's
     * delivery is decided by cardinality alone, so {@code @splitQuery} names nothing it does not
     * already do and is reported redundant with the same computable deletion fix. The list
     * coordinate batches and the single one does not, and both warn: the directive's own
     * semantics (stop projecting into the parent's statement) is satisfied before it is applied
     * either way, the re-projection always being its own statement.
     */
    @Test
    void splitQueryOnDiscriminatedInterfaceChild_carriesDeletionFixAtBothCardinalities() {
        var schema = TestSchemaHelper.buildSchema("""
            interface Content @table(name: "content") @discriminate(on: "CONTENT_TYPE") {
              title: String @field(name: "TITLE")
            }
            type FilmContent implements Content @table(name: "content") @discriminator(value: "FILM") {
              title: String @field(name: "TITLE")
            }
            type Film @table(name: "film") {
              splitContents: [Content!]! @splitQuery @reference(path: [{key: "content_film_id_fkey"}])
              splitContent: Content @splitQuery @reference(path: [{key: "content_film_id_fkey"}])
            }
            type Query { films: [Film!]! }
            """);

        for (var coordinate : java.util.List.of("Film.splitContents", "Film.splitContent")) {
            var fix = fixFor(schema,
                LintRule.SPLITQUERY_REDUNDANT_ON_DISCRIMINATED_INTERFACE_CHILD, coordinate);
            assertThat(fix)
                .as("redundant @splitQuery on %s carries a deletion fix", coordinate)
                .isPresent();
            assertThat(fix.get().edits()).singleElement().satisfies(e -> {
                assertThat(e.replacement()).isEmpty();
                assertThat(e.end().getColumn() - e.start().getColumn())
                    .as("the deleted span is exactly '@splitQuery'").isEqualTo(11);
            });
        }
    }

    private static final String FILM_SERVICE = "no.sikt.graphitron.rewrite.generators.TestFilmService";

    /**
     * The service child's sibling advisory, on a {@code @table} parent: a child {@code @service}
     * is always called once per batch of parents, so {@code @splitQuery} beside it is inert, and
     * it is reported with the same computable deletion fix. The field still classifies to its
     * service variant; the directive changes nothing about it.
     */
    @Test
    void splitQueryBesideServiceOnTableParent_carriesDeletionFix() {
        var schema = TestSchemaHelper.buildSchema("""
            type Film @table(name: "film") {
              rating: String @splitQuery
                  @service(service: {className: "%s", method: "getRatingByFilmRecord"})
            }
            type Query { films: [Film!]! }
            """.formatted(FILM_SERVICE));

        assertThat(schema.warnings())
            .filteredOn(w -> w instanceof BuildWarning.LintFinding lf
                && lf.rule() == LintRule.SPLITQUERY_REDUNDANT_ON_SERVICE_CHILD
                && lf.message().contains("Film.rating"))
            .as("one redundancy finding at the coordinate")
            .hasSize(1);
        var fix = fixFor(schema, LintRule.SPLITQUERY_REDUNDANT_ON_SERVICE_CHILD, "Film.rating");
        assertThat(fix).as("redundant @splitQuery carries a deletion fix").isPresent();
        assertThat(fix.get().edits()).singleElement().satisfies(e -> {
            assertThat(e.replacement()).isEmpty();
            assertThat(e.end().getLine()).isEqualTo(e.start().getLine());
            assertThat(e.end().getColumn() - e.start().getColumn())
                .as("the deleted span is exactly '@splitQuery'").isEqualTo(11);
        });
        assertThat(schema.field("Film", "rating"))
            .as("the field still classifies to its service variant")
            .isInstanceOf(no.sikt.graphitron.rewrite.model.ChildField.ServiceRecordField.class);
    }

    /**
     * The warning is emitted ahead of the service resolver, so a method the resolver rejects (here
     * one with no {@code Sources} parameter) still draws it beside the rejection.
     */
    @Test
    void splitQueryBesideRejectedServiceOnTableParent_stillWarns() {
        var schema = TestSchemaHelper.buildSchema("""
            type Film @table(name: "film") {
              rank(filter: String): Int @splitQuery
                  @service(service: {className: "%s", method: "getConstantRank"})
            }
            type Query { films: [Film!]! }
            """.formatted(FILM_SERVICE));

        assertThat(schema.field("Film", "rank"))
            .as("the Sources-less method is rejected whatever @splitQuery says")
            .isInstanceOf(no.sikt.graphitron.rewrite.model.GraphitronField.UnclassifiedField.class);
        assertThat(fixFor(schema, LintRule.SPLITQUERY_REDUNDANT_ON_SERVICE_CHILD, "Film.rank"))
            .as("the rejection does not suppress the redundancy finding")
            .isPresent();
    }

    /** The negative: a child {@code @service} without {@code @splitQuery} draws no redundancy finding. */
    @Test
    void serviceChildWithoutSplitQuery_drawsNoRedundancyFinding() {
        var schema = TestSchemaHelper.buildSchema("""
            type Film @table(name: "film") {
              rating: String @service(service: {className: "%s", method: "getRatingByFilmRecord"})
            }
            type Query { films: [Film!]! }
            """.formatted(FILM_SERVICE));

        assertThat(schema.warnings())
            .filteredOn(w -> w instanceof BuildWarning.LintFinding lf
                && (lf.rule() == LintRule.SPLITQUERY_REDUNDANT_ON_SERVICE_CHILD
                    || lf.rule() == LintRule.SPLITQUERY_REDUNDANT_ON_RECORD_PARENT))
            .isEmpty();
    }
}
