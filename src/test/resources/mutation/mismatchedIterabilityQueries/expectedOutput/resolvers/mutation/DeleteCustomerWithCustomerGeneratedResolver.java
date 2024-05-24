package fake.code.generated.resolvers.mutation;

import fake.code.generated.queries.mutation.DeleteCustomerWithCustomerDBQueries;
import fake.code.generated.queries.query.CustomerDBQueries;
import fake.code.generated.transform.RecordTransformer;
import fake.graphql.example.api.DeleteCustomerWithCustomerMutationResolver;
import fake.graphql.example.model.Customer;
import fake.graphql.example.model.EditInput;
import fake.graphql.example.model.ListedResponse;
import fake.graphql.example.model.Result;
import graphql.schema.DataFetchingEnvironment;
import java.lang.Exception;
import java.lang.Override;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import javax.inject.Inject;
import no.fellesstudentsystem.graphql.helpers.selection.SelectionSet;
import no.fellesstudentsystem.graphql.helpers.resolvers.ResolverHelpers;
import no.sikt.graphitron.jooq.generated.testdata.tables.records.CustomerRecord;
import org.jooq.DSLContext;

public class DeleteCustomerWithCustomerGeneratedResolver implements DeleteCustomerWithCustomerMutationResolver {
    @Inject
    DSLContext ctx;

    @Inject
    private CustomerDBQueries customerDBQueries;

    @Inject
    private DeleteCustomerWithCustomerDBQueries deleteCustomerWithCustomerDBQueries;

    @Override
    public CompletableFuture<ListedResponse> deleteCustomerWithCustomer(EditInput input,
            DataFetchingEnvironment env) throws Exception {
        var ctx = ResolverHelpers.selectContext(env, this.ctx);
        var select = new SelectionSet(env.getSelectionSet());

        var transform = new RecordTransformer(env, this.ctx);

        var inputRecord = transform.editInputToJOOQRecord(input, "input");

        var rowsUpdated = deleteCustomerWithCustomerDBQueries.deleteCustomerWithCustomer(ctx, inputRecord);
        var inputRecordCustomer = getResultCustomer(ctx, inputRecord, select);

        var listedResponse = new ListedResponse();

        var result = new Result();
        result.setCustomer(inputRecordCustomer);
        listedResponse.setResults(List.of(result));

        return CompletableFuture.completedFuture(listedResponse);
    }

    private Customer getResultCustomer(DSLContext ctx, CustomerRecord idContainer,
            SelectionSet select) {
        if (!select.contains("results/customer") || idContainer == null) {
            return null;
        }

        return customerDBQueries.loadCustomerByIdsAsNode(ctx, Set.of(idContainer.getId()), select.withPrefix("results/customer")).values().stream().findFirst().orElse(null);
    }
}