package no.fellesstudentsystem.graphitron;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import no.fellesstudentsystem.graphitron.configuration.GeneratorConfig;
import no.fellesstudentsystem.graphitron.definitions.interfaces.GenerationTarget;
import no.fellesstudentsystem.graphitron.enums.KjonnTest;
import no.fellesstudentsystem.graphitron.generators.abstractions.ClassGenerator;
import no.fellesstudentsystem.graphitron.generators.db.FetchDBClassGenerator;
import no.fellesstudentsystem.graphitron.generators.resolvers.FetchResolverClassGenerator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class GraphQLGeneratorValidationTest {
    public static final String
            SRC_TEST_RESOURCES_PATH = "validation",
            SRC_TEST_RESOURCES = "src/test/resources/" + SRC_TEST_RESOURCES_PATH + "/";
    @TempDir
    Path tempOutputDirectory;

    private ListAppender<ILoggingEvent> logWatcher;

    private final Map<String, Class<?>> enumOverrides = Map.of("KJONN_TEST", KjonnTest.class);

    @BeforeEach
    void setup() {
        logWatcher = TestCommon.setup();
    }

    @AfterEach
    void teardown() {
        TestCommon.teardown();
    }

    private Map<String, String> generateFiles(String schemaParentFolder) throws IOException {
        return generateFiles(schemaParentFolder, false);
    }

    private Map<String, String> generateFiles(String schemaParentFolder, boolean warnDirectives) throws IOException {
        var test = new TestCommon(schemaParentFolder, SRC_TEST_RESOURCES_PATH, tempOutputDirectory);

        var processedSchema = GraphQLGenerator.getProcessedSchema(warnDirectives);
        processedSchema.validate(enumOverrides);
        List<ClassGenerator<? extends GenerationTarget>> generators = List.of(
                new FetchDBClassGenerator(processedSchema, enumOverrides, Map.of()),
                new FetchResolverClassGenerator(processedSchema)
        );

        test.setGenerators(generators);
        return test.generateFiles();
    }

    @Test
    void generate_nonRootObjectThatReturnsInterface_shouldCreateResolverAndLogWarning() throws IOException {
        assertThatGeneratedFilesMatchesExpectedFilesInOutputFolder("resolverReturningInterface", "resolverReturningInterface");
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
    void generate_whenImplicitJoinViaNonExistentPath_shouldThrowException() {
        assertThatThrownBy(() -> generateFiles("error/implicitJoinViaNonExistentPath"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Can not automatically infer join of 'EKSAMENSTILPASNING' and 'EMNE'.");
    }

    @Test
    void generate_whenimplicitJoinViaExistentThenNonExistentPath_shouldThrowException() {
        assertThatThrownBy(() -> generateFiles("error/implicitJoinViaExistentThenNonExistentPath"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Can not automatically infer join of 'LAND' and 'EKSAMENSTILPASNING'.");
    }

    @Test
    void generate_whenRecognizedDirectivesNotUsedInSchema_shouldLogWarning() throws IOException {
        System.setProperty(GeneratorConfig.PROPERTY_SCHEMA_FILES, SRC_TEST_RESOURCES + "warning/unusedDirective/schema.graphqls");
        System.setProperty(GeneratorConfig.PROPERTY_OUTPUT_DIRECTORY, tempOutputDirectory.toString());
        GraphQLGenerator.generate();
        Set<String> logMessages = getLogMessagesWithLevelWarn();
        assertThat(logMessages).containsOnly(
                "The following directives are declared in the code generator, but were not found in the GraphQL schema files: " +
                        "reference, condition, mapEnum, service, mutationType, record, column, error, table");
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
    void generate_whenUnknownColumnForImplicitJoin_shouldLogWarning() throws IOException {
        generateFiles("warning/unknownColumnForImplicitJoin");
        assertThat(getLogMessagesWithLevelWarn()).containsOnly(
                "No column(s) with name(s) 'BAKNAVN' found in table 'PERSON'"
        );
    }

    @Test
    void generate_whenUnknownEnum_shouldLogWarning() throws IOException {
        generateFiles("warning/unknownEnum");
        assertThat(getLogMessagesWithLevelWarn()).containsOnly(
                "No enum with name 'KJONN_TEST2' found in no.fellesstudentsystem.kjerneapi.enums.GeneratorEnum"
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
        generateFiles("missingDirective", true);
        assertThat(getLogMessagesWithLevelWarn()).isEmpty();
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
        TestCommon.assertThatGeneratedFilesMatchesExpectedFilesInOutputFolder(SRC_TEST_RESOURCES + expectedOutputFolder, generatedFiles);
    }
}
