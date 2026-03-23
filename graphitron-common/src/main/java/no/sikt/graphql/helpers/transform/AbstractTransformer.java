package no.sikt.graphql.helpers.transform;

import graphql.GraphQLError;
import graphql.schema.DataFetchingEnvironment;
import no.sikt.graphql.exception.ValidationViolationGraphQLException;
import no.sikt.graphql.helpers.resolvers.EnvironmentHandler;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.List;

public abstract class AbstractTransformer extends EnvironmentHandler {
    protected final HashSet<GraphQLError> _iv_validationErrors = new HashSet<>();

    public AbstractTransformer(DataFetchingEnvironment env) {
        super(env);
    }

    public void validate() {
        if (!_iv_validationErrors.isEmpty()) {
            throw new ValidationViolationGraphQLException(_iv_validationErrors);
        }
    }

    public void warningIfArgumentSetMismatchForJooqRecordInput(List<?> inputList, String path) {
        if (inputList.size() > 1) {
            var logger = LoggerFactory.getLogger(AbstractTransformer.class);
            try {
                var firstArgumentSet = getArgumentsForIndex(path, 0);
                for (int _iv_argIterator = 1; _iv_argIterator < inputList.size(); _iv_argIterator++) {
                    var nextArgumentSet = getArgumentsForIndex(path, _iv_argIterator);
                    if (firstArgumentSet.size() != nextArgumentSet.size() || !firstArgumentSet.containsAll(nextArgumentSet)) {
                        logger.warn("Different argument set for a list of jOOQ record inputs. If this is a generated UPDATE or UPSERT mutation, this query would have caused fields to be nulled out in Graphitron versions v7.1.4 to v8.8.0.");
                        return;
                    }
                }
            } catch(Exception e) {
                logger.warn("Checking argument set failed.");
            }
        }
    }
}
