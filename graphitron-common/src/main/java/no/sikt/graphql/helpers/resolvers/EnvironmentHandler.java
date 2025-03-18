package no.sikt.graphql.helpers.resolvers;

import graphql.schema.DataFetchingEnvironment;
import graphql.schema.DataFetchingFieldSelectionSet;
import no.sikt.graphql.helpers.selection.ConnectionSelectionSet;
import no.sikt.graphql.helpers.selection.SelectionSet;
import no.sikt.graphql.naming.LocalContextNames;
import org.dataloader.BatchLoaderEnvironment;
import org.jooq.DSLContext;

import java.util.*;

/**
 * Helper class for generated code that helps simplify the extraction of required data from a DataFetchingEnvironment.
 */
public class EnvironmentHandler {
    protected final DataFetchingEnvironment env;
    protected final Map<String, Object> localContext;
    protected final String executionPath;
    protected final DSLContext dslContext;
    protected final SelectionSet select, connectionSelect;
    protected final Set<String> arguments;
    protected final List<List<String>> nextKeys;

    public EnvironmentHandler(DataFetchingEnvironment env) {
        this.env = env;
        if (false) { // Disabled until everything uses context as a map.
            localContext = Optional.of((Map<String, Object>) env.getLocalContext()).orElse(new HashMap<>());
            dslContext = (DSLContext) localContext.get(LocalContextNames.DSL_CONTEXT.getName());  // Must exist.
            nextKeys = Optional.of((List<List<String>>) localContext.get(LocalContextNames.NEXT_KEYS.getName())).orElse(List.of());
        } else {
            localContext = new HashMap<>();
            dslContext = env.getLocalContext();
            nextKeys = List.of();
        }

        select = new SelectionSet(getSelectionSetsFromEnvironment(env));
        connectionSelect = new ConnectionSelectionSet(getSelectionSetsFromEnvironment(env));
        executionPath = env.getExecutionStepInfo().getPath().toString();
        arguments = flattenArgumentKeys(env.getArguments());
    }

    public DataFetchingEnvironment getEnv() {
        return env;
    }

    public Map<String, Object> getLocalContext() {
        return localContext;
    }

    public DSLContext getCtx() {
        return dslContext;
    }

    public String getExecutionPath() {
        return executionPath;
    }

    public SelectionSet getSelect() {
        return select;
    }

    public SelectionSet getConnectionSelect() {
        return connectionSelect;
    }

    public Set<String> getArguments() {
        return arguments;
    }

    public List<List<String>> getNextKeys() {
        return nextKeys;
    }

    protected static List<DataFetchingFieldSelectionSet> getSelectionSetsFromEnvironment(BatchLoaderEnvironment loaderEnvironment) {
        return ((List<DataFetchingEnvironment>) (List<?>) loaderEnvironment.getKeyContextsList()).stream()
                .map(DataFetchingEnvironment::getSelectionSet)
                .toList();
    }

    private static DataFetchingFieldSelectionSet getSelectionSetsFromEnvironment(DataFetchingEnvironment env) {
        return env.getSelectionSet();
    }

    private static Set<String> flattenArgumentKeys(Map<String, Object> arguments) {
        return flattenArgumentKeys(arguments, "");
    }

    private static Set<String> flattenArgumentKeys(Map<String, Object> arguments, String path) {
        var result = new HashSet<String>();
        for (var arg : arguments.entrySet()) {
            var key = arg.getKey();
            var value = arg.getValue();
            var nextPath = path.isEmpty() ? key : path + "/" + key;
            result.add(nextPath);

            if (value instanceof Map) {
                result.addAll(flattenArgumentKeys((Map<String, Object>) value, nextPath));
            }

            if (value instanceof List<?> listValue) {
                var first = listValue.stream().findFirst();
                if (first.isPresent() && first.get() instanceof Map) {
                    result.addAll(flattenArgumentKeys((Map<String, Object>) first.get(), nextPath));
                }
            }
        }

        return result;
    }
}
