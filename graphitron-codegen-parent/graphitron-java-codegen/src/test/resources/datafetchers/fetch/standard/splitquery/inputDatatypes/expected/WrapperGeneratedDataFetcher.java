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
            Wrapper _os_wrapper = _iv_env.getSource();
            String _mi_id = _iv_env.getArgument("id");
            String _mi_str = _iv_env.getArgument("str");
            Boolean _mi_bool = _iv_env.getArgument("bool");
            Integer _mi_i = _iv_env.getArgument("i");
            DummyEnum _mi_e = _iv_env.getArgument("e");
            DummyInput _mi_in = ResolverHelpers.transformDTO(_iv_env.getArgument("in"), DummyInput.class);
            List<String> _mi_idList = _iv_env.getArgument("idList");
            List<DummyInput> _mi_inList = ResolverHelpers.transformDTOList(_iv_env.getArgument("inList"), DummyInput.class);

            return new DataFetcherHelper(_iv_env).load(
                    _os_wrapper.getQueryKey(),
                    (_iv_ctx, _iv_keys, _iv_selectionSet) -> WrapperDBQueries.queryForWrapper(_iv_ctx, _iv_keys, _mi_id, _mi_str, _mi_bool, _mi_i, _mi_e, _mi_in, _mi_idList, _mi_inList, _iv_selectionSet)
            );
        };
    }

    public static DataFetcher<CompletableFuture<DummyType>> queryNonNullable() {
        return _iv_env -> {
            Wrapper _os_wrapper = _iv_env.getSource();
            String _mi_id = _iv_env.getArgument("id");
            String _mi_str = _iv_env.getArgument("str");
            Boolean _mi_bool = _iv_env.getArgument("bool");
            Integer _mi_i = _iv_env.getArgument("i");
            DummyEnum _mi_e = _iv_env.getArgument("e");
            DummyInput _mi_in = ResolverHelpers.transformDTO(_iv_env.getArgument("in"), DummyInput.class);
            List<String> _mi_idList = _iv_env.getArgument("idList");
            List<DummyInput> _mi_inList = ResolverHelpers.transformDTOList(_iv_env.getArgument("inList"), DummyInput.class);

            return new DataFetcherHelper(_iv_env).load(
                    _os_wrapper.getQueryNonNullableKey(),
                    (_iv_ctx, _iv_keys, _iv_selectionSet) -> WrapperDBQueries.queryNonNullableForWrapper(_iv_ctx, _iv_keys, _mi_id, _mi_str, _mi_bool, _mi_i, _mi_e, _mi_in, _mi_idList, _mi_inList, _iv_selectionSet)
            );
        };
    }
}