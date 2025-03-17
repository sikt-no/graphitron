package no.sikt.graphql.naming;

public enum LocalContextNames {
    DSL_CONTEXT("DSLContext"),
    NEXT_KEYS("nextKeys");

    private final String name;

    LocalContextNames(String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }
}
