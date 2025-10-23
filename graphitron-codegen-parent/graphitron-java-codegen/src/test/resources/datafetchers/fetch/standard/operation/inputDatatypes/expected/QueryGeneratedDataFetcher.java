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
        return _iv_env -> {
            String id = _iv_env.getArgument("id");
            String str = _iv_env.getArgument("str");
            Boolean bool = _iv_env.getArgument("bool");
            Integer i = _iv_env.getArgument("i");
            DummyEnum e = _iv_env.getArgument("e");
            DummyInput in = ResolverHelpers.transformDTO(_iv_env.getArgument("in"), DummyInput.class);
            List<String> idList = _iv_env.getArgument("idList");
            List<DummyInput> inList = ResolverHelpers.transformDTOList(_iv_env.getArgument("inList"), DummyInput.class);

            return new DataFetcherHelper(_iv_env).load(
                    (_iv_ctx, _iv_selectionSet) -> QueryDBQueries.queryForQuery(_iv_ctx, id, str, bool, i, e, in, idList, inList, _iv_selectionSet)
            );
        };
    }

    public static DataFetcher<CompletableFuture<DummyType>> queryNonNullable() {
        return _iv_env -> {
            String id = _iv_env.getArgument("id");
            String str = _iv_env.getArgument("str");
            Boolean bool = _iv_env.getArgument("bool");
            Integer i = _iv_env.getArgument("i");
            DummyEnum e = _iv_env.getArgument("e");
            DummyInput in = ResolverHelpers.transformDTO(_iv_env.getArgument("in"), DummyInput.class);
            List<String> idList = _iv_env.getArgument("idList");
            List<DummyInput> inList = ResolverHelpers.transformDTOList(_iv_env.getArgument("inList"), DummyInput.class);

            return new DataFetcherHelper(_iv_env).load(
                    (_iv_ctx, _iv_selectionSet) -> QueryDBQueries.queryNonNullableForQuery(_iv_ctx, id, str, bool, i, e, in, idList, inList, _iv_selectionSet)
            );
        };
    }
}