package fake.code.generated.resolvers.mutation;

import fake.code.generated.queries.MutationDBQueries;
import fake.graphql.example.model.CustomerInputTable;
import graphql.schema.DataFetcher;
import java.lang.String;
import java.util.concurrent.CompletableFuture;
import no.sikt.graphql.helpers.resolvers.DataFetcherHelper;
import no.sikt.graphql.helpers.resolvers.ResolverHelpers;

public class MutationGeneratedDataFetcher {
    public static DataFetcher<CompletableFuture<String>> mutation() {
        return env -> {
            CustomerInputTable in = ResolverHelpers.transformDTO(env.getArgument("in"), CustomerInputTable.class);
            return new DataFetcherHelper(env).load((ctx, selectionSet) -> MutationDBQueries.mutationForMutation(ctx, in, selectionSet));
        };
    }
}
