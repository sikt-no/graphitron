package no.fellesstudentsystem.graphql.helpers;

import graphql.schema.DataFetchingEnvironment;
import graphql.schema.DataFetchingFieldSelectionSet;
import org.dataloader.BatchLoaderEnvironment;

import java.util.List;
import java.util.stream.Collectors;

public class EnvironmentUtils {
        public static List<DataFetchingFieldSelectionSet> getSelectionSetsFromEnvironment(BatchLoaderEnvironment loaderEnvironment) {
            return ((List<DataFetchingEnvironment>) (List<?>) loaderEnvironment.getKeyContextsList()).stream()
                    .map(DataFetchingEnvironment::getSelectionSet)
                    .collect(Collectors.toList());
        }

        public static DataFetchingFieldSelectionSet getSelectionSetsFromEnvironment(DataFetchingEnvironment env) {
            return env.getSelectionSet();
        }
}
