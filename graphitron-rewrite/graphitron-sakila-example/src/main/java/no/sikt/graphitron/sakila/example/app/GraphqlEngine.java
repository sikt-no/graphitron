package no.sikt.graphitron.sakila.example.app;

import graphql.GraphQL;
import graphql.schema.GraphQLSchema;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import no.sikt.graphitron.generated.Graphitron;

@ApplicationScoped
public class GraphqlEngine {

    private GraphQL graphql;

    @PostConstruct
    void init() {
        GraphQLSchema schema = Graphitron.buildSchema(b -> {});
        this.graphql = GraphQL.newGraphQL(schema).build();
    }

    public GraphQL get() {
        return graphql;
    }
}
