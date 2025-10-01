package no.sikt.graphitron.example.server;

import com.apollographql.federation.graphqljava.Federation;
import graphql.ExecutionInput;
import graphql.GraphQL;
import graphql.execution.ExecutionStrategy;
import graphql.schema.idl.TypeRuntimeWiring;
import io.agroal.api.AgroalDataSource;
import jakarta.inject.Inject;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;
import no.sikt.graphitron.example.datafetchers.QueryDataFetcher;
import no.sikt.graphitron.example.exceptionhandling.SchemaBasedErrorStrategyImpl;
import no.sikt.graphitron.example.generated.graphitron.exception.GeneratedExceptionStrategyConfiguration;
import no.sikt.graphitron.example.generated.graphitron.exception.GeneratedExceptionToErrorMappingProvider;
import no.sikt.graphitron.example.generated.graphitron.graphitron.Graphitron;
import no.sikt.graphitron.servlet.GraphitronServlet;
import no.sikt.graphql.NodeIdStrategy;
import no.sikt.graphql.exception.DataAccessExceptionMapperImpl;
import no.sikt.graphql.exception.ExceptionHandlingBuilder;
import org.jetbrains.annotations.NotNull;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;

import java.io.Serial;

@WebServlet(name = "GraphqlServlet", urlPatterns = {"graphql/*"}, loadOnStartup = 1)
public class GraphqlServlet extends GraphitronServlet {
    @Serial
    private static final long serialVersionUID = 1L;
    private final AgroalDataSource dataSource;

    @Inject
    public GraphqlServlet(AgroalDataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Inject
    public NodeIdStrategy nodeIdStrategy;

    @Override
    protected GraphQL getSchema(HttpServletRequest request) {
        var registry = Graphitron.getTypeRegistry();
        var newWiring = Graphitron.getRuntimeWiringBuilder(nodeIdStrategy);


        newWiring.type(
                TypeRuntimeWiring.newTypeWiring("Query")
                        .dataFetcher("helloWorld", QueryDataFetcher.helloWorld())
        );

        newWiring.type(
                TypeRuntimeWiring.newTypeWiring("City")
                        .dataFetcher("addressExample", QueryDataFetcher.addressExample())
        );
        var schema = Federation
                .transform(registry, newWiring.build())
                .setFederation2(true)
                .build();


        var executionStrategy = getCustomExecutionStrategy();
        return GraphQL.newGraphQL(schema)
                .queryExecutionStrategy(executionStrategy)
                .mutationExecutionStrategy(executionStrategy).build();
    }

    private static @NotNull ExecutionStrategy getCustomExecutionStrategy() {
        var dataAccessMapper = new DataAccessExceptionMapperImpl();
        return new ExceptionHandlingBuilder()
                .withDataAccessMapper(dataAccessMapper)
                .withSchemaBasedStrategy(new SchemaBasedErrorStrategyImpl(
                        new GeneratedExceptionStrategyConfiguration(),
                        new GeneratedExceptionToErrorMappingProvider(),
                        dataAccessMapper
                ))
                .build();
    }

    @Override
    protected ExecutionInput buildExecutionInput(ExecutionInput.Builder builder) {
        DSLContext ctx = DSL.using(dataSource, SQLDialect.POSTGRES);
        return builder.localContext(ctx).build();
    }
}
