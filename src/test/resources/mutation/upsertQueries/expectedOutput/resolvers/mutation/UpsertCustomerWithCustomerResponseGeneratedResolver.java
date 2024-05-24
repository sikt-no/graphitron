package fake.code.generated.resolvers.mutation;

import fake.code.generated.queries.mutation.UpsertCustomerWithCustomerResponseDBQueries;
import fake.code.generated.queries.query.CustomerDBQueries;
import fake.code.generated.transform.RecordTransformer;
import fake.graphql.example.api.UpsertCustomerWithCustomerResponseMutationResolver;
import fake.graphql.example.model.Customer;
import fake.graphql.example.model.UpsertInput;
import fake.graphql.example.model.UpsertResponseWithCustomer;
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

public class UpsertCustomerWithCustomerResponseGeneratedResolver implements UpsertCustomerWithCustomerResponseMutationResolver {
    @Inject
    DSLContext ctx;

    @Inject
    private CustomerDBQueries customerDBQueries;

    @Inject
    private UpsertCustomerWithCustomerResponseDBQueries upsertCustomerWithCustomerResponseDBQueries;

    @Override
    public CompletableFuture<UpsertResponseWithCustomer> upsertCustomerWithCustomerResponse(
            UpsertInput input, DataFetchingEnvironment env) throws Exception {
        var ctx = ResolverHelpers.selectContext(env, this.ctx);
        var select = new SelectionSet(env.getSelectionSet());

        var transform = new RecordTransformer(env, this.ctx);

        var inputRecord = transform.upsertInputToJOOQRecord(input, "input");

        var rowsUpdated = upsertCustomerWithCustomerResponseDBQueries.upsertCustomerWithCustomerResponse(ctx, inputRecord);
        var inputRecordCustomer = getUpsertResponseWithCustomerCustomer(ctx, inputRecord, select);

        var upsertResponseWithCustomer = new UpsertResponseWithCustomer();
        upsertResponseWithCustomer.setCustomer(inputRecordCustomer);

        return CompletableFuture.completedFuture(upsertResponseWithCustomer);
    }

    private Customer getUpsertResponseWithCustomerCustomer(DSLContext ctx,
            CustomerRecord idContainer, SelectionSet select) {
        if (!select.contains("customer") || idContainer == null) {
            return null;
        }

        return customerDBQueries.loadCustomerByIdsAsNode(ctx, Set.of(idContainer.getId()), select.withPrefix("customer")).values().stream().findFirst().orElse(null);
    }
}