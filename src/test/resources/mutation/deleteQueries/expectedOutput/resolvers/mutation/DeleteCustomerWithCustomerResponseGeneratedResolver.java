package fake.code.generated.resolvers.mutation;

import fake.code.generated.queries.mutation.DeleteCustomerWithCustomerResponseDBQueries;
import fake.code.generated.queries.query.CustomerDBQueries;
import fake.code.generated.transform.InputTransformer;
import fake.graphql.example.api.DeleteCustomerWithCustomerResponseMutationResolver;
import fake.graphql.example.model.Customer;
import fake.graphql.example.model.DeleteInput;
import fake.graphql.example.model.DeleteResponseWithCustomer;
import graphql.schema.DataFetchingEnvironment;
import java.lang.Exception;
import java.lang.Override;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import javax.inject.Inject;
import no.fellesstudentsystem.graphql.helpers.selection.SelectionSet;
import no.fellesstudentsystem.graphql.helpers.resolvers.ResolverHelpers;
import no.sikt.graphitron.jooq.generated.testdata.tables.records.CustomerRecord;
import org.jooq.DSLContext;

public class DeleteCustomerWithCustomerResponseGeneratedResolver implements DeleteCustomerWithCustomerResponseMutationResolver {
    @Inject
    DSLContext ctx;

    @Inject
    private CustomerDBQueries customerDBQueries;

    @Inject
    private DeleteCustomerWithCustomerResponseDBQueries deleteCustomerWithCustomerResponseDBQueries;

    @Override
    public CompletableFuture<DeleteResponseWithCustomer> deleteCustomerWithCustomerResponse(
            DeleteInput input, DataFetchingEnvironment env) throws Exception {
        var ctx = ResolverHelpers.selectContext(env, this.ctx);
        var select = new SelectionSet(env.getSelectionSet());

        var transform = new InputTransformer(env, ctx);

        var inputRecord = transform.deleteInputToJOOQRecord(input, "input");

        var rowsUpdated = deleteCustomerWithCustomerResponseDBQueries.deleteCustomerWithCustomerResponse(ctx, inputRecord);
        var inputRecordCustomer = getDeleteResponseWithCustomerCustomer(ctx, inputRecord, select);

        var deleteResponseWithCustomer = new DeleteResponseWithCustomer();
        deleteResponseWithCustomer.setCustomer(inputRecordCustomer);

        return CompletableFuture.completedFuture(deleteResponseWithCustomer);
    }

    private Customer getDeleteResponseWithCustomerCustomer(DSLContext ctx,
            CustomerRecord idContainer, SelectionSet select) {
        if (!select.contains("customer") || idContainer == null) {
            return null;
        }

        var nodes = customerDBQueries.loadCustomerByIdsAsNode(ctx, Set.of(idContainer.getId()), select.withPrefix("customer"));
        return nodes.values().stream().findFirst().orElse(null);
    }
}