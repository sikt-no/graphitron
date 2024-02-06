package fake.code.generated.resolvers.mutation;

import fake.code.generated.queries.mutation.InsertCustomerWithCustomerResponseDBQueries;
import fake.code.generated.queries.query.CustomerDBQueries;
import fake.code.generated.transform.InputTransformer;
import fake.graphql.example.api.InsertCustomerWithCustomerResponseMutationResolver;
import fake.graphql.example.model.Customer;
import fake.graphql.example.model.InsertInput;
import fake.graphql.example.model.InsertResponseWithCustomer;
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

public class InsertCustomerWithCustomerResponseGeneratedResolver implements InsertCustomerWithCustomerResponseMutationResolver {
    @Inject
    DSLContext ctx;

    @Inject
    private CustomerDBQueries customerDBQueries;

    @Inject
    private InsertCustomerWithCustomerResponseDBQueries insertCustomerWithCustomerResponseDBQueries;

    @Override
    public CompletableFuture<InsertResponseWithCustomer> insertCustomerWithCustomerResponse(
            InsertInput input, DataFetchingEnvironment env) throws Exception {
        var ctx = ResolverHelpers.selectContext(env, this.ctx);
        var select = new SelectionSet(env.getSelectionSet());

        var transform = new InputTransformer(env, ctx);

        var inputRecord = transform.insertInputToJOOQRecord(input, "input");

        var rowsUpdated = insertCustomerWithCustomerResponseDBQueries.insertCustomerWithCustomerResponse(ctx, inputRecord);
        var inputRecordCustomer = getInsertResponseWithCustomerCustomer(ctx, inputRecord, select);

        var insertResponseWithCustomer = new InsertResponseWithCustomer();
        insertResponseWithCustomer.setCustomer(inputRecordCustomer);

        return CompletableFuture.completedFuture(insertResponseWithCustomer);
    }

    private Customer getInsertResponseWithCustomerCustomer(DSLContext ctx,
            CustomerRecord idContainer, SelectionSet select) {
        if (!select.contains("customer") || idContainer == null) {
            return null;
        }

        var nodes = customerDBQueries.loadCustomerByIdsAsNode(ctx, Set.of(idContainer.getId()), select.withPrefix("customer"));
        return nodes.values().stream().findFirst().orElse(null);
    }
}