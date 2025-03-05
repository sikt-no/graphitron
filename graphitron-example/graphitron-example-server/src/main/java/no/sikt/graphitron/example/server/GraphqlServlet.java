package no.sikt.graphitron.example.server;

import graphql.ExecutionInput;
import graphql.GraphQL;
import io.agroal.api.AgroalDataSource;
import jakarta.inject.Inject;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;
import no.sikt.graphitron.example.generated.graphitron.graphitron.Graphitron;
import no.sikt.graphitron.servlet.GraphitronServlet;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;

@WebServlet(name = "GraphqlServlet", urlPatterns = {"graphql/*"}, loadOnStartup = 1)
public class GraphqlServlet extends GraphitronServlet {
    private final AgroalDataSource dataSource;

    @Inject
    public GraphqlServlet(AgroalDataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    protected GraphQL getSchema(HttpServletRequest request) {
        return GraphQL.newGraphQL(Graphitron.getSchema()).build();
    }

    @Override
    protected ExecutionInput buildExecutionInput(ExecutionInput.Builder builder) {
        DSLContext ctx = DSL.using(dataSource, SQLDialect.POSTGRES);
        return builder.localContext(ctx).build();
    }
}
