package no.fellesstudentsystem.graphitron;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import no.fellesstudentsystem.graphitron.definitions.interfaces.GenerationTarget;
import no.fellesstudentsystem.graphitron.generators.abstractions.ClassGenerator;
import no.fellesstudentsystem.graphitron.generators.db.fetch.FetchDBClassGenerator;
import no.fellesstudentsystem.graphql.schema.ProcessedSchema;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static no.fellesstudentsystem.graphitron.TestReferenceSet.*;
import static org.assertj.core.api.Assertions.assertThat;

public class GraphQLGeneratorQueryWithoutResolverTest extends GeneratorTest {
    public static final String SRC_TEST_RESOURCES_PATH = "query";

    public GraphQLGeneratorQueryWithoutResolverTest() {
        super(
                SRC_TEST_RESOURCES_PATH,
                List.of(
                        ENUM_RATING_LIST.get(),
                        CONDITION_CITY.get(),
                        CONDITION_FILM_ACTOR.get(),
                        CONDITION_FILM_RATING.get(),
                        CONDITION_STORE_CUSTOMER.get(),
                        CONDITION_CUSTOMER_ADDRESS.get(),
                        SERVICE_CUSTOMER.get()
                )
        );
    }
    @Override
    protected List<ClassGenerator<? extends GenerationTarget>> makeGenerators(ProcessedSchema schema) {
        return List.of(new FetchDBClassGenerator(schema));
    }

    @Test
    void generate_queryWithArguments_shouldUseCorrectPathForWhere() {
        assertGeneratedContentMatches("correctWhereConditionPathUsage");
    }

    @Test
    void generate_whenMixOfOptionalAndRequiredFieldsOnRequiredLeafNode_shouldGenerateQueryThatIncludesOneRequiredField() {
        assertGeneratedContentMatches("mixOfOptionalAndRequiredFields");
    }

    @Test //jOOQ' støtter maks 22 type-safe records. Flere enn 22 er støttet, men uten type safety.
    void generate_whenTypeHasMoreThan22Fields_shouldGenerateValidResolver() {
        assertGeneratedContentMatches("moreThan22Fields");
    }

    @Test
    void generate_whenMultipleReferencesForSameType_shouldCreateUniqueAliases() {
        assertGeneratedContentMatches("multipleAliasesForSameType");
    }

    @Test
    void generate_queryWithConditions_shouldCreateQueriesWithExtraConditions() {
        assertGeneratedContentMatches("queryWithConditions");
    }

    @Test
    void generate_queryWithConditions_shouldCreateQueriesWithEnumConditionInputs() {
        assertGeneratedContentMatches("queryWithEnumConditions");
    }

    @Test
    void generate_queryWithConditions_shouldCreateQueriesWithEnumListConditionInputs() {
        assertGeneratedContentMatches("queryWithEnumListConditions");
    }

    @Test
    void generate_queryWithInputTypes_shouldCreateQueryResolverThatHandlesInputTypes() {
        assertGeneratedContentMatches("queryWithInputTypeArguments");
    }

    @Test
    void generate_queryWithInputWithListInput_shouldCreateQueryResolverThatHandlesInputWithListOfInputTypes() {
        assertGeneratedContentMatches("queryWithInputWithListInput");
    }

    @Test
    void generate_queryWithInputs_shouldCreateConditionsWithoutExtraNullChecksWhenIterable() {
        assertGeneratedContentMatches("queryWithLayeredAndListedChecks");
    }

    @Test
    void generate_queryWithMultipleAndOptionalArguments_shouldCreateQueryResolverThatHandlesMultipleAndOptionalArguments() {
        assertGeneratedContentMatches("queryWithMultipleArguments");
    }

    @Disabled
    @Test
    void generate_queryWithMultiset_shouldCreateQueryResolverThatHandlesMultiset() {
        assertGeneratedContentMatches("queryWithMultiset");
    }

    @Disabled
    @Test
    void generate_queryWithMultiset_shouldCreateQueryResolverThatHandlesMultisetWhichContainsFieldWithCondition() {
        assertGeneratedContentMatches("queryWithMultisetContainingFieldWithCondition");
    }

    @Test
    void generate_queryWithoutPagination_shouldCreateQueryAndQueryResolverWithoutPaginationSupport() {
        assertGeneratedContentMatches("queryWithoutPagination");
    }

    @Test
    void generate_queryWithPagination_shouldCreateQueryResolverWithPaginationSupport() {
        assertGeneratedContentMatches("queryWithPagination");
    }

    @Test
    void generate_queryWithResolverPagination_shouldCreateResolverWithPaginationOnResolver() {
        assertGeneratedContentMatches("queryWithResolverPagination");
    }

    @Test
    void generate_queryWithSelfReferenceFindingImplicitJoinKey() {
        assertGeneratedContentMatches("queryWithSelfReferenceFindingImplicitJoinKey");
    }

    @Test
    void generate_queryWithSelfReferenceHavingConditionAndNoKey() {
        assertGeneratedContentMatches("queryWithSelfReferenceHavingConditionAndNoKey");
    }

    @Test
    void generate_queryWithSelfReferenceHavingExplicitJoinKey() {
        assertGeneratedContentMatches("queryWithSelfReferenceHavingExplicitJoinKey");
    }

    @Test
    void generate_queryWithUnion_shouldCreateQueryAndQueryResolverWithUnionSupport() {
        assertGeneratedContentMatches("queryWithUnion");
    }

    @Test
    void generate_referenceViaTablesBackwards_shouldCreateJoinViaTablesBackwards() {
        assertGeneratedContentMatches("referenceBackwards");
    }

    @Test
    void generate_referenceViaTablesBackwardsAndJoin_shouldCreateJoinViaTablesBackwards() {
        assertGeneratedContentMatches("referenceBackwardsWithExtraJoin");
    }

    @Test
    void generate_conditionOnReverseJoin_shouldFindAppropriateConditionSource() {
        assertGeneratedContentMatches("referenceBackwardWithCondition");
    }

    @Test
    void generate_joinWhenFutureHasExplicitJoin_shouldCreateAppropriateJoinType() {
        assertGeneratedContentMatches("referenceWithFutureExplicitJoin");
    }

    @Test
    void generate_queryWithLookup_shouldGenerateLookupResolverAndQuery() {
        assertGeneratedContentMatches("queryWithLookup");
    }

    @Test
    void generate_queryWithComplexLookup_shouldGenerateLookupResolversAndQueries() {
        assertGeneratedContentMatches("queryWithLookupMultipleParameters");
    }

    @Test
    void generate_queryThatReturnsInterface_shouldCreateResolver() {
        assertGeneratedContentMatches("queryReturningInterface");
        assertThat(getLogMessagesWithLevelWarn()).isEmpty();
    }

    private Set<String> getLogMessagesWithLevelWarn() {
        return logWatcher.list.stream()
                .filter(it -> it.getLevel() == Level.WARN)
                .map(ILoggingEvent::getFormattedMessage)
                .collect(Collectors.toSet());
    }

    @Test
    void generate_queryWithSorting_shouldCreateQueryResolverWithSortingSupport() {
        assertGeneratedContentMatches("queryWithSorting");
    }

    @Test
    void generate_queryWithPaginationAndSorting_shouldCreateQueryResolverWithPaginationAndSortingSupport() {
        assertGeneratedContentMatches("queryWithPaginationAndSorting");
    }
}
