package no.fellesstudentsystem.graphitron;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import no.fellesstudentsystem.graphitron.conditions.*;
import no.fellesstudentsystem.graphitron.configuration.GeneratorConfig;
import no.fellesstudentsystem.graphitron.configuration.externalreferences.ExternalClassReference;
import no.fellesstudentsystem.graphitron.configuration.externalreferences.ExternalReference;
import no.fellesstudentsystem.graphitron.enums.RatingListTest;
import no.fellesstudentsystem.graphitron.services.TestCustomerService;
import no.fellesstudentsystem.graphql.directives.GenerationDirective;
import no.fellesstudentsystem.graphql.directives.GenerationDirectiveParam;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

public class GraphQLGeneratorQueryTest extends TestCommon {
    public static final String SRC_TEST_RESOURCES_PATH = "query";

    private final List<ExternalReference> references = List.of(
            new ExternalClassReference("TEST_ENUM_RATING_LIST", RatingListTest.class),
            new ExternalClassReference("TEST_CITY", CityTestConditions.class),
            new ExternalClassReference("TEST_FILM_ACTOR", FilmActorTestConditions.class),
            new ExternalClassReference("TEST_FILM_RATING", RatingTestConditions.class),
            new ExternalClassReference("TEST_STORE_CUSTOMER", StoreTestConditions.class),
            new ExternalClassReference("TEST_CUSTOMER_ADDRESS", CustomerTestConditions.class),
            new ExternalClassReference("TEST_CUSTOMER", TestCustomerService.class)
    );

    public GraphQLGeneratorQueryTest() {
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
    void generate_queryWithPaginationAndSorting_shouldCreateQueryResolverWithPaginationAndSortingSupport() throws IOException {
        assertThatGeneratedFilesMatchesExpectedFilesInOutputFolder("queryWithPaginationAndSorting");
    }

    @Test
    void generate_queryWithSorting_shouldCreateQueryResolverWithSortingSupport() throws IOException {
        assertThatGeneratedFilesMatchesExpectedFilesInOutputFolder("queryWithSorting");
    }

    @Test
    void generate_whenOrderByNonIndexedColumn_shouldThrowException() {
        assertThatThrownBy(() -> generateFiles("error/queryWithOrderByNonIndexedColumn"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Table 'FILM' has no index 'NON_EXISTANT_INDEX' necessary for sorting by 'RELEASE_YEAR'");
    }

    @Test
    void generate_queryWithPaginationAndOrderBy_whenIndexOnFieldThatIsInaccessibleFromSchemaType_shouldThrowException() {
        assertThatThrownBy(() -> generateFiles("error/queryWithOrderByIndexOnFieldThatIsInaccessibleFromSchemaType"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("OrderByField 'LANGUAGE' refers to index 'IDX_FK_LANGUAGE_ID' on field 'language_id' but this field is not accessible from the schema type 'Film'");
    }

    @Test
    void generate_queryWithPaginationAndOrderBy_whenMissingIndexDirective_shouldThrowException() {
        assertThatThrownBy(() -> generateFiles("error/queryWithOrderByWithMissingIndexReference"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(String.format("Expected enum field 'TITLE' of 'FilmOrderByField' to have an '@%s(%s : ...)' directive, but no such directive was found", GenerationDirective.INDEX.getName(), GenerationDirectiveParam.NAME.getName()));
    }

    @Test
    void generate_queryWithSortingCombinedWithLookupKey_shouldThrowException() {
        assertThatThrownBy(() -> generateFiles("error/queryWithOrderByAndLookUp"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(String.format("'films' has both @%s and @%s defined. These directives can not be used together", GenerationDirective.ORDER_BY.getName(), GenerationDirective.LOOKUP_KEY.getName()));
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
    void generate_queryWithMultiset_shouldCreateQueryResolverThatHandlesMultiset() throws IOException {
        assertThatGeneratedFilesMatchesExpectedFilesInOutputFolder("queryWithMultiset");
    }

    @Test
    void generate_queryWithMultiset_shouldCreateQueryResolverThatHandlesMultisetWhichContainsFieldWithCondition() throws IOException {
        assertThatGeneratedFilesMatchesExpectedFilesInOutputFolder("queryWithMultisetContainingFieldWithCondition");
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
    void generate_queryWithInputWithListInput_shouldCreateQueryResolverThatHandlesInputWithListOfInputTypes() throws IOException {
        assertThatGeneratedFilesMatchesExpectedFilesInOutputFolder("queryWithInputWithListInput");
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
    void generate_queryWithConditions_shouldCreateQueriesWithEnumListConditionInputs() throws IOException {
        assertThatGeneratedFilesMatchesExpectedFilesInOutputFolder("queryWithEnumListConditions");
    }

    @Test
    void generate_queryWithInputs_shouldCreateConditionsWithoutExtraNullChecksWhenIterable() throws IOException {
        assertThatGeneratedFilesMatchesExpectedFilesInOutputFolder("queryWithLayeredAndListedChecks");
    }

    @Test
    void generate_splitQueryAtTypeWithoutTable_shouldFindAppropriateSourceTable() throws IOException {
        assertThatGeneratedFilesMatchesExpectedFilesInOutputFolder("splitQueryForTypeWithoutTable");
    }

    @Test
    void generate_referenceViaTablesBackwards_shouldCreateJoinViaTablesBackwards() throws IOException {
        assertThatGeneratedFilesMatchesExpectedFilesInOutputFolder("referenceBackwards");
    }

    @Test
    void generate_referenceViaTablesBackwardsAndJoin_shouldCreateJoinViaTablesBackwards() throws IOException {
        assertThatGeneratedFilesMatchesExpectedFilesInOutputFolder("referenceBackwardsWithExtraJoin");
    }

    @Test
    void generate_conditionOnReverseJoin_shouldFindAppropriateConditionSource() throws IOException {
        assertThatGeneratedFilesMatchesExpectedFilesInOutputFolder("referenceBackwardWithCondition");
    }

    @Test
    void generate_joinWhenFutureHasExplicitJoin_shouldCreateAppropriateJoinType() throws IOException {
        assertThatGeneratedFilesMatchesExpectedFilesInOutputFolder("referenceWithFutureExplicitJoin");
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
    void generate_queryWithUnion_shouldCreateQueryAndQueryResolverWithUnionSupport() throws IOException {
        assertThatGeneratedFilesMatchesExpectedFilesInOutputFolder("queryWithUnion");
    }

    @Test
    void generate_queryWithLookup_shouldGenerateLookupResolverAndQuery() throws IOException {
        assertThatGeneratedFilesMatchesExpectedFilesInOutputFolder("queryWithLookup");
    }

    @Test
    void generate_queryWithComplexLookup_shouldGenerateLookupResolversAndQueries() throws IOException {
        assertThatGeneratedFilesMatchesExpectedFilesInOutputFolder("queryWithLookupMultipleParameters");
    }


    @Test
    void generate_queryThatReturnsInterface_shouldCreateResolver() throws IOException {
        assertThatGeneratedFilesMatchesExpectedFilesInOutputFolder("queryReturningInterface");
        assertThat(getLogMessagesWithLevelWarn()).isEmpty();
    }

    @Test
    void generate_whenImplementsNodeWithoutTable_shouldThrowException() {
        assertThatThrownBy(() -> generateFiles("error/implementsNodeWithoutTable"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(String.format("Type Film needs to have the @%s directive set to be able to implement interface Node", GenerationDirective.TABLE.getName()));
    }
    @Test
    void generate_simpleInterfaceWithoutTable_shouldNotThrowException() {
        assertDoesNotThrow(() -> generateFiles("simpleInterfaceWithoutTable"));
    }

    @Test
    void generate_queryWithSelfReferenceHavingExplicitJoinKey() throws IOException {
        assertThatGeneratedFilesMatchesExpectedFilesInOutputFolder("queryWithSelfReferenceHavingExplicitJoinKey");
    }

    @Test
    void generate_queryWithSelfReferenceFindingImplicitJoinKey() throws IOException {
        assertThatGeneratedFilesMatchesExpectedFilesInOutputFolder("queryWithSelfReferenceFindingImplicitJoinKey");
    }
}
