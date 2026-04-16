package no.sikt.graphitron.example.server;

import graphql.ExecutionInput;
import graphql.GraphQL;
import graphql.execution.ExecutionStrategy;
import graphql.schema.GraphQLSchema;
import graphql.schema.idl.TypeRuntimeWiring;
import io.agroal.api.AgroalDataSource;
import jakarta.inject.Inject;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;
import no.fellesstudentsystem.schema_transformer.transform.SchemaFeatureFilter;
import no.sikt.graphitron.example.datafetchers.AddressInterfaceDataFetcher;
import no.sikt.graphitron.example.datafetchers.AddressInterfaceTypeResolver;
import no.sikt.graphitron.example.datafetchers.JooqRecordFetcher.*;
import no.sikt.graphitron.example.datafetchers.QueryDataFetcher;
import no.sikt.graphitron.example.exceptionhandling.SchemaBasedErrorStrategyImpl;
import no.sikt.graphitron.example.generated.graphitron.exception.GeneratedExceptionStrategyConfiguration;
import no.sikt.graphitron.example.generated.graphitron.exception.GeneratedExceptionToErrorMappingProvider;
import no.sikt.graphitron.example.generated.graphitron.graphitron.Graphitron;
import no.sikt.graphitron.servlet.GraphitronServlet;
import no.sikt.graphql.DefaultGraphitronContext;
import no.sikt.graphql.NodeIdStrategy;
import no.sikt.graphql.exception.DataAccessExceptionMapperImpl;
import no.sikt.graphql.exception.ExceptionHandlingBuilder;
import org.jetbrains.annotations.NotNull;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.jooq.impl.DefaultConfiguration;

import java.io.Serial;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import static no.sikt.graphitron.example.datafetchers.JooqRecordFetcher.JooqRecordAliasFetcher;
import static no.sikt.graphitron.example.generated.jooq.Tables.*;

@WebServlet(name = "GraphqlServlet", urlPatterns = {"graphql/*"}, loadOnStartup = 1)
public class GraphqlServlet extends GraphitronServlet {
    @Serial
    private static final long serialVersionUID = 1L;
    private static final String FEATURE_FLAGS_HEADER = "X-Feature-Flags";
    private static final Map<Set<String>, GraphQLSchema> schemaCache = new ConcurrentHashMap<>();
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

        newWiring
                .type("Query", builder ->
                        builder.dataFetcher("singleTableInterfaceNewMapping", AddressInterfaceDataFetcher.singleTableInterfaceNewMapping(nodeIdStrategy)))

                // Type mapping for single table interface
                .type("AddressInterface", builder -> builder.typeResolver(AddressInterfaceTypeResolver.getName()))


                // Type mapping
                .type("AddressImplOne", builder ->
                        builder.typeResolver(AddressInterfaceTypeResolver.getName())
                                .dataFetcher("mostSignificantAddressLine", env -> JooqRecordFieldFetcher.get(env, ADDRESS.ADDRESS_))
                                .dataFetcher("id", env -> JooqRecordAliasFetcher.get(env, "AddressImplOne_id"))
                                .dataFetcher("spokenLanguage", env -> JooqRecordAliasFetcher.get(env, "spokenLanguage"))
                                .dataFetcher("city", AddressInterfaceDataFetcher.city(nodeIdStrategy))
                                .dataFetcher("cityAsSubquery", env -> JooqRecordAliasFetcher.get(env, "cityAsSubquery"))
                )
                .type("AddressImplTwo", builder ->
                        builder.typeResolver(AddressInterfaceTypeResolver.getName())
                                .dataFetcher("mostSignificantAddressLine", env -> JooqRecordFieldFetcher.get(env, ADDRESS.ADDRESS2))
                                .dataFetcher("id", env -> JooqRecordAliasFetcher.get(env, "AddressImplTwo_id"))
                                .dataFetcher("spokenLanguage", env -> JooqRecordAliasFetcher.get(env, "spokenLanguage"))
                                .dataFetcher("city", AddressInterfaceDataFetcher.city(nodeIdStrategy))
                                .dataFetcher("cityAsSubquery", env -> JooqRecordAliasFetcher.get(env, "cityAsSubquery"))
                )
                .type("CityMinimal", builder -> builder
                        .dataFetcher("cityId", env -> JooqRecordFieldFetcher.get(env, CITY.CITY_ID))

                        // Country (altså subquery i subquery) fungerer ikke med alias. Må i så fall bruke indeks :mariopain:
//                        .dataFetcher("country", env -> JooqRecordFieldFetcher.get(env, "country"))

                        // Dette blir ikke gøy å debugge hvis man får feil....
                        .dataFetcher("country", env -> JooqRecordIndexFetcher.get(env, 1))
                )
                .type("CountryMinimal", builder -> builder
                        .dataFetcher("countryId", env -> JooqRecordFieldFetcher.get(env, COUNTRY.COUNTRY_ID))
                );

        /*
        Bekymring: blir wiring riktig når vi kommer fra andre steder i grafen?
         */

        var schema = Graphitron.getFederatedSchema(registry, newWiring, nodeIdStrategy);

        schema = applyFeatureFlags(request, schema);

        var executionStrategy = getCustomExecutionStrategy();
        return GraphQL.newGraphQL(schema)
                .queryExecutionStrategy(executionStrategy)
                .mutationExecutionStrategy(executionStrategy).build();
    }

    private GraphQLSchema applyFeatureFlags(HttpServletRequest request, GraphQLSchema schema) {
        var featureFlagsHeader = request.getHeader(FEATURE_FLAGS_HEADER);
        Set<String> featureFlags = featureFlagsHeader == null || featureFlagsHeader.isEmpty()
                ? Set.of()
                : Arrays.stream(featureFlagsHeader.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toSet());

        return schemaCache.computeIfAbsent(featureFlags,
                flags -> new SchemaFeatureFilter(flags).getFilteredGraphQLSchema(schema));
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
        var config = new DefaultConfiguration();
        config.set(SQLDialect.POSTGRES);
        config.set(dataSource);
        QueryCapturingExecuteListener.getInstanceIfEnabled().ifPresent(config::set);
        DSLContext ctx = DSL.using(config);
        return builder.graphQLContext(Map.of("graphitronContext", new DefaultGraphitronContext(ctx))).build();
    }
}
