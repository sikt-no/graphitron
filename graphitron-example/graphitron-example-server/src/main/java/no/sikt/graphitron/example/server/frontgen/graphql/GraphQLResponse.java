package no.sikt.graphitron.example.server.frontgen.graphql;

import java.util.List;
import java.util.Map;

public class GraphQLResponse<T> {
    private T data;
    private List<Map<String, Object>> errors;

    public T getData() {
        return data;
    }

    public void setData(T data) {
        this.data = data;
    }

    public List<Map<String, Object>> getErrors() {
        return errors;
    }

    public void setErrors(List<Map<String, Object>> errors) {
        this.errors = errors;
    }

    public boolean hasErrors() {
        return errors != null && !errors.isEmpty();
    }

    public String getErrorMessage() {
        if (!hasErrors()) {
            return "";
        }
        return errors.stream()
                .map(error -> error.getOrDefault("message", "Unknown error").toString())
                .reduce((a, b) -> a + "; " + b)
                .orElse("Unknown error");
    }
}