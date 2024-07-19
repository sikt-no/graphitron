package fake.code.generated.resolvers.mutation;

import fake.code.generated.queries.mutation.EditCustomerWithCustomer2DBQueries;
import fake.code.generated.queries.query.CustomerDBQueries;
import fake.code.generated.transform.RecordTransformer;
import fake.graphql.example.api.EditCustomerWithCustomer2MutationResolver;
import fake.graphql.example.model.Customer;
import fake.graphql.example.model.EditInput;
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

public class EditCustomerWithCustomer2GeneratedResolver implements EditCustomerWithCustomer2MutationResolver {
    @Inject
    DSLContext ctx;

    @Override
    public CompletableFuture<Result> editCustomerWithCustomer2(EditInput input,
            DataFetchingEnvironment env) throws Exception {
        var transform = new RecordTransformer(env, this.ctx);

        var inputRecord = transform.editInputToJOOQRecord(input, "input");

        var rowsUpdated = EditCustomerWithCustomer2DBQueries.editCustomerWithCustomer2(transform.getCtx(), inputRecord);
        var inputRecordCustomer = getResultCustomer(transform, inputRecord, transform.getSelect());

        var result = new Result();
        result.setCustomer(inputRecordCustomer);

        return CompletableFuture.completedFuture(result);
    }

    private Customer getResultCustomer(RecordTransformer transform, CustomerRecord idContainer,
            SelectionSet select) {
        if (!select.contains("customer") || idContainer == null) {
            return null;
        }

        return CustomerDBQueries.loadCustomerByIdsAsNode(transform.getCtx(), Set.of(idContainer.getId()), select.withPrefix("customer")).values().stream().findFirst().orElse(null);
    }
}
