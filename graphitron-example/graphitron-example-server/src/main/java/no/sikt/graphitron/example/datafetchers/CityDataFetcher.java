package no.sikt.graphitron.example.datafetchers;

import graphql.schema.DataFetcher;
import no.sikt.graphitron.example.generated.graphitron.model.*;
import no.sikt.graphql.NodeIdStrategy;
import no.sikt.graphql.helpers.resolvers.DataFetcherHelper;
import no.sikt.graphql.helpers.resolvers.ResolverHelpers;

import java.util.List;
import java.util.concurrent.CompletableFuture;

public class CityDataFetcher {
    public static DataFetcher<CompletableFuture<List<Address>>> addresses(
            NodeIdStrategy nodeIdStrategy) {
        return env -> {
            var city = ((City) env.getSource());
            return new DataFetcherHelper(env).load(
                    city.getAddressesKey(),
                    (ctx, resolverKeys, selectionSet) -> CityDBQueries.addressesForCity(ctx, nodeIdStrategy, resolverKeys, selectionSet));
        } ;
    }

    public static DataFetcher<CompletableFuture<CityAddressesPaginatedConnection>> addressesPaginated(
            NodeIdStrategy nodeIdStrategy) {
        return env -> {
            var _args = env.getArguments();
            var city = ((City) env.getSource());
            var first = ((Integer) _args.get("first"));
            var after = ((String) _args.get("after"));
            int pageSize = ResolverHelpers.getPageSize(first, 1000, 100);
            return new DataFetcherHelper(env).loadPaginatedMany(
                    city.getAddressesPaginatedKey(), pageSize, 1000,
                    (ctx, resolverKeys, selectionSet) -> CityDBQueries.addressesPaginatedForCity(ctx, nodeIdStrategy, resolverKeys, pageSize, after, selectionSet),
                    (ctx, resolverKeys) -> CityDBQueries.countAddressesPaginatedForCity(ctx, nodeIdStrategy, resolverKeys),
                    (connection) ->  {
                        var edges = connection.getEdges().stream().map(it -> new CityAddressesPaginatedConnectionEdge(it.getCursor() == null ? null : it.getCursor().getValue(), it.getNode())).toList();
                        var page = connection.getPageInfo();
                        var graphPage = new PageInfo(page.isHasPreviousPage(), page.isHasNextPage(), page.getStartCursor() == null ? null : page.getStartCursor().getValue(), page.getEndCursor() == null ? null : page.getEndCursor().getValue());
                        return new CityAddressesPaginatedConnection(edges, graphPage, connection.getNodes(), connection.getTotalCount());
                    }
            );
        } ;
    }
}
