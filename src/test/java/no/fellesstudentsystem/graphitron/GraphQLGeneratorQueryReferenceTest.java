package no.fellesstudentsystem.graphitron;

import no.fellesstudentsystem.graphitron.definitions.interfaces.GenerationTarget;
import no.fellesstudentsystem.graphitron.generators.abstractions.ClassGenerator;
import no.fellesstudentsystem.graphitron.generators.db.fetch.FetchDBClassGenerator;
import no.fellesstudentsystem.graphitron.generators.resolvers.fetch.FetchResolverClassGenerator;
import no.fellesstudentsystem.graphql.schema.ProcessedSchema;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.stream.Stream;

import static no.fellesstudentsystem.graphitron.TestReferenceSet.*;

public class GraphQLGeneratorQueryReferenceTest extends GeneratorTest {
    public static final String SRC_TEST_RESOURCES_PATH = "query/references";
    private static final List<String> testNamesWithPaths = List.of(
            "Given Key Should Join Tables,referenceGivenKey",
            "Given Condition On Direct Join Should Apply Condition,referenceGivenConditionOnDirectJoin",
            "Via Tables Should Create Join Via Tables,referenceViaTables",
            "Given Condition Via Tables Should Create Join Via Tables Then Condition,referenceGivenConditionViaTables",
            "Via Table With Condition Should Create Join Via Table With Condition,referenceViaTableWithCondition",
            "Given Key Via Tables Should Create Join Via Tables Then Key,referenceGivenKeyViaTables",
            "Given Key Via Table With Condition And Table With Key Should Create Join Via Tables Then Key,referenceGivenKeyViaTableWithConditionAndTableWithKey",
            "Given Key With Following Join Should Create Key Join Then Implicit Join,referenceGivenKeyWithExtraJoin"
    );

    public GraphQLGeneratorQueryReferenceTest() {
        super(SRC_TEST_RESOURCES_PATH, List.of(CONDITION_STORE_CUSTOMER.get(), CONDITION_CUSTOMER_ADDRESS.get(), CONDITION_FILM_ACTOR.get()));
    }

    @Override
    protected List<ClassGenerator<? extends GenerationTarget>> makeGenerators(ProcessedSchema schema) {
        return List.of(new FetchDBClassGenerator(schema));
    }

    @ParameterizedTest(name = "{index} {0}")
    @MethodSource("provideTestNamesAndPaths")
    void generate_referenceChecksOnQueries(String test, String path) {
        assertGeneratedContentMatches("queryReferences/" + path);
    }

    @ParameterizedTest(name = "{index} {0}")
    @MethodSource("provideTestNamesAndPaths")
    void generate_referenceChecksOnArguments(String test, String path) {
        assertGeneratedContentMatches("argumentReferences/" + path);
    }

    @ParameterizedTest(name = "{index} {0}")
    @MethodSource("provideTestNamesAndPaths")
    void generate_referenceChecksOnFields(String test, String path) {
        assertGeneratedContentMatches("fieldReferences/" + path);
    }

    private static Stream<Arguments> provideTestNamesAndPaths() {
        return testNamesWithPaths.stream().map(it -> it.split(",")).map(it -> Arguments.of(it[0], it[1]));
    }
}
