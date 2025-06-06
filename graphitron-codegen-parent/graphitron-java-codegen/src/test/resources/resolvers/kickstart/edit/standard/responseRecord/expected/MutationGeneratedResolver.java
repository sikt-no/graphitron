package fake.code.generated.resolvers.mutation;

import fake.code.generated.queries.MutationDBQueries;
import fake.code.generated.queries.CustomerDBQueries;
import fake.code.generated.transform.RecordTransformer;
import fake.graphql.example.api.MutationMutationResolver;
import fake.graphql.example.model.CustomerInputTable;
import fake.graphql.example.model.Response;
import graphql.schema.DataFetchingEnvironment;
import java.lang.Exception;
import java.lang.Override;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

public class MutationGeneratedResolver implements MutationMutationResolver {
    @Override
    public CompletableFuture<Response> mutation(CustomerInputTable in, DataFetchingEnvironment env)
            throws Exception {
        var transform = new RecordTransformer(env);
        var inRecord = transform.customerInputTableToJOOQRecord(in, "in");
        MutationDBQueries.mutationForMutation(transform.getCtx(), inRecord);

        var response = new Response();
        if (inRecord != null && transform.getSelect().contains("customer")) {
            response.setCustomer(CustomerDBQueries.customerForNode(transform.getCtx(), Set.of(inRecord.getId()), transform.getSelect().withPrefix("customer")).values().stream().findFirst().orElse(null));
        }

        return CompletableFuture.completedFuture(response);
    }
}
