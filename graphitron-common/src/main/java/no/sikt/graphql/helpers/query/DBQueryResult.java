package no.sikt.graphql.helpers.query;

import graphql.execution.DataFetcherResult;
import no.sikt.graphql.naming.LocalContextNames;
import org.jooq.DSLContext;

import java.util.HashMap;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

public class DBQueryResult<T> {
    private final T data;
    private final HashMap<String, Object> nextKeys;

    public DBQueryResult(T data) {
        this.data = data;
        this.nextKeys = new HashMap<>();
    }

    public DBQueryResult(T data, HashMap<String, Object> nextKeys) {
        this.data = data;
        this.nextKeys = nextKeys;
    }

    public T getData() {
        return data;
    }

    public HashMap<String, Object> getNextKeys() {
        return nextKeys;
    }

    public <U> DBQueryResult<U> transform(Function<T, U> transform) {
        return new DBQueryResult<>(transform.apply(data));
    }

    public DataFetcherResult<CompletableFuture<T>> asDataFetcherResult(DSLContext context) {
        var newContext = new HashMap<>();
        newContext.put(LocalContextNames.DSL_CONTEXT.getName(), context);
        newContext.put(LocalContextNames.NEXT_KEYS.getName(), nextKeys);
        return DataFetcherResult
                .<CompletableFuture<T>>newResult()
                .data(CompletableFuture.completedFuture(data))
                .localContext(newContext)
                .build();
    }
}
