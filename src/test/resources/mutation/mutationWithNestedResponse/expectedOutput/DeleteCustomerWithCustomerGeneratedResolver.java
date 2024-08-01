package fake.code.generated.resolvers.mutation;

import fake.code.generated.queries.mutation.DeleteCustomerWithCustomerDBQueries;
import fake.code.generated.queries.query.CustomerDBQueries;
import fake.code.generated.transform.RecordTransformer;
import fake.graphql.example.api.DeleteCustomerWithCustomerMutationResolver;
import fake.graphql.example.model.Customer;
import fake.graphql.example.model.EditInput;
import fake.graphql.example.model.Response;
import fake.graphql.example.model.Result;
import graphql.schema.DataFetchingEnvironment;
import java.lang.Exception;
import java.lang.Override;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import javax.inject.Inject;
import no.fellesstudentsystem.graphql.helpers.selection.SelectionSet;
import no.sikt.graphitron.jooq.generated.testdata.tables.records.CustomerRecord;
import org.jooq.DSLContext;

public class DeleteCustomerWithCustomerGeneratedResolver implements DeleteCustomerWithCustomerMutationResolver {
    @Inject
    DSLContext ctx;

    @Override
    public CompletableFuture<Response> deleteCustomerWithCustomer(EditInput input,
            DataFetchingEnvironment env) throws Exception {
        var transform = new RecordTransformer(env, this.ctx);

        var inputRecord = transform.editInputToJOOQRecord(input, "input");

        var rowsUpdated = DeleteCustomerWithCustomerDBQueries.deleteCustomerWithCustomer(transform.getCtx(), inputRecord);
        var inputRecordCustomer = getResultCustomer(transform, inputRecord, transform.getSelect());

        var response = new Response();

        var result = new Result();
        result.setCustomer(inputRecordCustomer);
        response.setResults(result);

        return CompletableFuture.completedFuture(response);
    }

    private Customer getResultCustomer(RecordTransformer transform, CustomerRecord idContainer,
            SelectionSet select) {
        if (!select.contains("results/customer") || idContainer == null) {
            return null;
        }

        return CustomerDBQueries.loadCustomerByIdsAsNode(transform.getCtx(), Set.of(idContainer.getId()), select.withPrefix("results/customer")).values().stream().findFirst().orElse(null);
    }
}
