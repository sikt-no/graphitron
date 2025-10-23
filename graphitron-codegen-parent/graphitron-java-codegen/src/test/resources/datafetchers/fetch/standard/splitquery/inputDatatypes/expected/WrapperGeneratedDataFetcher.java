package fake.code.generated.resolvers.query;

import fake.code.generated.queries.WrapperDBQueries;
import fake.graphql.example.model.DummyEnum;
import fake.graphql.example.model.DummyInput;
import fake.graphql.example.model.DummyType;
import fake.graphql.example.model.Wrapper;
import graphql.schema.DataFetcher;
import java.lang.Boolean;
import java.lang.Integer;
import java.lang.String;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import no.sikt.graphql.helpers.resolvers.DataFetcherHelper;
import no.sikt.graphql.helpers.resolvers.ResolverHelpers;

public class WrapperGeneratedDataFetcher {
    public static DataFetcher<CompletableFuture<DummyType>> query() {
        return _iv_env -> {
            Wrapper wrapper = _iv_env.getSource();
            String id = _iv_env.getArgument("id");
            String str = _iv_env.getArgument("str");
            Boolean bool = _iv_env.getArgument("bool");
            Integer i = _iv_env.getArgument("i");
            DummyEnum e = _iv_env.getArgument("e");
            DummyInput in = ResolverHelpers.transformDTO(_iv_env.getArgument("in"), DummyInput.class);
            List<String> idList = _iv_env.getArgument("idList");
            List<DummyInput> inList = ResolverHelpers.transformDTOList(_iv_env.getArgument("inList"), DummyInput.class);

            return new DataFetcherHelper(_iv_env).load(
                    wrapper.getQueryKey(),
                    (_iv_ctx, _iv_keys, _iv_selectionSet) -> WrapperDBQueries.queryForWrapper(_iv_ctx, _iv_keys, id, str, bool, i, e, in, idList, inList, _iv_selectionSet)
            );
        };
    }

    public static DataFetcher<CompletableFuture<DummyType>> queryNonNullable() {
        return _iv_env -> {
            Wrapper wrapper = _iv_env.getSource();
            String id = _iv_env.getArgument("id");
            String str = _iv_env.getArgument("str");
            Boolean bool = _iv_env.getArgument("bool");
            Integer i = _iv_env.getArgument("i");
            DummyEnum e = _iv_env.getArgument("e");
            DummyInput in = ResolverHelpers.transformDTO(_iv_env.getArgument("in"), DummyInput.class);
            List<String> idList = _iv_env.getArgument("idList");
            List<DummyInput> inList = ResolverHelpers.transformDTOList(_iv_env.getArgument("inList"), DummyInput.class);

            return new DataFetcherHelper(_iv_env).load(
                    wrapper.getQueryNonNullableKey(),
                    (_iv_ctx, _iv_keys, _iv_selectionSet) -> WrapperDBQueries.queryNonNullableForWrapper(_iv_ctx, _iv_keys, id, str, bool, i, e, in, idList, inList, _iv_selectionSet)
            );
        };
    }
}