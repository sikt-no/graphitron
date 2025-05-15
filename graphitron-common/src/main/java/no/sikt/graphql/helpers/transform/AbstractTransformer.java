package no.sikt.graphql.helpers.transform;

import graphql.GraphQLError;
import graphql.schema.DataFetchingEnvironment;
import no.sikt.graphql.exception.ValidationViolationGraphQLException;
import no.sikt.graphql.helpers.resolvers.EnvironmentHandler;

import java.util.HashSet;

public abstract class AbstractTransformer extends EnvironmentHandler {
    protected final HashSet<GraphQLError> validationErrors = new HashSet<>();

    public AbstractTransformer(DataFetchingEnvironment env) {
        super(env);
    }

    public void validate() {
        if (!validationErrors.isEmpty()) {
            throw new ValidationViolationGraphQLException(validationErrors);
        }
    }
}
