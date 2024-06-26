package fake.code.generated.resolvers.mutation;

import fake.code.generated.queries.mutation.EditCustomerWithCustomerIterableDBQueries;
import fake.code.generated.queries.query.CustomerDBQueries;
import fake.code.generated.transform.RecordTransformer;
import fake.graphql.example.api.EditCustomerWithCustomerIterableMutationResolver;
import fake.graphql.example.model.Customer;
import fake.graphql.example.model.EditInput;
import fake.graphql.example.model.Response;
import fake.graphql.example.model.Result;
import graphql.schema.DataFetchingEnvironment;
import java.lang.Exception;
import java.lang.Override;
import java.lang.String;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import javax.inject.Inject;
import no.fellesstudentsystem.graphql.helpers.selection.SelectionSet;
import no.fellesstudentsystem.graphql.helpers.resolvers.ResolverHelpers;
import no.sikt.graphitron.jooq.generated.testdata.tables.records.CustomerRecord;
import org.jooq.DSLContext;

public class EditCustomerWithCustomerIterableGeneratedResolver implements EditCustomerWithCustomerIterableMutationResolver {
    @Inject
    DSLContext ctx;

    @Override
    public CompletableFuture<Response> editCustomerWithCustomerIterable(List<EditInput> input,
            DataFetchingEnvironment env) throws Exception {
        var ctx = ResolverHelpers.selectContext(env, this.ctx);
        var select = new SelectionSet(env.getSelectionSet());

        var transform = new RecordTransformer(env, this.ctx);

        var inputRecordList = transform.editInputToJOOQRecord(input, "input");

        var rowsUpdated = EditCustomerWithCustomerIterableDBQueries.editCustomerWithCustomerIterable(ctx, inputRecordList);
        var inputRecordCustomer = getResultCustomer(ctx, inputRecordList, select);

        var response = new Response();

        var resultList = new ArrayList<Result>();
        for (var itInputRecordList : inputRecordList) {
            var result = new Result();
            result.setCustomer(inputRecordCustomer.get(itInputRecordList.getId()));
            resultList.add(result);
        }
        response.setResult(resultList.stream().findFirst().orElse(List.of()));

        return CompletableFuture.completedFuture(response);
    }

    private Map<String, Customer> getResultCustomer(DSLContext ctx,
            List<CustomerRecord> idContainer, SelectionSet select) {
        if (!select.contains("result/customer") || idContainer == null) {
            return Map.of();
        }

        return CustomerDBQueries.loadCustomerByIdsAsNode(ctx, idContainer.stream().map(it -> it.getId()).collect(Collectors.toSet()), select.withPrefix("result/customer"));
    }
}
