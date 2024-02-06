package fake.code.generated.resolvers.mutation;

import fake.code.generated.queries.mutation.EditCustomerWithCustomerResponseDBQueries;
import fake.code.generated.queries.query.CustomerDBQueries;
import fake.code.generated.transform.InputTransformer;
import fake.graphql.example.api.EditCustomerWithCustomerResponseMutationResolver;
import fake.graphql.example.model.Customer;
import fake.graphql.example.model.EditInput;
import fake.graphql.example.model.EditResponseWithCustomer;
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

public class EditCustomerWithCustomerResponseGeneratedResolver implements EditCustomerWithCustomerResponseMutationResolver {
    @Inject
    DSLContext ctx;

    @Inject
    private CustomerDBQueries customerDBQueries;

    @Inject
    private EditCustomerWithCustomerResponseDBQueries editCustomerWithCustomerResponseDBQueries;

    @Override
    public CompletableFuture<EditResponseWithCustomer> editCustomerWithCustomerResponse(
            EditInput input, DataFetchingEnvironment env) throws Exception {
        var ctx = ResolverHelpers.selectContext(env, this.ctx);
        var select = new SelectionSet(env.getSelectionSet());

        var transform = new InputTransformer(env, ctx);

        var inputRecord = transform.editInputToJOOQRecord(input, "input");

        var rowsUpdated = editCustomerWithCustomerResponseDBQueries.editCustomerWithCustomerResponse(ctx, inputRecord);
        var inputRecordCustomer = getEditResponseWithCustomerCustomer(ctx, inputRecord, select);

        var editResponseWithCustomer = new EditResponseWithCustomer();
        editResponseWithCustomer.setCustomer(inputRecordCustomer);

        return CompletableFuture.completedFuture(editResponseWithCustomer);
    }

    private Customer getEditResponseWithCustomerCustomer(DSLContext ctx, CustomerRecord idContainer,
            SelectionSet select) {
        if (!select.contains("customer") || idContainer == null) {
            return null;
        }

        var nodes = customerDBQueries.loadCustomerByIdsAsNode(ctx, Set.of(idContainer.getId()), select.withPrefix("customer"));
        return nodes.values().stream().findFirst().orElse(null);
    }
}