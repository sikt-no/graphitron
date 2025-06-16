package no.sikt.graphitron.example.server.frontgen.graphql;

import java.util.Map;

public class GraphQLResponse<T> {
    private T data;
    private Object[] errors;

    public T getData() {
        return data;
    }

    public void setData(T data) {
        this.data = data;
    }

    public Object[] getErrors() {
        return errors;
    }

    public void setErrors(Object[] errors) {
        this.errors = errors;
    }

    public boolean hasErrors() {
        return errors != null && errors.length > 0;
    }
}