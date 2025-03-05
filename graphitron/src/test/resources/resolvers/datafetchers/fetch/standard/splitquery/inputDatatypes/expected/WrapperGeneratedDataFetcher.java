package fake.code.generated.resolvers.query;

import fake.code.generated.queries.query.WrapperDBQueries;
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
        return env -> {
            var _args = env.getArguments();
            var wrapper = ((Wrapper) env.getSource());
            var id = ((String) _args.get("id"));
            var str = ((String) _args.get("str"));
            var bool = ((Boolean) _args.get("bool"));
            var i = ((Integer) _args.get("i"));
            var e = ((DummyEnum) _args.get("e"));
            var in = ResolverHelpers.transformDTO(_args.get("in"), DummyInput.class);
            var idList = ((List<String>) _args.get("idList"));
            var inList = ResolverHelpers.transformDTOList(_args.get("inList"), DummyInput.class);
            return new DataFetcherHelper(env).load(
                    "queryForWrapper", wrapper.getId(),
                    (ctx, ids, selectionSet) -> WrapperDBQueries.queryForWrapper(ctx, ids, id, str, bool, i, e, in, idList, inList, selectionSet)
            );
        };
    }

    public static DataFetcher<CompletableFuture<DummyType>> queryNonNullable() {
        return env -> {
            var _args = env.getArguments();
            var wrapper = ((Wrapper) env.getSource());
            var id = ((String) _args.get("id"));
            var str = ((String) _args.get("str"));
            var bool = ((Boolean) _args.get("bool"));
            var i = ((Integer) _args.get("i"));
            var e = ((DummyEnum) _args.get("e"));
            var in = ResolverHelpers.transformDTO(_args.get("in"), DummyInput.class);
            var idList = ((List<String>) _args.get("idList"));
            var inList = ResolverHelpers.transformDTOList(_args.get("inList"), DummyInput.class);
            return new DataFetcherHelper(env).load(
                    "queryNonNullableForWrapper", wrapper.getId(),
                    (ctx, ids, selectionSet) -> WrapperDBQueries.queryNonNullableForWrapper(ctx, ids, id, str, bool, i, e, in, idList, inList, selectionSet)
            );
        };
    }
}
