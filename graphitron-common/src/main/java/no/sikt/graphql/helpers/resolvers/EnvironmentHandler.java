package no.sikt.graphql.helpers.resolvers;

import graphql.schema.DataFetchingEnvironment;
import graphql.schema.DataFetchingFieldSelectionSet;
import no.sikt.graphql.MultitenantGraphitronContext;
import no.sikt.graphql.helpers.selection.ConnectionSelectionSet;
import no.sikt.graphql.helpers.selection.SelectionSet;
import org.dataloader.BatchLoaderEnvironment;
import org.jooq.DSLContext;
import org.jooq.Row;

import java.util.*;

import static org.apache.commons.lang3.StringUtils.capitalize;

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
    protected final Map<String, Map<String, Row>> nextKeys;
    protected final String dataloaderName;

    public EnvironmentHandler(DataFetchingEnvironment env) {
        this.env = env;
        String tenantPrefix = "";
        if (env.getGraphQlContext().hasKey("DSLContext")) {
            localContext = new HashMap<>();
            dslContext = env.getGraphQlContext().get("DSLContext");
            nextKeys = Map.of();
        } else if (env.getGraphQlContext().hasKey("multitenantGraphitronContext")) {
            MultitenantGraphitronContext c = env.getGraphQlContext().get("multitenantGraphitronContext");
            Object lc = env.getLocalContext();
            tenantPrefix = c.getTenantId(lc) + ":";
            dslContext = c.getDslContext(lc);
            localContext = new HashMap<>();
            nextKeys = Map.of();
        } else if (env.getLocalContext() instanceof DSLContext ctx) {
            localContext = new HashMap<>();
            dslContext = ctx;
            nextKeys = Map.of();
        } else {
            throw new IllegalStateException("Can't find DSLContext");
        }

        select = new SelectionSet(getSelectionSetsFromEnvironment(env));
        connectionSelect = new ConnectionSelectionSet(getSelectionSetsFromEnvironment(env));
        arguments = flattenArgumentKeys(env.getArguments());
        executionPath = env.getExecutionStepInfo().getPath().toString();
        dataloaderName = String.format("%s%sFor%s", tenantPrefix, capitalize(env.getField().getName()), env.getExecutionStepInfo().getObjectType().getName());
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

    public Map<String, Map<String, Row>> getNextKeys() {
        return nextKeys;
    }

    public Map<String, Row> getNextKeyFor(String fieldName) {
        return Optional.of(nextKeys.get(fieldName)).orElse(Map.of());
    }

    public Set<Row> getNextKeySet(String fieldName) {
        return new HashSet<>(getNextKeyFor(fieldName).values());
    }

    public String getDataloaderName() {
        return dataloaderName;
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
