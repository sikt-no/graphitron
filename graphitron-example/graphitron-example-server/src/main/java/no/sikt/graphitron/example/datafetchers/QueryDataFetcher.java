package no.sikt.graphitron.example.datafetchers;

import graphql.schema.DataFetcher;
import no.sikt.graphitron.example.generated.graphitron.model.Address;
import no.sikt.graphql.helpers.resolvers.DataFetcherHelper;

import java.util.concurrent.CompletableFuture;

public class QueryDataFetcher {
    public static DataFetcher<CompletableFuture<String>> helloWorld() {
        return env -> new DataFetcherHelper(env).load(
                (ctx, selectionSet) -> { return "Hello, World!";});
    }

    public static DataFetcher<CompletableFuture<Address>> addressExample() {
        return env -> new DataFetcherHelper(env).load(
                (ctx, selectionSet) -> {
                    return new Address(
                            null,
                            "1234",
                            "Street 1A",
                            "District",
                            null,
                            "ZIP1234",
                            "98765432");
                });
    }
}
