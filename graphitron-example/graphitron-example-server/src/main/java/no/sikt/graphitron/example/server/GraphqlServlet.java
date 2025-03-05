package no.sikt.graphitron.example.server;

import graphql.ExecutionInput;
import graphql.GraphQL;
import graphql.schema.idl.SchemaGenerator;
import graphql.schema.idl.SchemaParser;
import io.agroal.api.AgroalDataSource;
import jakarta.inject.Inject;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;
import no.sikt.graphitron.example.generated.graphitron.wiring.Wiring;
import no.sikt.graphitron.servlet.GraphitronServlet;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

@WebServlet(name = "GraphqlServlet", urlPatterns = {"graphql/*"}, loadOnStartup = 1)
public class GraphqlServlet extends GraphitronServlet {

    private final static String SCHEMA_NAME = "graphql/schema.graphqls";
    private final AgroalDataSource dataSource;

    @Inject
    public GraphqlServlet(AgroalDataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    protected GraphQL getSchema(HttpServletRequest request) {
        var typeRegistry = new SchemaParser().parse(removeDirectives(readGraphQLSchemaAsString()));
        var schemaGenerator = new SchemaGenerator();
        var schema = schemaGenerator.makeExecutableSchema(typeRegistry, Wiring.getRuntimeWiring());
        return GraphQL.newGraphQL(schema).build();
    }

    @Override
    protected ExecutionInput buildExecutionInput(ExecutionInput.Builder builder) {
        DSLContext ctx = DSL.using(dataSource, SQLDialect.POSTGRES);
        return builder.localContext(ctx).build();
    }

    private String readGraphQLSchemaAsString() {
        try (InputStream resourceAsStream = getClass().getClassLoader().getResourceAsStream(GraphqlServlet.SCHEMA_NAME)) {
            return new String(Objects.requireNonNull(resourceAsStream).readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Removes directives from a GraphQL string.
     * TODO This is a temporary solution to remove applied directives from the schema.
     */
    private static String removeDirectives(String graphqlString) {
        String regex = "\\s*@\\w+(\\([^)]*\\))?";
        return graphqlString.replaceAll(regex, "");
    }
}
