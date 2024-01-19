package no.fellesstudentsystem.graphitron;

import graphql.execution.ExecutionStepInfo;
import graphql.execution.MergedField;
import graphql.execution.ResultPath;
import graphql.language.Field;
import graphql.schema.*;
import no.fellesstudentsystem.graphql.exception.ValidationViolationGraphQLException;
import no.fellesstudentsystem.graphql.helpers.resolvers.DataLoaders;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.util.List;

import static graphql.Scalars.GraphQLInt;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class DataLoadersTest {
    private static final List<Object>
            STRING_KEY_0 = List.of("A0", "A1", "A2"),
            STRING_KEY_1 = List.of("B0", "B1", "B2"),
            INT_KEY = List.of(0, 1, 2);
    private final static GraphQLFieldDefinition FIELD = GraphQLFieldDefinition
            .newFieldDefinition()
            .name("testField")
            .type(GraphQLInt)
            .build();

    private final static DataFetchingEnvironment ENV = DataFetchingEnvironmentImpl
            .newDataFetchingEnvironment()
            .fieldDefinition(FIELD)
            .mergedField(MergedField.newMergedField().addField(Field.newField().name("testField").build()).build())
            .executionStepInfo(
                    ExecutionStepInfo
                            .newExecutionStepInfo()
                            .path(ResultPath.rootPath())
                            .type(GraphQLInt)
                            .fieldDefinition(FIELD)
                            .build()
            )
            .build();

    @Test
    public void mergeKeysEmptyKeysResultsInEmptyResult() {
        assertThat(DataLoaders.mergeKeys(List.of(), ENV)).isEmpty();
    }

    @Test
    public void mergeKeysBuildsKeysForSingleKeySet() {
        assertThat(DataLoaders.mergeKeys(List.of(STRING_KEY_0), ENV)).isEqualTo(STRING_KEY_0);
    }

    @Test
    public void mergeKeysBuildsKeysForIntegers() {
        assertThat(DataLoaders.mergeKeys(List.of(INT_KEY), ENV)).isEqualTo(List.of("0", "1", "2"));
    }

    @Test
    public void mergeKeysBuildsKeysMultipleKeySets() {
        assertThat(DataLoaders.mergeKeys(List.of(STRING_KEY_0, STRING_KEY_1, INT_KEY), ENV)).isEqualTo(List.of("A0,B0,0", "A1,B1,1", "A2,B2,2"));
    }

    @Test
    @Disabled
    public void mergeKeysThrowsErrorIfSetsDoNotMatchInSize() {
        assertThatThrownBy(() -> DataLoaders.mergeKeys(List.of(STRING_KEY_0, List.of(0)), ENV))
                .isInstanceOf(ValidationViolationGraphQLException.class);
                //.hasMessage("Keys sets have differing lengths. For this type of query, each key field is required to be an array of equal length.");
    }
}
