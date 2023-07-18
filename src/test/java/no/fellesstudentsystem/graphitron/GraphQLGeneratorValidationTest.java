package no.fellesstudentsystem.graphitron;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import no.fellesstudentsystem.graphitron.conditions.PermisjonTestConditions;
import no.fellesstudentsystem.graphitron.conditions.PersonTelefonTestConditions;
import no.fellesstudentsystem.graphitron.configuration.GeneratorConfig;
import no.fellesstudentsystem.graphitron.enums.KjonnTest;
import no.fellesstudentsystem.kjerneapi.tables.*;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class GraphQLGeneratorValidationTest extends TestCommon {
    public static final String
            SRC_TEST_RESOURCES_PATH = "validation",
            SRC_TEST_RESOURCES = "src/test/resources/" + SRC_TEST_RESOURCES_PATH + "/";

    private final Map<String, Class<?>> enums = Map.of("KJONN_TEST", KjonnTest.class);
    private final Map<String, Method> conditions = Map.of(
            "TEST_PERMISJON_STUDIERETT", PermisjonTestConditions.class.getMethod("permisjonStudierettJoin", Permisjon.class, Studierett.class),
            "TEST_PERSON_TELEFON_MOBIL", PersonTelefonTestConditions.class.getMethod("personTelefonMobil", Person.class, PersonTelefon.class)
    );

    public GraphQLGeneratorValidationTest() throws NoSuchMethodException {
        super(SRC_TEST_RESOURCES_PATH);
    }

    @Override
    protected void setProperties() {
        GeneratorConfig.setProperties(
                List.of(),
                tempOutputDirectory.toString(),
                DEFAULT_OUTPUT_PACKAGE,
                enums,
                conditions,
                Map.of(),
                Map.of()
        );
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
    void generate_whenRecognizedDirectivesNotUsedInSchema_shouldLogWarning() {
        GeneratorConfig.setSchemaFiles(SRC_TEST_RESOURCES + "warning/unusedDirective/schema.graphqls");
        GraphQLGenerator.generate();
        Set<String> logMessages = getLogMessagesWithLevelWarn();
        assertThat(logMessages).containsOnly(
                "The following directives are declared in the code generator, but were not found in the GraphQL schema files: " +
                        "reference, condition, mapEnum, service, mutationType, record, column, error, table");
    }

    @Test
    void generate_whenSpecifiedSchemaRootDirectory_shouldInfoLogAllExpectedSchemaFiles() {
        var testDirectory = SRC_TEST_RESOURCES + "testReadingSchemasInDirectory";
        GeneratorConfig.setSchemaFiles(testDirectory + "/schema1.graphqls", testDirectory + "/subdir/schema2.graphqls", testDirectory + "/subdir/subsubdir/schema3.graphqls");

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
                .hasMessage("Argument 'inputWithListField' is of collection of InputFields ('InputWithListField') type. Fields returning collections: 'institusjonsnummer' are not supported on such types (used for generating condition tuples)");
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
                "No enum with name 'KJONN_TEST2' found."
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
}
