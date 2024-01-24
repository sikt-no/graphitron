package no.fellesstudentsystem.graphql.helpers.resolvers;

import graphql.schema.DataFetchingEnvironment;
import no.fellesstudentsystem.graphql.helpers.EnvironmentUtils;
import no.fellesstudentsystem.graphql.helpers.selection.SelectionSet;
import org.jetbrains.annotations.NotNull;
import org.jooq.DSLContext;

import java.util.Optional;

public class ResolverHelpers {
    public static int getPageSize(Integer first, int max, int defaultMax) {
        return Optional.ofNullable(first).map(it -> Math.min(max, it)).orElse(defaultMax);
    }

    public static DSLContext selectContext(DataFetchingEnvironment env, DSLContext defaultContext) {
        return env.getLocalContext() == null ? defaultContext : (DSLContext) env.getLocalContext();
    }

    @NotNull
    public static SelectionSet getSelectionSet(DataFetchingEnvironment env) {
        return new SelectionSet(EnvironmentUtils.getSelectionSetsFromEnvironment(env));
    }
}
