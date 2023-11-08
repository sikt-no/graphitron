package no.fellesstudentsystem.graphitron;

import no.fellesstudentsystem.graphitron.configuration.GeneratorConfig;
import no.fellesstudentsystem.graphitron.configuration.externalreferences.ExternalClassReference;

import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

public class GraphQLGeneratorQueryReferenceTest extends TestCommon {
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

    private final List<ExternalClassReference> references = List.of(
            new ExternalClassReference("TEST_STORE_CUSTOMER", "no.fellesstudentsystem.graphitron.conditions.StoreTestConditions"),
            new ExternalClassReference("TEST_CUSTOMER_ADDRESS", "no.fellesstudentsystem.graphitron.conditions.CustomerTestConditions"),
            new ExternalClassReference("TEST_FILM_ACTOR", "no.fellesstudentsystem.graphitron.conditions.FilmActorTestConditions")
    );

    public GraphQLGeneratorQueryReferenceTest() {
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

    @ParameterizedTest(name = "{index} {0}")
    @MethodSource("provideTestNamesAndPaths")
    void generate_referenceChecksOnQueries(String test, String path) throws IOException {
        assertThatGeneratedFilesMatchesExpectedFilesInOutputFolder("queryReferences/" + path);
    }

    @ParameterizedTest(name = "{index} {0}")
    @MethodSource("provideTestNamesAndPaths")
    void generate_referenceChecksOnArguments(String test, String path) throws IOException {
        assertThatGeneratedFilesMatchesExpectedFilesInOutputFolder("argumentReferences/" + path);
    }

    @ParameterizedTest(name = "{index} {0}")
    @MethodSource("provideTestNamesAndPaths")
    void generate_referenceChecksOnFields(String test, String path) throws IOException {
        assertThatGeneratedFilesMatchesExpectedFilesInOutputFolder("fieldReferences/" + path);
    }

    private static Stream<Arguments> provideTestNamesAndPaths() {
        return testNamesWithPaths.stream().map(it -> it.split(",")).map(it -> Arguments.of(it[0], it[1]));
    }
}
