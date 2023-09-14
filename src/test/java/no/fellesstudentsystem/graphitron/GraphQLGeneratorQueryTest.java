package no.fellesstudentsystem.graphitron;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import no.fellesstudentsystem.graphitron.conditions.*;
import no.fellesstudentsystem.graphitron.configuration.GeneratorConfig;
import no.sikt.graphitron.jooq.generated.testdata.enums.MpaaRating;
import no.sikt.graphitron.jooq.generated.testdata.tables.*;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Method;
import java.util.AbstractMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

public class GraphQLGeneratorQueryTest extends TestCommon {
    public static final String SRC_TEST_RESOURCES_PATH = "query";

    private final Map<String, Class<?>> enums = Map.of("RATING", MpaaRating.class);
    private final Map<String, Method> conditions = Map.ofEntries(
            new AbstractMap.SimpleEntry<>("TEST_STORE_CUSTOMER", StoreTestConditions.class.getMethod("mostValuableCustomer", Store.class, Customer.class)),
            new AbstractMap.SimpleEntry<>("TEST_CUSTOMER_ADDRESS", CustomerTestConditions.class.getMethod("customerAddressJoin", Customer.class, Address.class)),

            new AbstractMap.SimpleEntry<>("TEST_CITY_NAME", CityTestConditions.class.getMethod("cityName", City.class, String.class)),
            new AbstractMap.SimpleEntry<>("TEST_CITY_NAMES", CityTestConditions.class.getMethod("cityNames", City.class, List.class)),
            new AbstractMap.SimpleEntry<>("TEST_CITY_ALL", CityTestConditions.class.getMethod("cityAll", City.class, String.class, List.class)),
            new AbstractMap.SimpleEntry<>("TEST_CITY_INPUT_ALL", CityTestConditions.class.getMethod("cityInputAll", City.class, String.class, String.class, String.class)),

            new AbstractMap.SimpleEntry<>("TEST_FILM_MAIN_ACTOR", FilmActorTestConditions.class.getMethod("mainActor", Film.class, FilmActor.class)),
            new AbstractMap.SimpleEntry<>("TEST_FILM_STARRING_ACTOR", FilmActorTestConditions.class.getMethod("starringActor", Film.class, FilmActor.class)),

            new AbstractMap.SimpleEntry<>("TEST_FILM_RATING", RatingTestConditions.class.getMethod("rating", Film.class, String.class)),
            new AbstractMap.SimpleEntry<>("TEST_FILM_RATING_ALL", RatingTestConditions.class.getMethod("ratingAll", Film.class, String.class, Integer.class)),
            new AbstractMap.SimpleEntry<>("TEST_FILM_RATING_INPUT_ALL", RatingTestConditions.class.getMethod("ratingInputAll", Film.class, Integer.class, String.class, Integer.class))
    );

    public GraphQLGeneratorQueryTest() throws NoSuchMethodException {
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
                conditions,
                Map.of(),
                Map.of(),
                Map.of(),
                List.of()
        );
    }

    private Set<String> getLogMessagesWithLevelWarn() {
        return logWatcher.list.stream()
                .filter(it -> it.getLevel() == Level.WARN)
                .map(ILoggingEvent::getFormattedMessage)
                .collect(Collectors.toSet());
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
    void generate_queryWithConditions_shouldCreateQueriesWithEnumConditionInputs() throws IOException {
        assertThatGeneratedFilesMatchesExpectedFilesInOutputFolder("queryWithEnumConditions");
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
}
