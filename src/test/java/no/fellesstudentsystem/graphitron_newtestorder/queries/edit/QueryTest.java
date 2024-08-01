package no.fellesstudentsystem.graphitron_newtestorder.queries.edit;

import no.fellesstudentsystem.graphitron.definitions.interfaces.GenerationTarget;
import no.fellesstudentsystem.graphitron.generators.abstractions.ClassGenerator;
import no.fellesstudentsystem.graphitron.generators.db.update.UpdateDBClassGenerator;
import no.fellesstudentsystem.graphitron_newtestorder.GeneratorTest;
import no.fellesstudentsystem.graphitron_newtestorder.SchemaComponent;
import no.fellesstudentsystem.graphql.schema.ProcessedSchema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static no.fellesstudentsystem.graphitron_newtestorder.SchemaComponent.CUSTOMER_INPUT_TABLE;

@DisplayName("Mutation queries - Queries for updating data")
public class QueryTest extends GeneratorTest {
    private static final List<String> testNamesWithPaths = List.of(
            "Delete mutation,delete",
            "Insert mutation,insert",
            "Update mutation,update",
            "Upsert mutation,upsert",
            "Mutation with an unusable extra field,extraField",
            "List mutation,listed", // Mutation type does not matter.

            // These may be unused in practice and could have problems.
            "Double record mutation,twoRecords",
            "Mutations with two distinct records,twoDifferentRecords",
            "Mutations with two records where both are lists,twoListedRecords",
            "Mutations with two records where one is a list,twoRecordsOneListed"
    );

    @Override
    protected String getSubpath() {
        return "queries/edit";
    }

    @Override
    protected Set<SchemaComponent> getComponents() {
        return makeComponents(CUSTOMER_INPUT_TABLE);
    }

    @Override
    protected List<ClassGenerator<? extends GenerationTarget>> makeGenerators(ProcessedSchema schema) {
        return List.of(new UpdateDBClassGenerator(schema));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("provideTestNamesAndPaths")
    @DisplayName("Mutation query")
    @SuppressWarnings("unused")
    void queryTest(String test, String path) {
        assertGeneratedContentMatches(path);
    }

    private static Stream<Arguments> provideTestNamesAndPaths() {
        return testNamesWithPaths.stream().map(it -> it.split(",")).map(it -> Arguments.of(it[0], it[1]));
    }
}
