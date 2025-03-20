package no.sikt.graphql.helpers.query;

import graphql.execution.DataFetcherResult;
import no.sikt.graphql.naming.LocalContextNames;
import org.jooq.DSLContext;
import org.jooq.Row;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

public class DBQueryResult<T> {
    private final T data;

    // TODO: Change inner map to Map<Row, Row> after phasing out graphql-codegen
    private final Map<String, Map<String, Row>> nextKeys;

    public DBQueryResult(T data) {
        this.data = data;
        this.nextKeys = new HashMap<>();
    }

    public DBQueryResult(T data, Map<String, Map<String, Row>> nextKeys) {
        this.data = data;
        this.nextKeys = nextKeys;
    }

    public T getData() {
        return data;
    }

    public Map<String, Map<String, Row>> getNextKeys() {
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
