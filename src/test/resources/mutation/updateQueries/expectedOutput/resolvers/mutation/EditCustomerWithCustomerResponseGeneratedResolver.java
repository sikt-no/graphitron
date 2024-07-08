package fake.code.generated.resolvers.mutation;

import fake.code.generated.queries.mutation.EditCustomerWithCustomerResponseDBQueries;
import fake.code.generated.queries.query.CustomerDBQueries;
import fake.code.generated.transform.RecordTransformer;
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
import no.sikt.graphitron.jooq.generated.testdata.tables.records.CustomerRecord;
import org.jooq.DSLContext;

public class EditCustomerWithCustomerResponseGeneratedResolver implements EditCustomerWithCustomerResponseMutationResolver {
    @Inject
    DSLContext ctx;

    @Override
    public CompletableFuture<EditResponseWithCustomer> editCustomerWithCustomerResponse(
            EditInput input, DataFetchingEnvironment env) throws Exception {
        var transform = new RecordTransformer(env, this.ctx);

        var inputRecord = transform.editInputToJOOQRecord(input, "input");

        var rowsUpdated = EditCustomerWithCustomerResponseDBQueries.editCustomerWithCustomerResponse(transform.getCtx(), inputRecord);
        var inputRecordCustomer = getEditResponseWithCustomerCustomer(transform.getCtx(), inputRecord, transform.getSelect());

        var editResponseWithCustomer = new EditResponseWithCustomer();
        editResponseWithCustomer.setCustomer(inputRecordCustomer);

        return CompletableFuture.completedFuture(editResponseWithCustomer);
    }

    private Customer getEditResponseWithCustomerCustomer(DSLContext ctx, CustomerRecord idContainer,
            SelectionSet select) {
        if (!select.contains("customer") || idContainer == null) {
            return null;
        }

        return CustomerDBQueries.loadCustomerByIdsAsNode(ctx, Set.of(idContainer.getId()), select.withPrefix("customer")).values().stream().findFirst().orElse(null);
    }
}
