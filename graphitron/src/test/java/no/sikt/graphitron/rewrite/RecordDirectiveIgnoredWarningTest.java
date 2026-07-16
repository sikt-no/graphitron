package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.rewrite.model.GraphitronType;
import no.sikt.graphitron.rewrite.model.Rejection;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Dedicated classifier-level coverage for the {@code @record}-ignored deprecation warning.
 *
 * <p>{@code @record} is a deprecated, legal-but-ignored directive: it drives no binding (reflection
 * does), and a reachable type still carrying it earns a build warning telling the author to remove
 * it. R307 folds that warning into the classification pass (see
 * {@code TypeBuilder.emitDirectiveIgnoredWarning}); this test pins the three message variants, the
 * multi-producer-rejection suppression, and the reachability gate directly at the classifier, so
 * the signal no longer needs a compilation/execution fixture to verify.
 *
 * <p>These are the only applied {@code @record} occurrences left in the generator test tree
 * (binding-hint decoration elsewhere drops the inert directive; see R307). The {@code @record}
 * declaration and {@code readRecordClassName} stay, so the schemas keep parsing.
 */
@PipelineTier
class RecordDirectiveIgnoredWarningTest {

    @Test
    void matches_directiveClassNameEqualsReflectedClass_warnsRedundant() {
        var schema = TestSchemaHelper.buildSchema("""
            type FilmDetails @record(record: {className: "no.sikt.graphitron.rewrite.test.jooq.tables.records.FilmRecord"}) {
                title: String
            }
            type Query {
                film: FilmDetails
                    @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "getFilm"})
            }
            """);

        assertThat(schema.warnings())
            .extracting(BuildWarning::message)
            .anyMatch(m -> m.contains("FilmDetails")
                && m.contains("The directive is redundant; remove it"));
    }

    @Test
    void disagrees_directiveClassNameDiffersFromReflectedClass_warnsDisagrees() {
        var schema = TestSchemaHelper.buildSchema("""
            type FilmDetails @record(record: {className: "no.sikt.graphitron.rewrite.test.jooq.tables.records.FilmRecord"}) {
                title: String
            }
            type Query {
                language: FilmDetails
                    @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "getLanguage"})
            }
            """);

        assertThat(schema.warnings())
            .extracting(BuildWarning::message)
            .anyMatch(m -> m.contains("FilmDetails")
                && m.contains("derives a different backing class")
                && m.contains("LanguageRecord"));
    }

    @Test
    void shadowedByTable_inputWithBothDirectives_warnsShadowed() {
        var schema = TestSchemaHelper.buildSchema("""
            input FilmInput
                @table(name: "film")
                @record(record: {className: "no.sikt.graphitron.rewrite.test.jooq.tables.records.FilmRecord"})
            { id: ID }
            type Query { x: String }
            """);

        assertThat(schema.warnings())
            .extracting(BuildWarning::message)
            .anyMatch(m -> m.contains("FilmInput")
                && m.contains("carries both @table and")
                && m.contains("@record")
                && m.contains("the @record directive is ignored"));
    }

    @Test
    void tableObjectWithRecord_recordIgnored_staysTableBackedNotConflict() {
        // D1 precedence: @record co-located with @table is not a DirectiveConflict; @table wins and
        // @record is ignored, so the type classifies table-backed rather than demoting to
        // UnclassifiedType. (The reachable-input variant, which also fires the shadowed warning, is
        // above; this object carrier pins only the no-conflict classification. Reached via Query.c so
        // the R279 field-first walk classifies it; an unreachable type is pruned, not classified.)
        var schema = TestSchemaHelper.buildSchema("""
            type Query { c: Conflicted }
            type Conflicted @table(name: "film") @record(record: {className: "no.sikt.graphitron.rewrite.TestDtoStub"}) {
                title: String
            }
            """);

        assertThat(schema.type("Conflicted")).isNotInstanceOf(GraphitronType.UnclassifiedType.class);
    }

    @Test
    void multiProducerRejection_suppressesWarning() {
        // Two @service producers reach FilmDetails returning different records: the producer-agreement
        // check surfaces RecordBindingMultiProducer and the typed error supersedes the directive-ignored
        // warning at the same site.
        var schema = TestSchemaHelper.buildSchema("""
            type FilmDetails @record(record: {className: "no.sikt.graphitron.rewrite.test.jooq.tables.records.FilmRecord"}) {
                title: String
            }
            type Query {
                viaFilm: FilmDetails
                    @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "getFilm"})
                viaLanguage: FilmDetails
                    @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "getLanguage"})
            }
            """);

        var t = schema.type("FilmDetails");
        assertThat(t).isInstanceOf(GraphitronType.UnclassifiedType.class);
        assertThat(((GraphitronType.UnclassifiedType) t).rejection())
            .isInstanceOf(Rejection.AuthorError.RecordBindingMultiProducer.class);

        assertThat(schema.warnings())
            .extracting(BuildWarning::message)
            .noneMatch(m -> m.contains("FilmDetails") && m.contains("the @record directive is ignored")
                || m.contains("FilmDetails") && m.contains("The directive is redundant"));
    }

    @Test
    void recordOnErrorType_silentlyIgnored_staysErrorTypeWithNoWarning() {
        // D1 precedence: @record co-located with @error is not a conflict; @error wins and @record is
        // silently ignored. The type stays ErrorType and earns no directive-ignored warning (the
        // @error type has no reflected record binding, so the warning's reachability gate never fires).
        var schema = TestSchemaHelper.buildSchema("""
            type RecordIgnoredError
                @error(handlers: [{handler: VALIDATION}])
                @record(record: {className: "java.lang.Object"}) {
                path: [String!]!
                message: String!
            }
            type Query { err: RecordIgnoredError }
            """);

        assertThat(schema.type("RecordIgnoredError")).isInstanceOf(GraphitronType.ErrorType.class);
        assertThat(schema.warnings())
            .extracting(BuildWarning::message)
            .noneMatch(m -> m.contains("RecordIgnoredError"));
    }

    @Test
    void unreachableRecordType_noWarningAndNoBinding() {
        // The warning is reachability-gated, mirroring the generator: a type carrying @record that no
        // producer reaches has no backing class to bind to (@record drives no binding) and is left
        // unclassified, and earns no directive-ignored warning.
        var schema = TestSchemaHelper.buildSchema("""
            type FilmDetails @record(record: {className: "no.sikt.graphitron.codereferences.dummyreferences.DummyRecord"}) {
                title: String
            }
            type Query { x: String }
            """);

        assertThat(schema.type("FilmDetails")).isNull();
        assertThat(schema.warnings())
            .extracting(BuildWarning::message)
            .noneMatch(m -> m.contains("FilmDetails"));
    }
}
