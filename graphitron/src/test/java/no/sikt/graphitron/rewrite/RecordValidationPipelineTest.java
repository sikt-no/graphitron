package no.sikt.graphitron.rewrite;

import com.palantir.javapoet.TypeSpec;
import no.sikt.graphitron.model.config.RunContext;
import no.sikt.graphitron.model.diagnostics.BuildWarning;
import no.sikt.graphitron.model.lint.LintConfig;
import no.sikt.graphitron.model.lint.LintRule;
import no.sikt.graphitron.model.test.CapturedGraphs;
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
import static org.assertj.core.api.Assertions.assertThat;

/**
 * A VALIDATION channel over a {@code @service} parameter bound to a jOOQ record: the record is a
 * validation target, the pre-step's {@code ConstraintViolations.Paths} constant is declared for it,
 * and a column the store found no getter for reaches the report as {@code validation-column-unchecked}.
 * That column is renamed_getter_draft.description, whose getter the fixture catalog's codegen renames.
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

    private static final String FILM = """
        type Film implements Node @table(name: "film") @node { id: ID! title: String }
        type Query { films: [Film!]! }
        """;

    private static final String FIELDS = FILM + """
        input FilmDraftInput {
            id: ID @nodeId(typeName: "Film")
            title: String @field(name: "title")
            description: String @field(name: "description")
        }
        input FilmKeyInput { id: ID! @nodeId(typeName: "Film") }
        type Mutation {
            draftFilms(in: [FilmDraftInput!]!): SakPayload
                @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "runSakWithFilmRecords"})
            keyFilm(in: FilmKeyInput!): SakPayload
                @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "runSakWithFilmRecord"})
        }
        """;

    /** A record whose description column has no getter of jOOQ's default name, and whose title has one. */
    private static final String RENAMED_GETTER = FILM + """
        input DraftInput {
            title: String @field(name: "title")
            description: String @field(name: "description")
        }
        type Mutation {
            saveDrafts(in: [DraftInput!]!): SakPayload
                @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "runSakWithRenamedGetterDrafts"})
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
        assertThat(unchecked(validateOnly(tmp, CHANNEL + FIELDS, LintConfig.empty()))).isEmpty();
    }

    @Test
    void aColumnWithNoGetterIsReportedFromAValidateOnlyRun(@TempDir Path tmp) throws IOException {
        assertThat(unchecked(validateOnly(tmp, CHANNEL + RENAMED_GETTER, LintConfig.empty())))
            .singleElement()
            .satisfies(w -> assertThat(w.message())
                .contains("Mutation.saveDrafts", "RenamedGetterDraftRecord", "'description'", "getDescription()"));
    }

    @Test
    void aColumnWithNoGetterIsReportedFromAGeneratingRun(@TempDir Path tmp) throws IOException {
        assertThat(unchecked(generating(tmp, CHANNEL + RENAMED_GETTER, LintConfig.empty()))).hasSize(1);
    }

    @Test
    void withoutTheChannelNoColumnIsReported(@TempDir Path tmp) throws IOException {
        assertThat(unchecked(validateOnly(tmp, NO_VALIDATION_CHANNEL + RENAMED_GETTER, LintConfig.empty()))).isEmpty();
    }

    @Test
    void theRuleIdSuppressesTheFinding(@TempDir Path tmp) throws IOException {
        assertThat(unchecked(validateOnly(tmp, CHANNEL + RENAMED_GETTER,
            new LintConfig(Set.of("validation-column-unchecked"), List.of())))).isEmpty();
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

    private static List<BuildWarning> validateOnly(Path tmp, String sdl, LintConfig lint) throws IOException {
        return warnings(tmp, sdl, lint, generator -> generator.buildOutput().warnings());
    }

    private static List<BuildWarning> generating(Path tmp, String sdl, LintConfig lint) throws IOException {
        return warnings(tmp, sdl, lint, generator -> generator.runPass().output().warnings());
    }

    private static List<BuildWarning> warnings(Path tmp, String sdl, LintConfig lint,
            java.util.function.Function<GraphQLRewriteGenerator, List<BuildWarning>> pass) throws IOException {
        var run = context(tmp, sdl, lint);
        return CapturedGraphs.read(run, opened ->
            pass.apply(new GraphQLRewriteGenerator(run, opened.handle(), opened.schema())));
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
