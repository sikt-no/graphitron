package no.fellesstudentsystem.graphitron;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import no.fellesstudentsystem.graphitron.conditions.EmneTestConditions;
import no.fellesstudentsystem.graphitron.definitions.interfaces.GenerationTarget;
import no.fellesstudentsystem.graphitron.enums.KjonnTest;
import no.fellesstudentsystem.graphitron.generators.abstractions.ClassGenerator;
import no.fellesstudentsystem.graphitron.generators.db.FetchDBClassGenerator;
import no.fellesstudentsystem.graphitron.generators.resolvers.FetchResolverClassGenerator;
import no.fellesstudentsystem.graphql.mapping.GenerationDirective;
import no.fellesstudentsystem.kjerneapi.tables.Emne;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

public class GraphQLGeneratorQueryTest {
    public static final String
            SRC_TEST_RESOURCES_PATH = "query",
            SRC_TEST_RESOURCES = "src/test/resources/" + SRC_TEST_RESOURCES_PATH + "/";
    @TempDir
    Path tempOutputDirectory;

    private ListAppender<ILoggingEvent> logWatcher;

    private final Map<String, Class<?>> enumOverrides = Map.of("KJONN_TEST", KjonnTest.class);
    private final Map<String, Method> conditionOverrides = Map.of(
            "TEST_EMNE_KODE", EmneTestConditions.class.getMethod("emneKode", Emne.class, String.class),
            "TEST_EMNE_KODER", EmneTestConditions.class.getMethod("emneKoder", Emne.class, List.class),
            "TEST_EMNE_ALL", EmneTestConditions.class.getMethod("emneAll", Emne.class, String.class, List.class),
            "TEST_EMNE_INPUT_ALL", EmneTestConditions.class.getMethod("emneInputAll", Emne.class, String.class, String.class, String.class)
    );

    public GraphQLGeneratorQueryTest() throws NoSuchMethodException {
    }

    @BeforeEach
    void setup() {
        logWatcher = TestCommon.setup();
    }

    @AfterEach
    void teardown() {
        TestCommon.teardown();
    }

    private Map<String, String> generateFiles(String schemaParentFolder) throws IOException {
        var test = new TestCommon(schemaParentFolder, SRC_TEST_RESOURCES_PATH, tempOutputDirectory);

        var processedSchema = GraphQLGenerator.getProcessedSchema(false);
        processedSchema.validate(enumOverrides);
        List<ClassGenerator<? extends GenerationTarget>> generators = List.of(
                new FetchDBClassGenerator(processedSchema, enumOverrides, conditionOverrides),
                new FetchResolverClassGenerator(processedSchema)
        );

        test.setGenerators(generators);
        return test.generateFiles();
    }

    @Test
    void generate_usingAllDefinedDirectives_shouldGenerateResolversForAllSupportedTypesOfJoins() throws IOException {
        assertThatGeneratedFilesMatchesExpectedFilesInOutputFolder("allDefinedDirectivesInUse");
    }

    @Test //jOOQ' støtter maks 22 type-safe records. Flere enn 22 er støttet, men uten type safety.
    void generate_whenTypeHasMoreThan22Fields_shouldGenerateValidResolver() throws IOException {
        assertThatGeneratedFilesMatchesExpectedFilesInOutputFolder("moreThan22Fields");
    }

    @Test
    void generate_manualResolver_shouldNotGenerateAnyResolvers() throws IOException {
        assertThatGeneratedFilesMatchesExpectedFilesInOutputFolder("manualResolver");
    }

    @Test
    void generate_whenMixOfOptionalAndRequiredFieldsOnRequiredLeafNode_shouldGenerateQueryThatIncludesOneRequiredField() throws IOException {
        assertThatGeneratedFilesMatchesExpectedFilesInOutputFolder("mixOfOptionalAndRequiredFields");
    }

    @Test
    void generate_queryWithPagination_shouldCreateQueryResolverWithPaginationSupport() throws IOException {
        assertThatGeneratedFilesMatchesExpectedFilesInOutputFolder("queryWithPagination");
    }

    @Test
    void generate_queryWithResolverPagination_shouldCreateResolverWithPaginationOnResolver() throws IOException {
        assertThatGeneratedFilesMatchesExpectedFilesInOutputFolder("queryWithResolverPagination");
    }

    @Test
    void generate_queryWithPaginationFullRelayBoilerplate_shouldCreateQueryResolverWithPaginationSupport() throws IOException {
        assertThatGeneratedFilesMatchesExpectedFilesInOutputFolder("queryWithPaginationRelayBoilerplate", "queryWithPagination");
    }

    @Test
    void generate_queryWithMultipleAndOptionalArguments_shouldCreateQueryResolverThatHandlesMultipleAndOptionalArguments() throws IOException {
        assertThatGeneratedFilesMatchesExpectedFilesInOutputFolder("queryWithMultipleArguments");
    }

    @Test
    void generate_queryWithArguments_shouldUseCorrectPathForWhere() throws IOException {
        assertThatGeneratedFilesMatchesExpectedFilesInOutputFolder("correctWhereConditionPathUsage");
    }

    @Test
    void generate_queryWithInputTypes_shouldCreateQueryResolverThatHandlesInputTypes() throws IOException {
        assertThatGeneratedFilesMatchesExpectedFilesInOutputFolder("queryWithInputTypeArguments");
    }

    @Test
    void generate_queryWithConditions_shouldCreateQueriesWithExtraConditions() throws IOException {
        assertThatGeneratedFilesMatchesExpectedFilesInOutputFolder("queryWithConditions");
    }

    @Test
    void generate_referenceGivenKey_shouldJoinTables() throws IOException {
        assertThatGeneratedFilesMatchesExpectedFilesInOutputFolder("referenceGivenKey");
    }

    @Test
    void generate_referenceGivenConditionOnDirectJoin_shouldApplyCondition() throws IOException {
        assertThatGeneratedFilesMatchesExpectedFilesInOutputFolder("referenceGivenConditionOnDirectJoin");
    }

    @Test
    void generate_referenceViaTables_shouldCreateJoinViaTables() throws IOException {
        assertThatGeneratedFilesMatchesExpectedFilesInOutputFolder("referenceViaTables");
    }

    @Disabled("not supported yet")
    @Test
    void generate_referenceViaTablesBackwards_shouldCreateJoinViaTablesBackwards() throws IOException {
        assertThatGeneratedFilesMatchesExpectedFilesInOutputFolder("referenceViaTablesBackwards");
    }

    @Test
    void generate_referenceGivenConditionViaTables_shouldCreateJoinViaTablesThenCondition() throws IOException {
        assertThatGeneratedFilesMatchesExpectedFilesInOutputFolder("referenceGivenConditionViaTables");
    }

    @Test
    void generate_referenceViaTableWithCondition_shouldCreateJoinViaTableWithCondition() throws IOException {
        assertThatGeneratedFilesMatchesExpectedFilesInOutputFolder("referenceViaTableWithCondition");
    }

    @Test
    void generate_referenceGivenKeyViaTables_shouldCreateJoinViaTablesThenKey() throws IOException {
        assertThatGeneratedFilesMatchesExpectedFilesInOutputFolder("referenceGivenKeyViaTables");
    }

    @Test
    void generate_referenceGivenKeyViaTableWithConditionAndTableWithKey_shouldCreateJoinViaTablesThenKey() throws IOException {
        assertThatGeneratedFilesMatchesExpectedFilesInOutputFolder("referenceGivenKeyViaTableWithConditionAndTableWithKey");
    }

    @Test
    void generate_whenMultipleReferencesForSameType_shouldCreateUniqueAliases() throws IOException {
        assertThatGeneratedFilesMatchesExpectedFilesInOutputFolder("multipleAliasesForSameType");
    }

    @Test
    void generate_queryWithoutPagination_shouldCreateQueryAndQueryResolverWithoutPaginationSupport() throws IOException {
        assertThatGeneratedFilesMatchesExpectedFilesInOutputFolder("queryWithoutPagination");
    }

    @Test
    void generate_queryThatReturnsInterface_shouldCreateResolver() throws IOException {
        assertThatGeneratedFilesMatchesExpectedFilesInOutputFolder("queryReturningInterface");
        assertThat(getLogMessagesWithLevelWarn()).isEmpty();
    }

    @Test
        /* "meta test" that verifies that all directives defined in the production graphql schema are also used
         and thus tested in the most exhaustive test schema.
         */
    void allDefinedDirectivesAreInUseAndTested() throws IOException {
        String testSchemaPath = SRC_TEST_RESOURCES + "allDefinedDirectivesInUse/schema.graphqls";
        String testSchema = TestCommon.readFileAsString(Paths.get(testSchemaPath));

        var mutationDirectives = Set.of("service", "record", "mutationType", "error");
        Stream
                .of(GenerationDirective.values())  // Changed test to only check directives used for code generation.
                .map(GenerationDirective::getName)
                .filter(it -> !mutationDirectives.contains(it)) // Skip new mutation stuff, those are tested separately.
                .forEach(directiveName -> assertThat(testSchema).contains(directiveName));
    }

    private Set<String> getLogMessagesWithLevelWarn() {
        return logWatcher.list.stream()
                .filter(it -> it.getLevel() == Level.WARN)
                .map(ILoggingEvent::getFormattedMessage)
                .collect(Collectors.toSet());
    }

    private void assertThatGeneratedFilesMatchesExpectedFilesInOutputFolder(String schemaFolder, String expectedOutputFolder) throws IOException {
        Map<String, String> generatedFiles = generateFiles(schemaFolder);
        TestCommon.assertThatGeneratedFilesMatchesExpectedFilesInOutputFolder(SRC_TEST_RESOURCES + expectedOutputFolder, generatedFiles);
    }

    private void assertThatGeneratedFilesMatchesExpectedFilesInOutputFolder(String resourceRootFolder) throws IOException {
        assertThatGeneratedFilesMatchesExpectedFilesInOutputFolder(resourceRootFolder, resourceRootFolder);
    }
}
