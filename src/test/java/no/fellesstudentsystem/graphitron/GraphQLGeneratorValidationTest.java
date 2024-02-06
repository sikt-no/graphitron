package no.fellesstudentsystem.graphitron;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import no.fellesstudentsystem.graphitron.configuration.GeneratorConfig;
import no.fellesstudentsystem.graphitron.configuration.externalreferences.ExternalClassReference;
import no.fellesstudentsystem.graphitron.mojo.GraphQLGenerator;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class GraphQLGeneratorValidationTest extends TestCommon {
    public static final String SRC_TEST_RESOURCES_PATH = "validation";
    private final List<ExternalClassReference> references = List.of(
            new ExternalClassReference("RATING_TEST", "no.fellesstudentsystem.graphitron.enums.RatingTest"),
            new ExternalClassReference("TEST_FILM_RATING", "no.fellesstudentsystem.graphitron.conditions.RatingTestConditions"),
            new ExternalClassReference("TEST_STORE_CUSTOMER", "no.fellesstudentsystem.graphitron.conditions.StoreTestConditions"),
            new ExternalClassReference("TEST_CUSTOMER_ADDRESS", "no.fellesstudentsystem.graphitron.conditions.CustomerTestConditions"),
            new ExternalClassReference("TEST_CUSTOMER", "no.fellesstudentsystem.graphitron.services.TestCustomerService"),
            new ExternalClassReference("TEST_CUSTOMER_INPUT_RECORD", "no.fellesstudentsystem.graphitron.records.TestCustomerInputRecord")
    );
    public GraphQLGeneratorValidationTest() {
        super(SRC_TEST_RESOURCES_PATH);
    }

    @Override
    protected void setProperties() {
        GeneratorConfig.setProperties(
                Set.of(),
                tempOutputDirectory.toString(),
                DEFAULT_OUTPUT_PACKAGE,
                DEFAULT_JOOQ_PACKAGE,
                references,
                List.of(),
                List.of()
        );
    }

    @Test
    void generate_nonRootObjectThatReturnsInterface_shouldCreateResolverAndLogWarning() {
        getProcessedSchema("resolverReturningInterface");
        assertThat(getLogMessagesWithLevelWarn()).containsOnly(
                "No field(s) or method(s) with name(s) 'NODEREF' found in table 'CUSTOMER'",
                "interface (Node) returned in non root object. This is not fully supported. Use with care");
    }

    @Test
    void generate_queryThatReturnsInterfaceWhenIllegalArguments_shouldThrowException() {
        assertThatThrownBy(() -> getProcessedSchema("error/queryReturningInterfaceIllegalArguments"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Only exactly one input field is currently supported for fields returning interfaces. 'nodes' has 2 input fields");
    }

    @Test
    void generate_resolverThatReturnsInterfaceWhenIllegalArguments_shouldThrowException() {
        assertThatThrownBy(() -> getProcessedSchema("error/resolverReturningInterfaceIllegalArguments"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Only exactly one input field is currently supported for fields returning interfaces. 'nodeRef' has 0 input fields");
    }

    @Test
    void generate_queryThatReturnsListOfInterfaces_shouldThrowException() {
        assertThatThrownBy(() -> getProcessedSchema("error/queryReturningInterfaceCollection"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Generating fields returning collections/lists of interfaces is not supported. 'nodes' must return only one Node");
    }

    @Test
    void generate_whenSpecifiedSchemaRootDirectory_shouldInfoLogAllExpectedSchemaFiles() {
        var testDirectory = getSourceTestPath() + "testReadingSchemasInDirectory";
        GeneratorConfig.setSchemaFiles(testDirectory + "/schema1.graphqls", testDirectory + "/subdir/schema2.graphqls", testDirectory + "/subdir/subsubdir/schema3.graphqls");

        GraphQLGenerator.getProcessedSchema().validate();
        assertThat(getLogMessagesWithLevel(Level.INFO).stream().anyMatch(msg ->
                msg.startsWith("Reading graphql schemas [") && msg.contains("schema1.graphqls") && msg.contains("schema2.graphqls")
                        && msg.contains("schema3.graphqls") && !msg.contains("notASchema"))
        ).isTrue();
    }

    @Test
    void generate_whenArgumentHasListOfInputsWithListField_shouldThrowException() {
        assertThatThrownBy(() -> getProcessedSchema("error/listOfInputWithNestedList"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Argument 'inputWithListField' is of collection of InputFields ('InputWithListField') type. Fields returning collections: 'ids' are not supported on such types (used for generating condition tuples)");
    }

    @Test
    void generate_whenMultisetRequireReferenceConditionOnItself_shouldThrowException() {
        assertThatThrownBy(() ->  generateFiles("error/multisetReferenceConditionNotSupported"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("List of type Address requires the @SplitQuery directive to be able to contain @condition in a @reference within a list");
    }

    @Test
    void generate_whenMultisetRequireFieldInputArgumentOnItself_shouldThrowException() {
        assertThatThrownBy(() ->  generateFiles("error/multisetFieldInputArgumentNotSupported"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Input arguments is not supported for multiset lists in INVENTORY");
    }

    @Test
    void generate_whenMultisetFailsToGenerateWhereBetweenTwoUnconnectedTables_shouldThrowException() {
        assertThatThrownBy(() ->  generateFiles("error/multisetIncorrectWhereConstruction"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("The multiset context in FILM is set to generate a where statement but cannot find a path between FILM and RENTAL");
    }

    @Test
    void generate_whenArgumentHasListOfInputsWithOptionalField_shouldLogWarning() {
        getProcessedSchema("warning/listOfInputWithOptionalField");
        assertThat(getLogMessagesWithLevelWarn()).containsOnly(
                "Argument 'inputWithOptionalField' is of collection of InputFields ('InputWithOptionalField') type. Optional fields on such types are not supported. The following fields will be treated as mandatory in the resulting, generated condition tuple: 'title', 'rating'"
        );
    }

    @Test
    void generate_whenUnknownNodeTable_shouldLogWarning() {
        getProcessedSchema("warning/unknownNodeTable");
        assertThat(getLogMessagesWithLevelWarn()).containsOnly(
                "No table or key with name 'TRACTOR' found in no.sikt.graphitron.jooq.generated.testdata.Tables or no.sikt.graphitron.jooq.generated.testdata.Keys"
        );
    }

    @Test
    void generate_whenUnknownResourceTable_shouldLogWarning() {
        getProcessedSchema("warning/unknownResourceTable");
        assertThat(getLogMessagesWithLevelWarn()).containsOnly(
                "No table or key with name 'UNKNOWN_TABLE' found in no.sikt.graphitron.jooq.generated.testdata.Tables or no.sikt.graphitron.jooq.generated.testdata.Keys"
        );
    }

    @Test
    void generate_whenUnknownColumn_shouldLogWarning() {
        getProcessedSchema("warning/unknownColumn");
        assertThat(getLogMessagesWithLevelWarn()).containsOnly(
                "No field(s) or method(s) with name(s) 'NONEXISTENTDURATION, NONEXISTENTRATE, NON_EXISTENT_ID, RATING2, TITLE2' found in table 'FILM'"
        );
    }

    @Test
    void generate_whenUnknownColumnForImplicitJoin_shouldLogWarning() {
        getProcessedSchema("warning/unknownColumnForImplicitJoin");
        assertThat(getLogMessagesWithLevelWarn()).containsOnly(
                "No field(s) or method(s) with name(s) 'DESTRUCT' found in table 'ADDRESS'"
        );
    }

    @Test
    void generate_whenUnknownEnum_shouldLogWarning() {
        getProcessedSchema("warning/unknownEnum");
        assertThat(getLogMessagesWithLevelWarn()).containsOnly(
                "No enum with name 'UNKOWN_ENUM' found."
        );
    }

    @Test
    void generate_whenIncorrectPaginationSpec_shouldLogWarning() {
        getProcessedSchema("error/queryIncorrectPagination");
        assertThat(getLogMessagesWithLevelWarn()).containsOnly(
                "Type ActorConnection ending with the reserved suffix 'Connection' must have either " +
                        "forward(first and after fields) or backwards(last and before fields) pagination, yet " +
                        "neither was found. No pagination was generated for this type."
        );
    }

    @Test
    void generate_whenMutationTypeHasMissingMapping_shouldLogWarning() {
        getProcessedSchema("warning/insertMissingRecordMapping");
        assertThat(getLogMessagesWithLevelWarn()).containsOnly(
                "Input type InsertCustomerInput referencing table CUSTOMER does not map all fields required by the database. Missing required fields: FIRST_NAME",
                "Input type InsertCustomerInput referencing table CUSTOMER does not map all fields required by the database as non-nullable. Nullable required fields: FIRST_NAME"
        );
    }

    @Test
    void generate_whenMutationTypeHasMissingMappingWithDefault_shouldNotLogWarning() {
        getProcessedSchema("warning/insertMissingRecordMappingWithDefault");
        assertThat(getLogMessagesWithLevelWarn()).isEmpty();
    }

    @Test
    void generate_whenMutationTypeHasMissingRequiredMapping_shouldLogWarning() {
        getProcessedSchema("warning/insertMissingRecordRequiredMapping");
        assertThat(getLogMessagesWithLevelWarn()).containsOnly(
                "Input type InsertCustomerInput referencing table CUSTOMER does not map all fields required by the database as non-nullable. Nullable required fields: FIRST_NAME"
        );
    }

    @Test
    void generate_whenInsertMutationTypeHasNoRecord_shouldThrowException() {
        assertThatThrownBy(() -> getProcessedSchema("error/insertNoRecordSet"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Mutation registerCustomerInput is set as an insert operation, but does not link any input to tables.");
    }

    @Test
    void generate_whenImplicitJoinDoesNotExist_shouldLogWarning() {
        getProcessedSchema("warning/implicitJoinDoesNotExist");
        assertThat(getLogMessagesWithLevelWarn()).containsOnly(
                "No field(s) or method(s) with name(s) 'payment' found in table 'STORE'"
        );
    }

    @Test
    void generate_whenKeyMissing_shouldLogWarning() {
        getProcessedSchema("warning/referenceKeysMissing");
        assertThat(getLogMessagesWithLevelWarn()).containsOnly(
                "No table or key with name 'FAKE_KEY' found in no.sikt.graphitron.jooq.generated.testdata.Tables or no.sikt.graphitron.jooq.generated.testdata.Keys",
                "No field(s) or method(s) with name(s) 'FAKE_KEY' found in table 'CUSTOMER'",
                "No table or key with name 'NOT_A_KEY' found in no.sikt.graphitron.jooq.generated.testdata.Tables or no.sikt.graphitron.jooq.generated.testdata.Keys",
                "No field(s) or method(s) with name(s) 'NOT_A_KEY' found in table 'ADDRESS'"
        );
    }

    @Test
    void generate_whenKeyUsedInReverse_shouldNotLogWarning() {
        getProcessedSchema("warning/referenceKeysReversed");
        assertThat(getLogMessagesWithLevelWarn()).isEmpty();
    }

    @Test
    void generate_whenFieldsDoNotMatchDBAndHasOverridingCondition_shouldNotLogWarning() {
        getProcessedSchema("warning/queryWithNonMappedArguments");
        assertThat(getLogMessagesWithLevelWarn()).containsOnly(
                "No field(s) or method(s) with name(s) 'FAKEFIELD0, FAKEFIELD2, FAKEFIELD4' found in table 'FILM'"
        );
    }

    @Test
    void generate_whenOnlyInputOrPayloadFieldIsIterable_shouldLogWarning() {
        getProcessedSchema("warning/onlyInputOrPayloadIsList");
        assertThat(getLogMessagesWithLevelWarn()).containsOnly(
                "Mutation editCustomerWarn1 with Input EditInput is not defined as a list while Payload type EditResponseNoListField contains a list",
                "Mutation editCustomerWarn2 with Input EditInput is not defined as a list while Payload type EditResponseListField contains a list",
                "Mutation editCustomerWarn3 with Input EditInput is not defined as a list while Payload type EditResponseOneListField contains a list",
                "Mutation editCustomerWarn4 with Input EditInput is defined as a list while Payload type EditResponseNoListField does not contain a list",
                "Mutation editCustomerWarn5 with Input EditInput is defined as a list while Payload type EditResponseWithErrors does not contain a list"
        );
    }

    @Test
    void generate_serviceMutationWithWronglyIterableRecordFields_shouldWarnOfIterable() {
        getProcessedSchema("warning/wronglyIterableNestedRecordFields");
        assertThat(getLogMessagesWithLevelWarn()).containsOnly(
                "Field edit2A with Input type EditInputLevel2A is iterable, but has no record mapping set. Iterable Input types within records without record mapping can not be mapped to a single field in the surrounding record.",
                "Field edit2B with Input type EditInputLevel2B is iterable, but has no record mapping set. Iterable Input types within records without record mapping can not be mapped to a single field in the surrounding record."
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
}
