package no.sikt.graphitron.example.server;

import com.apollographql.federation.graphqljava._Any;
import com.apollographql.federation.graphqljava._FieldSet;
import graphql.ExecutionInput;
import graphql.GraphQL;
import graphql.scalars.ExtendedScalars;
import graphql.schema.GraphQLScalarType;
import graphql.schema.idl.SchemaGenerator;
import graphql.schema.idl.TypeRuntimeWiring;
import io.agroal.api.AgroalDataSource;
import jakarta.inject.Inject;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;
import no.sikt.graphitron.example.datafetchers.QueryDataFetcher;
import no.sikt.graphitron.example.generated.graphitron.graphitron.Graphitron;
import no.sikt.graphitron.servlet.GraphitronServlet;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;

import java.util.List;

@WebServlet(name = "GraphqlServlet", urlPatterns = {"graphql/*"}, loadOnStartup = 1)
public class GraphqlServlet extends GraphitronServlet {
    private final AgroalDataSource dataSource;
    private final List<GraphQLScalarType> scalars = List.of(
            ExtendedScalars.GraphQLBigDecimal,
            _FieldSet.type,
            _Any.type
    );

    @Inject
    public GraphqlServlet(AgroalDataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    protected GraphQL getSchema(HttpServletRequest request) {
        var registry = Graphitron.getTypeRegistry();
        var newWiring = Graphitron.getRuntimeWiringBuilder();
        newWiring.type(
                TypeRuntimeWiring.newTypeWiring("Query")
                        .dataFetcher("helloWorld", QueryDataFetcher.helloWorld())
        );

        newWiring.type(
                TypeRuntimeWiring.newTypeWiring("City")
                        .dataFetcher("addressExample", QueryDataFetcher.addressExample())
        );
        scalars.forEach(newWiring::scalar);

        var schema = new SchemaGenerator().makeExecutableSchema(registry, newWiring.build());
        return GraphQL.newGraphQL(schema).build();
    }

    @Override
    protected ExecutionInput buildExecutionInput(ExecutionInput.Builder builder) {
        DSLContext ctx = DSL.using(dataSource, SQLDialect.POSTGRES);
        return builder.localContext(ctx).build();
    }
}
