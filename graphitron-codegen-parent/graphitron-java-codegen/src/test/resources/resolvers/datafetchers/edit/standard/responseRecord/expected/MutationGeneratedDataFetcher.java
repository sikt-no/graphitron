package fake.code.generated.resolvers.mutation;

import fake.code.generated.queries.MutationDBQueries;
import fake.code.generated.queries.CustomerDBQueries;
import fake.code.generated.transform.RecordTransformer;
import fake.graphql.example.model.CustomerInputTable;
import fake.graphql.example.model.Response;
import graphql.schema.DataFetcher;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import no.sikt.graphql.helpers.resolvers.ResolverHelpers;

public class MutationGeneratedDataFetcher {
    public static DataFetcher<CompletableFuture<Response>> mutation() {
        return env -> {
            var _args = env.getArguments();
            var in = ResolverHelpers.transformDTO(_args.get("in"), CustomerInputTable.class);
            var transform = new RecordTransformer(env);

            var inRecord = transform.customerInputTableToJOOQRecord(in, "in");

            var mutation = MutationDBQueries.mutationForMutation(transform.getCtx(), inRecord);

            var response = new Response();
            if (inRecord != null && transform.getSelect().contains("customer")) {
                response.setCustomer(CustomerDBQueries.customerForNode(transform.getCtx(), Set.of(inRecord.getId()), transform.getSelect().withPrefix("customer")).values().stream().findFirst().orElse(null));
            }

            return CompletableFuture.completedFuture(response);
        };
    }
}
