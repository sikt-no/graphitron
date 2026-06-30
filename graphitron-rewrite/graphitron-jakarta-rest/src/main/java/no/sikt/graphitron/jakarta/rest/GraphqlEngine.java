package no.sikt.graphitron.jakarta.rest;

import graphql.GraphQL;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Holds the cached {@link GraphQL} engine at application scope. graphql-java's {@code GraphQL} is
 * immutable and thread-safe once built, so it is assembled once from the consumer's
 * {@link GraphitronApplication#engineBuilder()} and reused for every request.
 */
@ApplicationScoped
public class GraphqlEngine {

    @Inject
    GraphitronApplication application;

    private GraphQL graphql;

    @PostConstruct
    void init() {
        this.graphql = application.engineBuilder().build();
    }

    /** @return the shared, immutable engine */
    public GraphQL get() {
        return graphql;
    }
}
