package no.sikt.graphitron.rewrite;

import com.palantir.javapoet.TypeSpec;
import no.sikt.graphitron.model.config.RunContext;
import no.sikt.graphitron.model.derive.EntryDefectVerdict;
import no.sikt.graphitron.model.lint.LintConfig;
import no.sikt.graphitron.model.read.StoreHandle;
import no.sikt.graphitron.model.test.CapturedGraphs;
import no.sikt.graphitron.model.test.FactGraphs;
import no.sikt.graphitron.model.schema.input.SchemaInput;
import no.sikt.graphitron.model.schema.input.SchemaSource;
import no.sikt.graphitron.rewrite.generators.schema.ConstraintViolationsClassGenerator;
import no.sikt.graphitron.rewrite.model.MutationField;
import no.sikt.graphitron.rewrite.model.ServiceMethodCall;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static no.sikt.graphitron.common.configuration.TestConfiguration.DEFAULT_JOOQ_PACKAGE;
import static no.sikt.graphitron.common.configuration.TestConfiguration.DEFAULT_OUTPUT_PACKAGE;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * A VALIDATION channel over a {@code @service} parameter bound to a jOOQ record: the record is a
 * validation target, the pre-step's {@code ConstraintViolations.Paths} constant is declared for it,
 * and a column the store found no getter for is the stored warning {@code VALIDATION_COLUMN_UNCHECKED},
 * whose message names it. That column is renamed_getter_draft.description, whose getter the fixture
 * catalog's codegen renames; {@code defects/validation-column-unchecked} pins the defect and its column
 * rows, and the run here reads its message. Structural assertions only; the generated method body is
 * pinned at the execution tier.
 */
@PipelineTier
class RecordValidationPipelineTest {

    @RegisterExtension
    static final FactGraphs.Reader FACTS = FactGraphs.reader();

    /** saveDrafts over renamed_getter_draft's records, under a VALIDATION channel. */
    private static final String RENAMED_GETTER_DOCUMENT = "defects/validation-column-unchecked";

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

    /**
     * The fact document's graph, written again for the one case a fact document cannot serve: a
     * generating run, which needs output coordinates a fact capture does not have.
     */
    private static final String RENAMED_GETTER_WITH_CHANNEL = FILM + """
        type DraftInvalid @error(handlers: [{handler: VALIDATION}]) { path: [String!]! message: String! }
        union DraftError = DraftInvalid
        type DraftsCountedPayload { count: Int errors: [DraftError] }
        input DraftInput {
            title: String @field(name: "title")
            description: String @field(name: "description")
        }
        type Mutation {
            saveDrafts(in: [DraftInput!]!): DraftsCountedPayload @service(service: {
                className: "no.sikt.graphitron.rewrite.test.services.RenamedGetterDraftService",
                method: "count", argMapping: "drafts: in"})
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
        assertThat(unchecked(tmp, CHANNEL + FIELDS)).isEmpty();
    }

    @Test
    void aColumnWithNoGetterIsAStoredWarningNamingIt() {
        FACTS.read(RENAMED_GETTER_DOCUMENT, graph -> {
            assertThat(unchecked(graph.handle()))
                .singleElement()
                .satisfies(row -> assertThat(EntryDefectVerdict.error(row).message())
                    .contains("Mutation.saveDrafts", "columns with no getter: description"));
            return null;
        });
    }

    /**
     * A generating run over a record with a column it cannot check emits the validating call, the
     * pre-step checking the columns it has getters for, and keeps the warning: an unchecked column is
     * no reason to refuse the service.
     */
    @Test
    void aGeneratingRunEmitsTheValidatingCallAndKeepsTheWarning(@TempDir Path tmp) throws IOException {
        var run = generatingContext(tmp, RENAMED_GETTER_WITH_CHANNEL);
        CapturedGraphs.read(run, opened -> {
            var pass = new GraphQLRewriteGenerator(run, opened.handle(), opened.schema()).runPass();
            assertThat(pass.output().report().errors()).as("nothing refuses the service").isEmpty();
            assertThat(pass.generation()).as("the round generates").isPresent();
            assertThat(unchecked(opened.handle())).singleElement()
                .satisfies(row -> assertThat(row.detail()).isEqualTo("description"));
            return null;
        });
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

    /** The stored VALIDATION_COLUMN_UNCHECKED warnings of the graph {@code sdl} captures. */
    private static List<EntryDefectVerdict.Row> unchecked(Path tmp, String sdl) throws IOException {
        var run = context(tmp, sdl, LintConfig.empty());
        return CapturedGraphs.read(run, opened -> unchecked(opened.handle()));
    }

    private static List<EntryDefectVerdict.Row> unchecked(StoreHandle store) {
        return EntryDefectVerdict.rows(store.dsl(), store.graphName(), "warning").stream()
            .filter(row -> row.code().equals("VALIDATION_COLUMN_UNCHECKED"))
            .toList();
    }

    /**
     * A run over {@code sdl} whose classpath is the service module's root, which holds the class the
     * graph's {@code @service} names: a capture that read no classpath resolves no service, so its
     * call could not be emitted.
     */
    private static RunContext generatingContext(Path tmp, String sdl) throws IOException {
        Path schema = Files.createTempFile(tmp, "schema", ".graphqls");
        Files.writeString(schema, "interface Node { id: ID! }\n" + sdl);
        Path services;
        try {
            services = Path.of(no.sikt.graphitron.rewrite.test.services.RenamedGetterDraftService.class
                .getProtectionDomain().getCodeSource().getLocation().toURI());
        } catch (java.net.URISyntaxException e) {
            throw new IllegalStateException("the service module's classpath entry", e);
        }
        return new RunContext(
            List.of(new SchemaInput(SchemaSource.file(schema), Optional.empty(), Optional.empty())),
            tmp, "RecordValidationPipelineTest", tmp,
            DEFAULT_OUTPUT_PACKAGE, DEFAULT_JOOQ_PACKAGE, List.of(services));
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
