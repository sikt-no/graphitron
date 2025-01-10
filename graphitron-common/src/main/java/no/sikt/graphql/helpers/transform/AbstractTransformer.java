package no.sikt.graphql.helpers.transform;

import graphql.GraphQLError;
import graphql.schema.DataFetchingEnvironment;
import no.sikt.graphql.exception.ValidationViolationGraphQLException;
import no.sikt.graphql.helpers.arguments.Arguments;
import no.sikt.graphql.helpers.selection.SelectionSet;
import org.jooq.DSLContext;

import java.util.HashSet;
import java.util.Set;

public abstract class AbstractTransformer {
    protected final DSLContext ctx;

    protected final DataFetchingEnvironment env;

    protected final Set<String> arguments;

    protected final SelectionSet select;

    protected final HashSet<GraphQLError> validationErrors = new HashSet<>();

    public AbstractTransformer(DataFetchingEnvironment env) {
        this.env = env;
        this.ctx = env.getLocalContext();
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
