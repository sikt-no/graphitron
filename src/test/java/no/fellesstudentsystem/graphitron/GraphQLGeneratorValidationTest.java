package no.fellesstudentsystem.graphitron;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import no.fellesstudentsystem.graphitron.configuration.GeneratorConfig;
import no.fellesstudentsystem.graphitron.definitions.interfaces.GenerationTarget;
import no.fellesstudentsystem.graphitron.generators.abstractions.ClassGenerator;
import no.fellesstudentsystem.graphitron.generators.db.fetch.FetchDBClassGenerator;
import no.fellesstudentsystem.graphitron.generators.resolvers.fetch.FetchResolverClassGenerator;
import no.fellesstudentsystem.graphitron.mojo.GraphQLGenerator;
import no.fellesstudentsystem.graphql.directives.GenerationDirective;
import no.fellesstudentsystem.graphql.directives.GenerationDirectiveParam;
import no.fellesstudentsystem.graphql.schema.ProcessedSchema;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static no.fellesstudentsystem.graphitron.TestReferenceSet.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class GraphQLGeneratorValidationTest extends GeneratorTest {
    public static final String SRC_TEST_RESOURCES_PATH = "validation";

    public GraphQLGeneratorValidationTest() {
        super(
                SRC_TEST_RESOURCES_PATH,
                List.of(
                        ENUM_RATING.get(),
                        CONDITION_FILM_RATING.get(),
                        CONDITION_STORE_CUSTOMER.get(),
                        CONDITION_CUSTOMER_ADDRESS.get(),
                        SERVICE_CUSTOMER.get(),
                        RECORD_CUSTOMER.get()
                ),
                false
        );
    }

    @Override
    protected List<ClassGenerator<? extends GenerationTarget>> makeGenerators(ProcessedSchema schema) {
        return List.of(
                new FetchDBClassGenerator(schema),
                new FetchResolverClassGenerator(schema)
        );
    }

    @Test
    void generate_nonRootObjectThatReturnsInterface_shouldThrowExceptionAndLogWarning() {
        assertThatThrownBy(() -> getProcessedSchema("resolverReturningInterface"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Problems have been found that prevent code generation:\n" +
                        "interface (Node) returned in non root object. This is not fully supported. Use with care"
                );
        assertThat(getLogMessagesWithLevelWarn()).containsOnly(
                "Problems have been found that MAY prevent code generation:\n" +
                        "No field(s) or method(s) with name(s) 'NODEREF' found in table 'CUSTOMER'"
        );
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

    @Disabled
    @Test
    void generate_whenMultisetRequireReferenceConditionOnItself_shouldThrowException() {
        assertThatThrownBy(() ->  generateFiles("error/multisetReferenceConditionNotSupported"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(String.format("List of type Address requires the @%s directive to be able to contain @%s in a @%s within a list", GenerationDirective.SPLIT_QUERY.getName(), GenerationDirectiveParam.CONDITION.getName(), GenerationDirective.REFERENCE.getName()));
    }

    @Disabled
    @Test
    void generate_whenMultisetRequireFieldInputArgumentOnItself_shouldThrowException() {
        assertThatThrownBy(() ->  generateFiles("error/multisetFieldInputArgumentNotSupported"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Input arguments is not supported for multiset lists in INVENTORY");
    }

    @Disabled
    @Test
    void generate_whenMultisetFailsToGenerateWhereBetweenTwoUnconnectedTables_shouldThrowException() {
        assertThatThrownBy(() ->  generateFiles("error/multisetIncorrectWhereConstruction"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("The multiset context in FILM is set to generate a where statement but cannot find a path between FILM and RENTAL");
    }

    @Test
    void generate_whenUnknownNodeTable_shouldLogWarning() {
        getProcessedSchema("warning/unknownNodeTable");
        assertThat(getLogMessagesWithLevelWarn()).containsOnly(
                "No table or key with name 'TRACTOR' found in no.sikt.graphitron.jooq.generated.testdata"
        );
    }

    @Test
    void generate_whenUnknownResourceTable_shouldLogWarning() {
        getProcessedSchema("warning/unknownResourceTable");
        assertThat(getLogMessagesWithLevelWarn()).containsOnly(
                "No table or key with name 'UNKNOWN_TABLE' found in no.sikt.graphitron.jooq.generated.testdata"
        );
    }

    @Test
    void generate_whenUnknownColumn_shouldLogWarning() {
        getProcessedSchema("warning/unknownColumn");
        assertThat(getLogMessagesWithLevelWarn()).containsOnly("Problems have been found that MAY prevent code generation:\n" +
                "No field(s) or method(s) with name(s) 'NONEXISTENTDURATION, NONEXISTENTRATE, NON_EXISTENT_ID, RATING2, TITLE2' found in table 'FILM'"
        );
    }

    @Test
    void generate_whenUnknownColumnForImplicitJoin_shouldLogWarning() {
        getProcessedSchema("warning/unknownColumnForImplicitJoin");
        assertThat(getLogMessagesWithLevelWarn()).containsOnly("Problems have been found that MAY prevent code generation:\n" +
                "No field(s) or method(s) with name(s) 'DESTRUCT' found in table 'ADDRESS'"
        );
    }

    @Test
    void generate_whenUnknownEnum_shouldThrowException() {
        assertThatThrownBy(() -> getProcessedSchema("error/unknownEnum"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Problems have been found that prevent code generation:\n" +
                        "No enum with name 'UNKOWN_ENUM' found."
                );
    }

    @Test
    void generate_whenIncorrectPaginationSpec_shouldThrowException() {
        assertThatThrownBy(() -> getProcessedSchema("error/queryIncorrectPagination"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Problems have been found that prevent code generation:\n" +
                        "Type ActorConnection ending with the reserved suffix 'Connection' must have either " +
                        "forward(first and after fields) or backwards(last and before fields) pagination, yet " +
                        "neither was found. No pagination was generated for this type.");

    }

    @Test
    void generate_whenMutationTypeHasMissingMapping_shouldLogWarning() {
        getProcessedSchema("warning/insertMissingRecordMapping");
        assertThat(getLogMessagesWithLevelWarn()).containsOnly("Problems have been found that MAY prevent code generation:\n" +
                "Input type InsertCustomerInput referencing table CUSTOMER does not map all fields required by the database. Missing required fields: FIRST_NAME" + "\n" +
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
        assertThat(getLogMessagesWithLevelWarn()).containsOnly("Problems have been found that MAY prevent code generation:\n" +
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
    void generate_whenFieldHasInvalidType_shouldThrowExceptionWithSuggestions() {
        assertThatThrownBy(() -> getProcessedSchema("error/invalidType", true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(
                        "Problems have been found that prevent code generation:\n" +
                                "Field \"rental\" within schema type \"Query\" has invalid type \"Rentaaal\" (or an union containing it). Closest type matches found by levenshtein distance are:\n" +
                                "Rental - 2\n" +
                                "Field \"rentalUnion\" within schema type \"Query\" has invalid type \"Rentaaaaal\" (or an union containing it). Closest type matches found by levenshtein distance are:\n" +
                                "Rental - 4\n" +
                                "Field \"rentalUnionExtra\" within schema type \"Query\" has invalid type \"Rentaaaaal\" (or an union containing it). Closest type matches found by levenshtein distance are:\n" +
                                "Rental - 4\n" +
                                "Field \"rentalUnionExtra\" within schema type \"Query\" has invalid type \"Reeentaaaaal\" (or an union containing it). Closest type matches found by levenshtein distance are:\n" +
                                "Rental - 6\n" +
                                "Field \"payment\" within schema type \"Query\" has invalid type \"Payment\" (or an union containing it). Closest type matches found by levenshtein distance are:\n" +
                                "Payment0 - 1, Payment1 - 1\n\n" +

                                "Field \"rental\" within schema type \"Customer\" has invalid type \"Rentaaal\" (or an union containing it). Closest type matches found by levenshtein distance are:\n" +
                                "Rental - 2"
                );
    }

    @Test
    void generate_whenImplicitJoinDoesNotExist_shouldLogWarning() {
        getProcessedSchema("warning/implicitJoinDoesNotExist");
        assertThat(getLogMessagesWithLevelWarn()).containsOnly("Problems have been found that MAY prevent code generation:\n" +
                "No field(s) or method(s) with name(s) 'payment' found in table 'STORE'"
        );
    }

    @Test
    void generate_whenKeyMissing_shouldLogWarning() {
        getProcessedSchema("warning/referenceKeysMissing");
        assertThat(getLogMessagesWithLevelWarn()).containsOnly("Problems have been found that MAY prevent code generation:\n" +
                "No field(s) or method(s) with name(s) 'NOT_A_KEY' found in table 'ADDRESS'" + "\n" +
                "No field(s) or method(s) with name(s) 'FAKE_KEY' found in table 'CUSTOMER'",
                "No table or key with name 'FAKE_KEY' found in no.sikt.graphitron.jooq.generated.testdata",
                "No table or key with name 'NOT_A_KEY' found in no.sikt.graphitron.jooq.generated.testdata"
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
        assertThat(getLogMessagesWithLevelWarn()).containsOnly("Problems have been found that MAY prevent code generation:\n" +
                "No field(s) or method(s) with name(s) 'FAKEFIELD0, FAKEFIELD2, FAKEFIELD4' found in table 'FILM'"
        );
    }

    @Test
    void generate_whenOnlyInputOrPayloadFieldIsIterable_shouldLogWarning() {
        getProcessedSchema("warning/onlyInputOrPayloadIsList");
        assertThat(getLogMessagesWithLevelWarn()).containsOnly("Problems have been found that MAY prevent code generation:\n" +
                "Mutation editCustomerWarn1 with Input EditInput is not defined as a list while Payload type EditResponseNoListField contains a list" + "\n" +
                "Mutation editCustomerWarn2 with Input EditInput is not defined as a list while Payload type EditResponseListField contains a list" + "\n" +
                "Mutation editCustomerWarn3 with Input EditInput is not defined as a list while Payload type EditResponseOneListField contains a list" + "\n" +
                "Mutation editCustomerWarn4 with Input EditInput is defined as a list while Payload type EditResponseNoListField does not contain a list" + "\n" +
                "Mutation editCustomerWarn5 with Input EditInput is defined as a list while Payload type EditResponseWithErrors does not contain a list"
        );
    }

    @Test
    void generate_serviceMutationWithWronglyIterableRecordFields_shouldThrowException() {
        assertThatThrownBy(() -> getProcessedSchema("error/wronglyIterableNestedRecordFields"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Problems have been found that prevent code generation:\n"
                        + "Field edit2A with Input type EditInputLevel2A is iterable, but has no record mapping set. Iterable Input types within records without record mapping can not be mapped to a single field in the surrounding record.\n"
                        + "Field edit2B with Input type EditInputLevel2B is iterable, but has no record mapping set. Iterable Input types within records without record mapping can not be mapped to a single field in the surrounding record."
                );
    }

    @Test
    void generate_queryWithSelfReferenceMissingSplitQuery_shouldThrowException() {
        assertThatThrownBy(() -> generateFiles("error/queryWithSelfReferenceMissingSplitQuery"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Problems have been found that prevent code generation:\n" +
                        "Self reference must have splitQuery, field \"sequel\" in object \"Film\"");
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
