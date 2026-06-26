package no.sikt.graphitron.sakila.example.app;

import graphql.GraphQL;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import no.sikt.graphitron.generated.Graphitron;

@ApplicationScoped
public class GraphqlEngine {

    private GraphQL graphql;

    @PostConstruct
    void init() {
        this.graphql = Graphitron.newGraphQL().build();
    }

    public GraphQL get() {
        return graphql;
    }
}
