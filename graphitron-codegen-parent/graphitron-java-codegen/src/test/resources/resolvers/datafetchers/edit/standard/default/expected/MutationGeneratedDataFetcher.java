package fake.code.generated.resolvers.mutation;

import fake.code.generated.queries.MutationDBQueries;
import fake.code.generated.transform.RecordTransformer;
import fake.graphql.example.model.CustomerInputTable;
import graphql.schema.DataFetcher;
import java.lang.String;
import java.util.concurrent.CompletableFuture;
import no.sikt.graphql.helpers.resolvers.ResolverHelpers;

public class MutationGeneratedDataFetcher {
    public static DataFetcher<CompletableFuture<String>> mutation() {
        return env -> {
            var _args = env.getArguments();
            var in = ResolverHelpers.transformDTO(_args.get("in"), CustomerInputTable.class);
            var transform = new RecordTransformer(env);

            var inRecord = transform.customerInputTableToJOOQRecord(in, "in");

            var mutation = MutationDBQueries.mutationForMutation(transform.getCtx(), inRecord);

            return CompletableFuture.completedFuture(inRecord.getId());
        };
    }
}
