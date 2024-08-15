package no.fellesstudentsystem.graphitron;

import no.fellesstudentsystem.graphitron.definitions.interfaces.GenerationTarget;
import no.fellesstudentsystem.graphitron.generators.abstractions.ClassGenerator;
import no.fellesstudentsystem.graphitron.generators.db.fetch.FetchDBClassGenerator;
import no.fellesstudentsystem.graphql.schema.ProcessedSchema;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.util.List;

import static no.fellesstudentsystem.graphitron.TestReferenceSet.*;

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
    void generate_splitQueryAtTypeWithoutTable_shouldFindAppropriateSourceTable() {
        assertGeneratedContentMatches("splitQueryForTypeWithoutTable");
    }
}
