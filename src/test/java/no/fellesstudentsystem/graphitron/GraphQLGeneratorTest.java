package no.fellesstudentsystem.graphitron;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import no.fellesstudentsystem.graphitron.configuration.GeneratorConfig;
import no.fellesstudentsystem.graphql.mapping.GenerationDirective;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class GraphQLGeneratorTest {
    public static final String SRC_TEST_RESOURCES = "src/test/resources/";
    @TempDir
    Path tempOutputDirectory;

    private ListAppender<ILoggingEvent> logWatcher;

    @BeforeEach
    void setup() {
        logWatcher = new ListAppender<>();
        logWatcher.start();
        ((Logger) LoggerFactory.getLogger(GraphQLGenerator.class)).addAppender(logWatcher);
    }

    @AfterEach
    void teardown() {
        ((Logger) LoggerFactory.getLogger(GraphQLGenerator.class)).detachAndStopAllAppenders();
        System.clearProperty(GeneratorConfig.PROPERTY_SCHEMA_FILES);
    }

    private Map<String, String> generateFiles(String schemaParentFolder) throws IOException {
        System.setProperty(GeneratorConfig.PROPERTY_SCHEMA_FILES, SRC_TEST_RESOURCES + schemaParentFolder + "/schema.graphqls," + SRC_TEST_RESOURCES + "defaultDirectives.graphqls");
        System.setProperty(GeneratorConfig.PROPERTY_OUTPUT_DIRECTORY, tempOutputDirectory.toString());
        GraphQLGenerator.generate();
        Map<String, String> generatedFiles = new HashMap<>();
        Files.walkFileTree(tempOutputDirectory, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (!Files.isDirectory(file)) {
                    generatedFiles.put(file.getFileName().toString(), readFileAsString(file));
                }
                return FileVisitResult.CONTINUE;
            }
        });
        return generatedFiles;
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
    void generate_nonRootObjectThatReturnsInterface_shouldCreateResolverAndLogWarning() throws IOException {
        assertThatGeneratedFilesMatchesExpectedFilesInOutputFolder("resolverReturningInterface");
        Set<String> logMessages = getLogMessagesWithLevelWarn();
        assertThat(logMessages).containsOnly(
                "No column(s) with name(s) 'REFERERTNODE' found in table 'STUDIERETT'",
                "interface (Node) returned in non root object. This is not fully supported. Use with care");
    }

    @Test
    void generate_queryThatReturnsInterfaceWhenIllegalArguments_shouldThrowException() {
        assertThatThrownBy(() -> generateFiles("error/queryReturningInterfaceIllegalArguments"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Only exactly one input field is currently supported for fields returning interfaces. 'nodes' has 2 input fields");
    }

    @Test
    void generate_resolverThatReturnsInterfaceWhenIllegalArguments_shouldThrowException() {
        assertThatThrownBy(() -> generateFiles("error/resolverReturningInterfaceIllegalArguments"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Only exactly one input field is currently supported for fields returning interfaces. 'referertNode' has 0 input fields");
    }

    @Test
    void generate_queryThatReturnsListOfInterfaces_shouldThrowException() {
        assertThatThrownBy(() -> generateFiles("error/queryReturningInterfaceCollection"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Generating fields returning collections/lists of interfaces is not supported. 'nodes' must return only one Node");
    }

    @Test
    void generate_whenIncorrectImplicitJoin_shouldThrowException() {
        assertThatThrownBy(() -> generateFiles("error/implicitJoinFailure"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Can not automatically infer join of 'STUDENT' and 'KULL'.");
    }

    @Test
    void generate_whenRecognizedDirectivesNotUsedInSchema_shouldLogWarning() throws IOException {
        System.setProperty(GeneratorConfig.PROPERTY_SCHEMA_FILES, SRC_TEST_RESOURCES + "warning/unusedDirective/schema.graphqls");
        System.setProperty(GeneratorConfig.PROPERTY_OUTPUT_DIRECTORY, tempOutputDirectory.toString());
        GraphQLGenerator.generate();
        Set<String> logMessages = getLogMessagesWithLevelWarn();
        assertThat(logMessages).containsOnly(
                "The following directives are declared in the code generator, but were not found in the GraphQL schema files: " +
                        "reference, condition, mapEnum, service, record, column");
    }

    @Test
    void generate_whenSpecifiedSchemaRootDirectory_shouldInfoLogAllExpectedSchemaFiles() throws IOException {
        var testDirectory = SRC_TEST_RESOURCES + "testReadingSchemasInDirectory";
        System.setProperty(
                GeneratorConfig.PROPERTY_SCHEMA_FILES,
                testDirectory + "/schema1.graphqls," + testDirectory + "/subdir/schema2.graphqls," + testDirectory + "/subdir/subsubdir/schema3.graphqls"
        );
        System.setProperty(GeneratorConfig.PROPERTY_OUTPUT_DIRECTORY, tempOutputDirectory.toString());
        GraphQLGenerator.generate();
        Set<String> logMessages = getLogMessagesWithLevel(Level.INFO);
        assertThat(logMessages.stream().anyMatch(msg ->
                msg.startsWith("Reading graphql schemas [") && msg.contains("schema1.graphqls") && msg.contains("schema2.graphqls")
                        && msg.contains("schema3.graphqls") && !msg.contains("notASchema"))
        ).isTrue();
    }

    @Test
    void generate_whenArgumentHasListOfInputsWithListField_shouldThrowException() {
        assertThatThrownBy(() -> generateFiles("error/listOfInputWithNestedList"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Argument 'inputWithListField' is of collection of InputFields ('InputWithListField') type. Fields returning collections: 'arstall' are not supported on such types (used for generating condition tuples)");
    }

    @Test
    void generate_whenArgumentHasListOfInputsWithOptionalField_shouldLogWarning() throws IOException {
        generateFiles("warning/listOfInputWithOptionalField");
        assertThat(getLogMessagesWithLevelWarn()).containsOnly(
                "Argument 'inputWithOptionalField' is of collection of InputFields ('InputWithOptionalField') type. Optional fields on such types are not supported. The following fields will be treated as mandatory in the resulting, generated condition tuple: 'maned', 'termintype'"
        );
    }

    @Test
    void generate_whenUnknownNodeTable_shouldLogWarning() throws IOException {
        generateFiles("warning/unknownNodeTable");
        assertThat(getLogMessagesWithLevelWarn()).containsOnly(
                "No table with name 'ROMSKIP' found in no.fellesstudentsystem.kjerneapi.Tables"
        );
    }

    @Test
    void generate_whenUnknownResourceTable_shouldLogWarning() throws IOException {
        generateFiles("warning/unknownResourceTable");
        assertThat(getLogMessagesWithLevelWarn()).containsOnly(
                "No table with name 'UNKNOWN_TABLE' found in no.fellesstudentsystem.kjerneapi.Tables"
        );
    }

    @Test
    void generate_whenUnknownColumn_shouldLogWarning() throws IOException {
        generateFiles("warning/unknownColumn");
        assertThat(getLogMessagesWithLevelWarn()).containsOnly(
                "No column(s) with name(s) 'GENERELL, KVERKNADTEKST_NYNORSK, ROMKAMERAT, ROMVESEN' found in table 'ROM'",
                "No column(s) with name(s) 'UNDERVISNINGSKAPASITET' found in table 'ROM'",
                "No column(s) with name(s) 'FODSELSNR2, KJONN2' found in table 'PERSON'",
                "No column(s) with name(s) 'MENNESKENR' found in table 'PERSON'"
        );
    }

    @Test
    void generate_whenUnknownEnum_shouldLogWarning() throws IOException {
        generateFiles("warning/unknownEnum");
        assertThat(getLogMessagesWithLevelWarn()).containsOnly(
                "No enum with name 'Kjonn2' found in no.fellesstudentsystem.kjerneapi.enums.GeneratorEnum"
        );
    }

    @Test
    void generate_whenIncorrectPaginationSpec_shouldLogWarning() throws IOException {
        generateFiles("error/queryIncorrectPagination");
        assertThat(getLogMessagesWithLevelWarn()).containsOnly(
                "Type QueryPersonConnection ending with the reserved suffix 'Connection' must have either " +
                        "forward(first and after fields) or backwards(last and before fields) pagination, yet " +
                        "neither was found. No pagination was generated for this type."
        );
    }

    @Test
    void generate_whenDirectiveNotRecognizedByGenerator_shouldNotLogWarning() throws IOException {
        generateFiles("warning/unrecognizedDirective");
        assertThat(getLogMessagesWithLevelWarn()).isEmpty();
    }

    @Test
    void generate_whenExhaustiveTestSchema_shouldNotLogWarning() throws IOException {
        generateFiles("allDefinedDirectivesInUse");
        assertThat(getLogMessagesWithLevelWarn()).isEmpty();
    }

    @Test
        /* "meta test" that verifies that all directives defined in the production graphql schema are also used
         and thus tested in the most exhaustive test schema.
         */
    void allDefinedDirectivesAreInUseAndTested() throws IOException {
        String testSchemaPath = SRC_TEST_RESOURCES + "allDefinedDirectivesInUse/schema.graphqls";
        String testSchema = readFileAsString(Paths.get(testSchemaPath));

        Stream
                .of(GenerationDirective.values())  // Changed test to only check directives used for code generation.
                .map(GenerationDirective::getName)
                .filter(it -> !it.equals("service") && !it.equals("record")) // Skip new mutation stuff, those are tested separately.
                .forEach(directiveName -> assertThat(testSchema).contains(directiveName));
    }

    @Test
    void generate_mutation_shouldGenerateResolversWithInputsAndResponseObjects() throws IOException {
        assertThatGeneratedFilesMatchesExpectedFilesInOutputFolder("mutationInputAndResponseResolvers");
    }

    @Test
    void generate_mutationWithListFields_shouldGenerateResolversForLists() throws IOException {
        assertThatGeneratedFilesMatchesExpectedFilesInOutputFolder("mutationListResolvers");
    }

    @Test
    void generate_mutationWithNestedInputs_shouldGenerateResolversForNestedStructures() throws IOException {
        assertThatGeneratedFilesMatchesExpectedFilesInOutputFolder("mutationNestedResolvers");
    }

    @Test
    void generate_mutation_shouldGenerateResolversWithIDsNotInDBs() throws IOException {
        assertThatGeneratedFilesMatchesExpectedFilesInOutputFolder("mutationMapIDsNotInDB");
    }

    @Test
    void generate_whenNoServiceMethodSet_shouldThrowException() {
        assertThatThrownBy(() -> generateFiles("error/serviceMethodNotSet"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(
                        "Requested to generate a method for 'endrePersonSimple' in type 'Mutation' without providing a service to call."
                );
    }

    @Test
    void generate_whenServiceNotFound_shouldThrowException() {
        assertThatThrownBy(() -> generateFiles("error/serviceNotFound"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(
                        "Requested to generate a method for 'endrePersonSimple' that calls service 'SERVICE_NOT_FOUND', " +
                                "but no such service was found in 'no.fellesstudentsystem.kjerneapi.services.GeneratorService'"
                );
    }

    @Test
    void generate_whenServiceMethodNotFound_shouldThrowException() {
        assertThatThrownBy(() -> generateFiles("error/serviceMethodNotFound"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(
                        "Service 'no.fellesstudentsystem.kjerneapi.services.test_services.TestPersonService'" +
                                " contains no method with the name 'endrePersonSimple'" +
                                " and 2 parameter(s), which is required to generate the resolver."
                );
    }

    private Set<String> getLogMessagesWithLevelWarn() {
        return getLogMessagesWithLevel(Level.WARN);
    }

    private Set<String> getLogMessagesWithLevel(Level level) {
        return logWatcher.list.stream()
                .filter(it -> it.getLevel() == level)
                .map(ILoggingEvent::getFormattedMessage)
                .collect(Collectors.toSet());
    }

    private void assertThatGeneratedFilesMatchesExpectedFilesInOutputFolder(String schemaFolder, String expectedOutputFolder) throws IOException {
        Map<String, String> generatedFiles = generateFiles(schemaFolder);
        var expectedFileNames = new HashSet<String>();

        Files.walkFileTree(Paths.get(SRC_TEST_RESOURCES + expectedOutputFolder + "/expectedOutput"), new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path expectedOutputFile, BasicFileAttributes attrs) throws IOException {
                String expectedFileName = expectedOutputFile.getFileName().toString().replace(".txt", "");
                expectedFileNames.add(expectedFileName);
                String expectedFileContent = readFileAsString(expectedOutputFile);
                assertThat(generatedFiles.keySet()).contains(expectedFileName);

                String generatedFileContent = generatedFiles.get(expectedFileName);
                assertThat(generatedFileContent).isEqualToIgnoringWhitespace(expectedFileContent);
                return FileVisitResult.CONTINUE;
            }
        });

        assertThat(expectedFileNames).containsExactlyInAnyOrderElementsOf(generatedFiles.keySet());
    }

    private void assertThatGeneratedFilesMatchesExpectedFilesInOutputFolder(String resourceRootFolder) throws IOException {
        assertThatGeneratedFilesMatchesExpectedFilesInOutputFolder(resourceRootFolder, resourceRootFolder);
    }

    private static String readFileAsString(Path file) throws IOException {
        return Files.readString(file, StandardCharsets.UTF_8);
    }
}
