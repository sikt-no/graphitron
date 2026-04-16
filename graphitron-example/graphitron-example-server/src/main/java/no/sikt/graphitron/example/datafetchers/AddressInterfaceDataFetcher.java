package no.sikt.graphitron.example.datafetchers;

import graphql.schema.DataFetcher;
import no.sikt.graphitron.example.generated.graphitron.model.City;
import no.sikt.graphitron.example.generated.jooq.tables.records.AddressRecord;
import no.sikt.graphql.NodeIdStrategy;
import no.sikt.graphql.helpers.resolvers.DataFetcherHelper;
import org.jooq.Record;
import org.jooq.Result;

import java.util.concurrent.CompletableFuture;

public class AddressInterfaceDataFetcher {

    public static DataFetcher<CompletableFuture<Result<? extends Record>>> singleTableInterfaceNewMapping(NodeIdStrategy _iv_nodeIdStrategy) {
        return _iv_env ->
                new DataFetcherHelper(_iv_env).load((_iv_ctx, _iv_selectionSet) -> AddressInterfaceDBQueries.singleTableInterfaceForQuery(_iv_ctx, _iv_nodeIdStrategy, _iv_selectionSet));

    }

    /*
    Illustrerer hvordan vi kommer oss inn igjen i grafen, så herfra brukes bare DTO
     */
    public static DataFetcher<CompletableFuture<City>> city(NodeIdStrategy _iv_nodeIdStrategy) {
        return _iv_env -> {
            var source = ((Record) _iv_env.getSource());
            assert source != null;
            return new DataFetcherHelper(_iv_env).load(
                    source.get("address_pkey", AddressRecord.class),
                    (_iv_ctx, _iv_keys, _iv_selectionSet) -> AddressInterfaceDBQueries.city(_iv_ctx, _iv_nodeIdStrategy, _iv_keys, _iv_selectionSet)
            );
        } ;
    }
}
