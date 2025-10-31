package fake.code.generated.resolvers.mutation;

import fake.code.generated.queries.MutationDBQueries;
import fake.code.generated.transform.RecordTransformer;
import fake.graphql.example.model.CustomerInputTable;
import graphql.schema.DataFetcher;
import java.lang.String;
import java.util.concurrent.CompletableFuture;
import no.sikt.graphql.helpers.resolvers.DataFetcherHelper;
import no.sikt.graphql.helpers.resolvers.ResolverHelpers;

public class MutationGeneratedDataFetcher {
    public static DataFetcher<CompletableFuture<String>> mutation() {
        return _iv_env -> {
            CustomerInputTable in = ResolverHelpers.transformDTO(_iv_env.getArgument("in"), CustomerInputTable.class);
            var _iv_transform = new RecordTransformer(_iv_env);

            var inRecord = _iv_transform.customerInputTableToJOOQRecord(in, "in");

            MutationDBQueries.mutationForMutation(_iv_transform.getCtx(), inRecord);
            return new DataFetcherHelper(_iv_env).load((_iv_ctx, _iv_selectionSet) -> MutationDBQueries.mutationForMutation(_iv_ctx, inRecord, _iv_selectionSet));
        };
    }
}
