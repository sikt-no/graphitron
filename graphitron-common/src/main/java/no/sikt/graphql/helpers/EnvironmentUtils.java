package no.sikt.graphql.helpers;

import graphql.language.InlineFragment;
import graphql.language.TypeName;
import graphql.schema.DataFetchingEnvironment;
import graphql.schema.DataFetchingFieldSelectionSet;
import org.dataloader.BatchLoaderEnvironment;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Helper class for generated code that helps simplify the extraction of selection sets from various query/mutation environments.
 */
public class EnvironmentUtils {
    public static List<DataFetchingFieldSelectionSet> getSelectionSetsFromEnvironment(BatchLoaderEnvironment loaderEnvironment) {
        return ((List<DataFetchingEnvironment>) (List<?>) loaderEnvironment.getKeyContextsList()).stream()
                .map(DataFetchingEnvironment::getSelectionSet)
                .collect(Collectors.toList());
    }

    public static DataFetchingFieldSelectionSet getSelectionSetsFromEnvironment(DataFetchingEnvironment env) {
        return env.getSelectionSet();
    }

    public static Optional<String> getTargetTypeFromEnvironment(DataFetchingEnvironment env) {
        var selectedTypes = env.getMergedField().getFields().get(0).getSelectionSet().getSelectionsOfType(InlineFragment.class);
        if (selectedTypes.isEmpty()) {
            return Optional.empty();
        }

        if (selectedTypes.size() > 1) {
            return Optional.empty();
        }

        return selectedTypes.stream().findFirst().map(InlineFragment::getTypeCondition).map(TypeName::getName);
    }
}
