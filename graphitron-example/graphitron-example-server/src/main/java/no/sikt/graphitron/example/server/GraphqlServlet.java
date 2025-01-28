package no.sikt.graphitron.example.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import graphql.ExecutionInput;
import graphql.ExecutionResult;
import graphql.GraphQL;
import graphql.kickstart.tools.GraphQLResolver;
import graphql.kickstart.tools.SchemaParser;
import graphql.kickstart.tools.SchemaParserOptions;
import graphql.scalars.ExtendedScalars;
import graphql.schema.GraphQLSchema;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.ws.rs.ClientErrorException;
import org.dataloader.DataLoaderRegistry;
import org.jooq.DSLContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

@Dependent
@WebServlet(name = "GraphqlServlet", urlPatterns = {"graphql/*"}, loadOnStartup = 1)
public class GraphqlServlet extends HttpServlet {

    Logger logger = LoggerFactory.getLogger(GraphqlServlet.class);

    protected ObjectMapper objectMapper;

    public GraphqlServlet() {
        objectMapper = JsonMapper.builder()
                .configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false)
                .build();
    }

    private final static String SCHEMA_NAME = "graphql/schema.graphqls";

    @Inject
    Instance<GraphQLResolver<?>> resolvers;
    @Inject
    DSLContext dslContext;

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        handleGraphqlRequest(req, resp);
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        handleGraphqlRequest(req, resp);
    }

    protected void handleGraphqlRequest(HttpServletRequest request, HttpServletResponse response) throws IOException {
        var requestedMediaType = GraphqlHttpRequest.parseRequestedMediaType(request);

        try {
            var input = GraphqlHttpRequest.parse(request);
            var result = execute(input);
            writeResponse(response, result, requestedMediaType);
        } catch (ClientErrorException e) {
            response.sendError(e.getResponse().getStatus(), e.getMessage());
        } catch (Exception e) {
            logger.error("Internal Server Error!", e);
            response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
        }
    }


    protected void writeResponse(HttpServletResponse response, ExecutionResult result, String requestedMediaType) throws IOException {
        if (result.isDataPresent() || requestedMediaType.equals("application/json")) {
            response.setStatus(HttpServletResponse.SC_OK);
        } else {
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
        }

        response.setContentType(requestedMediaType);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());

        objectMapper.writer()
                .withDefaultPrettyPrinter()
                .writeValue(response.getWriter(), result.toSpecification());
    }

    protected ExecutionResult execute(ExecutionInput executionInput) {
        var schema = createSchema(getResolvers());

        var graphqlBuilder = GraphQL.newGraphQL(schema);

        var graphQL = graphqlBuilder.build();
        var input = executionInput
                .transform(this::addDataloaderRegistry)
                .transform(builder -> builder.localContext(dslContext));
        return graphQL.execute(input);
    }

    protected void addDataloaderRegistry(ExecutionInput.Builder builder) {
        builder.dataLoaderRegistry(new DataLoaderRegistry());
    }

    protected GraphQLSchema createSchema(List<GraphQLResolver<?>> resolvers) {
        var schemaString = removeDirectives(readGraphQLSchemaAsString());
        var options = SchemaParserOptions.newOptions()
                .objectMapperProvider(fieldDefinition -> objectMapper)
                .build();

        return SchemaParser
                .newParser()
                .schemaString(schemaString)
                .resolvers(resolvers)
                .options(options)
                .scalars(ExtendedScalars.Date, ExtendedScalars.DateTime, ExtendedScalars.GraphQLBigDecimal, ExtendedScalars.GraphQLShort, ExtendedScalars.Time)
                .build()
                .makeExecutableSchema();
    }

    protected String readGraphQLSchemaAsString() {
        try (InputStream resourceAsStream = getClass().getClassLoader().getResourceAsStream(GraphqlServlet.SCHEMA_NAME)) {
            return new String(Objects.requireNonNull(resourceAsStream).readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Removes directives from a GraphQL string.
     * TODO This is a temporary solution to remove applied directives from the schema.
     * With applied directives Kickstart fails to parse the schema.
     */
    private static String removeDirectives(String graphqlString) {
        String regex = "\\s*@\\w+(\\([^)]*\\))?";
        return graphqlString.replaceAll(regex, "");
    }

    protected List<GraphQLResolver<?>> getResolvers() {
        return resolvers.stream().collect(Collectors.toList());
    }
}
