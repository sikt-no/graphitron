package no.sikt.graphql.helpers.query;

import java.util.HashMap;

public class DBQueryResult <T> {
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
}
