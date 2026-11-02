package no.sikt.graphitron.rewrite;

import com.palantir.javapoet.TypeSpec;
import no.sikt.graphitron.model.config.RunContext;
import no.sikt.graphitron.model.diagnostics.BuildWarning;
import no.sikt.graphitron.model.lint.LintConfig;
import no.sikt.graphitron.model.lint.LintRule;
import no.sikt.graphitron.model.run.GraphitronStore;
import no.sikt.graphitron.model.schema.input.SchemaInput;
import no.sikt.graphitron.model.schema.input.SchemaSource;
import no.sikt.graphitron.rewrite.generators.schema.ConstraintViolationsClassGenerator;
import no.sikt.graphitron.rewrite.model.MutationField;
import no.sikt.graphitron.rewrite.model.ServiceMethodCall;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static no.sikt.graphitron.common.configuration.TestConfiguration.DEFAULT_JOOQ_PACKAGE;
import static no.sikt.graphitron.common.configuration.TestConfiguration.DEFAULT_OUTPUT_PACKAGE;
import static no.sikt.graphitron.model.Tables.SQL_COLUMN_RECORD_ACCESSOR;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * A VALIDATION channel over a {@code @service} parameter bound to a jOOQ record: the record is a
 * validation target, the pre-step's {@code ConstraintViolations.Paths} constant is declared for it,
 * and a column the store found no getter for reaches the report as {@code validation-column-unchecked}.
 * Structural assertions only; the generated method body is pinned at the execution tier.
 */
@PipelineTier
class RecordValidationPipelineTest {

    private static final String CHANNEL = """
        type ValidationErr @error(handlers: [{handler: VALIDATION}]) { path: [String!]! message: String! }
        union SakError = ValidationErr
        type SakPayload { data: String errors: [SakError] }
        """;

    private static final String NO_VALIDATION_CHANNEL = """
        type DbErr @error(handlers: [{handler: DATABASE, sqlState: "23503"}]) { path: [String!]! message: String! }
        union SakError = DbErr
        type SakPayload { data: String errors: [SakError] }
        """;

    private static final String FIELDS = """
        type Film implements Node @table(name: "film") @node { id: ID! title: String }
        input FilmDraftInput {
            id: ID @nodeId(typeName: "Film")
            title: String @field(name: "title")
            description: String @field(name: "description")
        }
        input FilmKeyInput { id: ID! @nodeId(typeName: "Film") }
        type Query { x: String }
        type Mutation {
            draftFilms(in: [FilmDraftInput!]!): SakPayload
                @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "runSakWithFilmRecords"})
            keyFilm(in: FilmKeyInput!): SakPayload
                @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "runSakWithFilmRecord"})
        }
        """;

    @Test
    void aRecordParameterUnderAValidationChannelIsAWrittenColumnsTarget() {
        var schema = TestSchemaHelper.buildSchema(CHANNEL + FIELDS);
        var f = (MutationField.MutationServiceRecordField) schema.field("Mutation", "draftFilms");
        assertThat(f.serviceMethodCall().validationTargets())
            .singleElement()
            .satisfies(t -> {
                assertThat(t).isInstanceOf(ServiceMethodCall.ValidationTarget.PerElement.class);
                assertThat(t.subject()).isInstanceOf(ServiceMethodCall.ValidationSubject.WrittenColumns.class);
            });
    }

    @Test
    void aRecordBindingOnlyAKeyDecodeYieldsNoTarget() {
        var schema = TestSchemaHelper.buildSchema(CHANNEL + FIELDS);
        var f = (MutationField.MutationServiceRecordField) schema.field("Mutation", "keyFilm");
        assertThat(f.serviceMethodCall().validationTargets()).isEmpty();
    }

    @Test
    void theFetchersClassDeclaresAPathsConstantUnderTheChannel(@TempDir Path tmp) {
        assertThat(pathsFields(TestSchemaHelper.storeBackedFetchers(tmp, CHANNEL + FIELDS))).hasSize(1);
    }

    @Test
    void withoutTheChannelNoPathsConstantIsDeclared(@TempDir Path tmp) {
        assertThat(pathsFields(TestSchemaHelper.storeBackedFetchers(tmp, NO_VALIDATION_CHANNEL + FIELDS))).isEmpty();
    }

    @Test
    void everyColumnWithAGetterIsCheckedSoNothingIsReported(@TempDir Path tmp) throws IOException {
        assertThat(unchecked(validateOnly(tmp, CHANNEL + FIELDS, LintConfig.empty(), false))).isEmpty();
    }

    @Test
    void aColumnWithNoGetterIsReportedFromAValidateOnlyRun(@TempDir Path tmp) throws IOException {
        assertThat(unchecked(validateOnly(tmp, CHANNEL + FIELDS, LintConfig.empty(), true)))
            .singleElement()
            .satisfies(w -> assertThat(w.message())
                .contains("Mutation.draftFilms", "'description'", "getDescription()"));
    }

    @Test
    void aColumnWithNoGetterIsReportedFromAGeneratingRun(@TempDir Path tmp) throws IOException {
        assertThat(unchecked(generating(tmp, CHANNEL + FIELDS, LintConfig.empty(), true))).hasSize(1);
    }

    @Test
    void withoutTheChannelNoColumnIsReported(@TempDir Path tmp) throws IOException {
        assertThat(unchecked(validateOnly(tmp, NO_VALIDATION_CHANNEL + FIELDS, LintConfig.empty(), true))).isEmpty();
    }

    @Test
    void theRuleIdSuppressesTheFinding(@TempDir Path tmp) throws IOException {
        assertThat(unchecked(validateOnly(tmp, CHANNEL + FIELDS,
            LintConfig.validated(Set.of("validation-column-unchecked"), List.of()), true))).isEmpty();
    }

    // ===== Helpers =====

    private static List<?> pathsFields(List<TypeSpec> fetchers) {
        var paths = ConstraintViolationsClassGenerator.pathsClass(DEFAULT_OUTPUT_PACKAGE);
        return fetchers.stream()
            .filter(t -> t.name().equals("MutationFetchers"))
            .flatMap(t -> t.fieldSpecs().stream())
            .filter(f -> f.type().equals(paths))
            .toList();
    }

    private static List<BuildWarning.LintFinding> unchecked(List<BuildWarning> warnings) {
        return warnings.stream()
            .filter(BuildWarning.LintFinding.class::isInstance)
            .map(BuildWarning.LintFinding.class::cast)
            .filter(f -> f.rule() == LintRule.VALIDATION_COLUMN_UNCHECKED)
            .toList();
    }

    private static List<BuildWarning> validateOnly(Path tmp, String sdl, LintConfig lint, boolean dropGetter)
            throws IOException {
        var run = context(tmp, sdl, lint);
        try (var opened = GraphitronStore.captured(run)) {
            if (dropGetter) {
                dropDescriptionGetter(opened);
            }
            return new GraphQLRewriteGenerator(run, opened).buildOutput().warnings();
        }
    }

    private static List<BuildWarning> generating(Path tmp, String sdl, LintConfig lint, boolean dropGetter)
            throws IOException {
        var run = context(tmp, sdl, lint);
        try (var opened = GraphitronStore.captured(run)) {
            if (dropGetter) {
                dropDescriptionGetter(opened);
            }
            return new GraphQLRewriteGenerator(run, opened).runPass().output().warnings();
        }
    }

    /** What a generator strategy renaming film.description's getter leaves: no accessor row. */
    private static void dropDescriptionGetter(no.sikt.graphitron.model.run.CapturedGraph opened) {
        var a = SQL_COLUMN_RECORD_ACCESSOR;
        opened.store().dsl().deleteFrom(a)
            .where(a.TABLE_NAME.eq("film"), a.COLUMN_NAME.eq("description"))
            .execute();
    }

    private static RunContext context(Path tmp, String sdl, LintConfig lint) throws IOException {
        Path schema = Files.createTempFile(tmp, "schema", ".graphqls");
        // A run reads the file as written, without the test helper's prelude declaring Node.
        Files.writeString(schema, "interface Node { id: ID! }\n" + sdl);
        return new RunContext(
            List.of(new SchemaInput(SchemaSource.file(schema), Optional.empty(), Optional.empty())),
            tmp, "RecordValidationPipelineTest", tmp,
            DEFAULT_OUTPUT_PACKAGE, DEFAULT_JOOQ_PACKAGE
        ).withLintConfig(lint);
    }
}
