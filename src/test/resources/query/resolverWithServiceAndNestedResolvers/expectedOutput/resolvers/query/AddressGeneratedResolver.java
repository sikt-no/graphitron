package fake.code.generated.resolvers.query;

import fake.code.generated.transform.RecordTransformer;
import fake.graphql.example.api.AddressResolver;
import fake.graphql.example.model.Address;
import fake.graphql.example.model.City;
import fake.graphql.example.model.CityConnection;
import fake.graphql.example.model.CityConnectionEdge;
import fake.graphql.example.model.PageInfo;
import fake.graphql.example.model.RecordCity;
import fake.graphql.example.model.RecordCityConnection;
import fake.graphql.example.model.RecordCityConnectionEdge;
import graphql.schema.DataFetchingEnvironment;
import java.lang.Exception;
import java.lang.Integer;
import java.lang.Override;
import java.lang.String;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import javax.inject.Inject;
import no.fellesstudentsystem.graphitron.services.TestFetchCityService;
import no.fellesstudentsystem.graphql.helpers.resolvers.ResolverHelpers;
import no.fellesstudentsystem.graphql.helpers.resolvers.ServiceDataFetcher;
import org.jooq.DSLContext;

public class AddressGeneratedResolver implements AddressResolver {
    @Inject
    DSLContext ctx;

    @Override
    public CompletableFuture<City> city(Address address, DataFetchingEnvironment env) throws
            Exception {
        var transform = new RecordTransformer(env, this.ctx);
        var testFetchCityService = new TestFetchCityService(transform.getCtx());

        return new ServiceDataFetcher<>(transform).load(
                "cityForAddress", address.getId(),
                (ids) -> testFetchCityService.city(ids),
                (transform, response) -> transform.cityRecordToGraphType(response, ""));
    }

    @Override
    public CompletableFuture<CityConnection> cityPaginated(Address address, Integer first,
                                                           String after, DataFetchingEnvironment env) throws Exception {
        int pageSize = ResolverHelpers.getPageSize(first, 1000, 100);
        var transform = new RecordTransformer(env, this.ctx);
        var testFetchCityService = new TestFetchCityService(transform.getCtx());

        return new ServiceDataFetcher<>(transform).loadPaginated(
                "cityPaginatedForAddress", address.getId(), pageSize, 1000,
                (ids) -> testFetchCityService.cityPaginated(ids, pageSize, after),
                (ids) -> testFetchCityService.countCityPaginated(ids),
                (it) -> it.getId(),
                (transform, response) -> transform.cityRecordToGraphType(response, ""),
                (connection) ->  {
                    var edges = connection.getEdges().stream().map(it -> CityConnectionEdge.builder().setCursor(it.getCursor() == null ? null : it.getCursor().getValue()).setNode(it.getNode()).build()).collect(Collectors.toList());
                    var page = connection.getPageInfo();
                    var graphPage = PageInfo.builder().setStartCursor(page.getStartCursor() == null ? null : page.getStartCursor().getValue()).setEndCursor(page.getEndCursor() == null ? null : page.getEndCursor().getValue()).setHasNextPage(page.isHasNextPage()).setHasPreviousPage(page.isHasPreviousPage()).build();
                    return CityConnection.builder().setNodes(connection.getNodes()).setEdges(edges).setTotalCount(connection.getTotalCount()).setPageInfo(graphPage).build();
                }
        );
    }

    @Override
    public CompletableFuture<RecordCity> recordCity(Address address, DataFetchingEnvironment env)
            throws Exception {
        var transform = new RecordTransformer(env, this.ctx);
        var testFetchCityService = new TestFetchCityService(transform.getCtx());

        return new ServiceDataFetcher<>(transform).load(
                "recordCityForAddress", address.getId(),
                (ids) -> testFetchCityService.recordCity(ids),
                (transform, response) -> transform.recordCityToGraphType(response, ""));
    }

    @Override
    public CompletableFuture<RecordCityConnection> recordCityPaginated(Address address,
                                                                       Integer first, String after, DataFetchingEnvironment env) throws Exception {
        int pageSize = ResolverHelpers.getPageSize(first, 1000, 100);
        var transform = new RecordTransformer(env, this.ctx);
        var testFetchCityService = new TestFetchCityService(transform.getCtx());

        return new ServiceDataFetcher<>(transform).loadPaginated(
                "recordCityPaginatedForAddress", address.getId(), pageSize, 1000,
                (ids) -> testFetchCityService.recordCityPaginated(ids, pageSize, after),
                (ids) -> testFetchCityService.countRecordCityPaginated(ids),
                (it) -> it.getId(),
                (transform, response) -> transform.recordCityToGraphType(response, ""),
                (connection) ->  {
                    var edges = connection.getEdges().stream().map(it -> RecordCityConnectionEdge.builder().setCursor(it.getCursor() == null ? null : it.getCursor().getValue()).setNode(it.getNode()).build()).collect(Collectors.toList());
                    var page = connection.getPageInfo();
                    var graphPage = PageInfo.builder().setStartCursor(page.getStartCursor() == null ? null : page.getStartCursor().getValue()).setEndCursor(page.getEndCursor() == null ? null : page.getEndCursor().getValue()).setHasNextPage(page.isHasNextPage()).setHasPreviousPage(page.isHasPreviousPage()).build();
                    return RecordCityConnection.builder().setNodes(connection.getNodes()).setEdges(edges).setTotalCount(connection.getTotalCount()).setPageInfo(graphPage).build();
                }
        );
    }
}
