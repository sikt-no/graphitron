package no.fellesstudentsystem.graphql.helpers.transform;

import graphql.GraphQLError;
import graphql.schema.DataFetchingEnvironment;
import no.fellesstudentsystem.graphql.exception.ValidationViolationGraphQLException;
import no.fellesstudentsystem.graphql.helpers.arguments.Arguments;
import no.fellesstudentsystem.graphql.helpers.resolvers.ResolverHelpers;
import no.fellesstudentsystem.graphql.helpers.selection.SelectionSet;
import org.jooq.DSLContext;

import java.util.HashSet;
import java.util.Set;

public abstract class AbstractTransformer {
    protected final DSLContext ctx;

    protected final DataFetchingEnvironment env;

    protected final Set<String> arguments;

    protected final SelectionSet select;

    protected final HashSet<GraphQLError> validationErrors = new HashSet<>();

    public AbstractTransformer(DataFetchingEnvironment env, DSLContext ctx) {
        this.env = env;
        this.ctx = ResolverHelpers.selectContext(env, ctx);
        select = new SelectionSet(env.getSelectionSet());
        arguments = Arguments.flattenArgumentKeys(env.getArguments());
    }

    public void validate() {
        if (!validationErrors.isEmpty()) {
            throw new ValidationViolationGraphQLException(validationErrors);
        }
    }

    public DSLContext getCtx() {
        return ctx;
    }

    public DataFetchingEnvironment getEnv() {
        return env;
    }

    public SelectionSet getSelect() {
        return select;
    }

    public Set<String> getArguments() {
        return arguments;
    }
}
