package fake.code.generated.resolvers.query;

import fake.code.generated.queries.QueryDBQueries;
import fake.graphql.example.model.DummyEnum;
import fake.graphql.example.model.DummyInput;
import fake.graphql.example.model.DummyType;
import graphql.schema.DataFetcher;
import java.lang.Boolean;
import java.lang.Integer;
import java.lang.String;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import no.sikt.graphql.helpers.resolvers.DataFetcherHelper;
import no.sikt.graphql.helpers.resolvers.ResolverHelpers;

public class QueryGeneratedDataFetcher {
    public static DataFetcher<CompletableFuture<DummyType>> query() {
        return env -> {
            String id = env.getArgument("id");
            String str = env.getArgument("str");
            Boolean bool = env.getArgument("bool");
            Integer i = env.getArgument("i");
            DummyEnum e = env.getArgument("e");
            DummyInput in = ResolverHelpers.transformDTO(env.getArgument("in"), DummyInput.class);
            List<String> idList = env.getArgument("idList");
            List<DummyInput> inList = ResolverHelpers.transformDTOList(env.getArgument("inList"), DummyInput.class);

            return new DataFetcherHelper(env).load(
                    (ctx, selectionSet) -> QueryDBQueries.queryForQuery(ctx, id, str, bool, i, e, in, idList, inList, selectionSet)
            );
        };
    }

    public static DataFetcher<CompletableFuture<DummyType>> queryNonNullable() {
        return env -> {
            String id = env.getArgument("id");
            String str = env.getArgument("str");
            Boolean bool = env.getArgument("bool");
            Integer i = env.getArgument("i");
            DummyEnum e = env.getArgument("e");
            DummyInput in = ResolverHelpers.transformDTO(env.getArgument("in"), DummyInput.class);
            List<String> idList = env.getArgument("idList");
            List<DummyInput> inList = ResolverHelpers.transformDTOList(env.getArgument("inList"), DummyInput.class);

            return new DataFetcherHelper(env).load(
                    (ctx, selectionSet) -> QueryDBQueries.queryNonNullableForQuery(ctx, id, str, bool, i, e, in, idList, inList, selectionSet)
            );
        };
    }
}