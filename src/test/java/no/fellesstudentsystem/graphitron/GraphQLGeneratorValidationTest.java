package no.fellesstudentsystem.graphitron;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import no.fellesstudentsystem.graphitron.configuration.GeneratorConfig;
import no.sikt.graphitron.jooq.generated.testdata.enums.MpaaRating;
import no.fellesstudentsystem.graphitron.mojo.GraphQLGenerator;
import org.junit.jupiter.api.Disabled;
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
    public static final String SRC_TEST_RESOURCES_PATH = "validation";

    private final Map<String, Class<?>> enums = Map.of("RATING", MpaaRating.class);

    public GraphQLGeneratorValidationTest() {
        super(SRC_TEST_RESOURCES_PATH);
    }

    @Override
    protected void setProperties() {
        GeneratorConfig.setProperties(
                DEFAULT_SYSTEM_PACKAGE,
                Set.of(),
                tempOutputDirectory.toString(),
                DEFAULT_OUTPUT_PACKAGE,
                DEFAULT_JOOQ_PACKAGE,
                enums,
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                List.of()
        );
    }

    @Test
    void generate_nonRootObjectThatReturnsInterface_shouldCreateResolverAndLogWarning() {
        getProcessedSchema("resolverReturningInterface", false);
        assertThat(getLogMessagesWithLevelWarn()).containsOnly(
                "No column(s) with name(s) 'NODEREF' found in table 'CUSTOMER'",
                "interface (Node) returned in non root object. This is not fully supported. Use with care");
    }

    @Test
    void generate_queryThatReturnsInterfaceWhenIllegalArguments_shouldThrowException() {
        assertThatThrownBy(() -> getProcessedSchema("error/queryReturningInterfaceIllegalArguments", false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Only exactly one input field is currently supported for fields returning interfaces. 'nodes' has 2 input fields");
    }

    @Test
    void generate_resolverThatReturnsInterfaceWhenIllegalArguments_shouldThrowException() {
        assertThatThrownBy(() -> getProcessedSchema("error/resolverReturningInterfaceIllegalArguments", false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Only exactly one input field is currently supported for fields returning interfaces. 'nodeRef' has 0 input fields");
    }

    @Test
    void generate_queryThatReturnsListOfInterfaces_shouldThrowException() {
        assertThatThrownBy(() -> getProcessedSchema("error/queryReturningInterfaceCollection", false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Generating fields returning collections/lists of interfaces is not supported. 'nodes' must return only one Node");
    }

    @Test
    void generate_whenIncorrectImplicitJoin_shouldThrowException() {
        assertThatThrownBy(() -> generateFiles("error/implicitJoinFailure"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Can not automatically infer join of 'ADDRESS' and 'FILM'.");
    }

    @Test
    void generate_whenImplicitJoinViaNonExistentPath_shouldThrowException() {
        assertThatThrownBy(() -> generateFiles("error/implicitJoinViaNonExistentPath"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Can not automatically infer join of 'ADDRESS' and 'FILM'.");
    }

    @Test
    void generate_whenimplicitJoinViaExistentThenNonExistentPath_shouldThrowException() {
        assertThatThrownBy(() -> generateFiles("error/implicitJoinViaExistentThenNonExistentPath"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Can not automatically infer join of 'COUNTRY' and 'FILM'.");
    }

    @Test
    void generate_whenRecognizedDirectivesNotUsedInSchema_shouldLogWarning() {
        GeneratorConfig.setSchemaFiles(getSourceTestPath() + "warning/unusedDirective/schema.graphqls");
        GraphQLGenerator.getProcessedSchema(true).validate();
        assertThat(getLogMessagesWithLevelWarn()).containsOnly(
                "The following directives are declared in the code generator, but were not found in the GraphQL schema files: " +
                        "reference, condition, mapEnum, service, mutationType, column, error, table");
    }

    @Test
    void generate_whenSpecifiedSchemaRootDirectory_shouldInfoLogAllExpectedSchemaFiles() {
        var testDirectory = getSourceTestPath() + "testReadingSchemasInDirectory";
        GeneratorConfig.setSchemaFiles(testDirectory + "/schema1.graphqls", testDirectory + "/subdir/schema2.graphqls", testDirectory + "/subdir/subsubdir/schema3.graphqls");

        GraphQLGenerator.getProcessedSchema(false).validate();
        assertThat(getLogMessagesWithLevel(Level.INFO).stream().anyMatch(msg ->
                msg.startsWith("Reading graphql schemas [") && msg.contains("schema1.graphqls") && msg.contains("schema2.graphqls")
                        && msg.contains("schema3.graphqls") && !msg.contains("notASchema"))
        ).isTrue();
    }

    @Test
    void generate_whenArgumentHasListOfInputsWithListField_shouldThrowException() {
        assertThatThrownBy(() -> getProcessedSchema("error/listOfInputWithNestedList", false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Argument 'inputWithListField' is of collection of InputFields ('InputWithListField') type. Fields returning collections: 'ids' are not supported on such types (used for generating condition tuples)");
    }

    @Test
    void generate_whenArgumentHasListOfInputsWithOptionalField_shouldLogWarning() {
        getProcessedSchema("warning/listOfInputWithOptionalField", false);
        assertThat(getLogMessagesWithLevelWarn()).containsOnly(
                "Argument 'inputWithOptionalField' is of collection of InputFields ('InputWithOptionalField') type. Optional fields on such types are not supported. The following fields will be treated as mandatory in the resulting, generated condition tuple: 'title', 'rating'"
        );
    }

    @Test
    void generate_whenUnknownNodeTable_shouldLogWarning() {
        getProcessedSchema("warning/unknownNodeTable", false);
        assertThat(getLogMessagesWithLevelWarn()).containsOnly(
                "No table with name 'TRACTOR' found in no.sikt.graphitron.jooq.generated.testdata.Tables"
        );
    }

    @Test
    void generate_whenUnknownResourceTable_shouldLogWarning() {
        getProcessedSchema("warning/unknownResourceTable", false);
        assertThat(getLogMessagesWithLevelWarn()).containsOnly(
                "No table with name 'UNKNOWN_TABLE' found in no.sikt.graphitron.jooq.generated.testdata.Tables"
        );
    }

    @Test
    void generate_whenUnknownColumn_shouldLogWarning() {
        getProcessedSchema("warning/unknownColumn", false);
        assertThat(getLogMessagesWithLevelWarn()).containsOnly(
                "No column(s) with name(s) 'NONEXISTENTDURATION, NONEXISTENTRATE, RATING2, TITLE2' found in table 'FILM'",
                "No column(s) with name(s) 'NON_EXISTENT_ID' found in table 'FILM'"
        );
    }

    @Test
    void generate_whenUnknownColumnForImplicitJoin_shouldLogWarning() {
        getProcessedSchema("warning/unknownColumnForImplicitJoin", false);
        assertThat(getLogMessagesWithLevelWarn()).containsOnly(
                "No column(s) with name(s) 'DESTRUCT' found in table 'ADDRESS'"
        );
    }

    @Test
    void generate_whenUnknownEnum_shouldLogWarning() {
        getProcessedSchema("warning/unknownEnum", false);
        assertThat(getLogMessagesWithLevelWarn()).containsOnly(
                "No enum with name 'UNKOWN_ENUM' found."
        );
    }

    @Test
    void generate_whenIncorrectPaginationSpec_shouldLogWarning() {
        getProcessedSchema("error/queryIncorrectPagination", false);
        assertThat(getLogMessagesWithLevelWarn()).containsOnly(
                "Type ActorConnection ending with the reserved suffix 'Connection' must have either " +
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
    void generate_whenMutationTypeHasMissingMapping_shouldLogWarning() {
        getProcessedSchema("warning/insertMissingRecordMapping", false);
        assertThat(getLogMessagesWithLevelWarn()).containsOnly(
                "Input type InsertCustomerInput referencing table CUSTOMER does not map all fields required by the database. Missing required fields: FIRST_NAME",
                "Input type InsertCustomerInput referencing table CUSTOMER does not map all fields required by the database as non-nullable. Nullable required fields: FIRST_NAME"
        );
    }

    @Test
    void generate_whenMutationTypeHasMissingMappingWithDefault_shouldNotLogWarning() {
        getProcessedSchema("warning/insertMissingRecordMappingWithDefault", false);
        assertThat(getLogMessagesWithLevelWarn()).isEmpty();
    }

    @Test
    void generate_whenMutationTypeHasMissingRequiredMapping_shouldLogWarning() {
        getProcessedSchema("warning/insertMissingRecordRequiredMapping", false);
        assertThat(getLogMessagesWithLevelWarn()).containsOnly(
                "Input type InsertCustomerInput referencing table CUSTOMER does not map all fields required by the database as non-nullable. Nullable required fields: FIRST_NAME"
        );
    }

    @Test
    void generate_whenInsertMutationTypeHasNoRecord_shouldThrowException() {
        assertThatThrownBy(() -> getProcessedSchema("error/insertNoRecordSet", false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Mutation registerCustomerInput is set as an insert operation, but does not link any input to tables.");
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
