package fake.code.generated.resolvers.mutation;

import fake.code.generated.queries.mutation.EditCustomerWithCustomer2DBQueries;
import fake.code.generated.queries.query.CustomerDBQueries;
import fake.graphql.example.package.api.EditCustomerWithCustomer2MutationResolver;
import fake.graphql.example.package.model.Customer;
import fake.graphql.example.package.model.EditInput;
import fake.graphql.example.package.model.Result;
import graphql.schema.DataFetchingEnvironment;
import java.lang.Exception;
import java.lang.Override;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import javax.inject.Inject;
import no.fellesstudentsystem.graphql.helpers.arguments.Arguments;
import no.fellesstudentsystem.graphql.helpers.selection.SelectionSet;
import no.sikt.graphitron.jooq.generated.testdata.tables.records.CustomerRecord;
import org.jooq.DSLContext;

public class EditCustomerWithCustomer2GeneratedResolver implements EditCustomerWithCustomer2MutationResolver {
    @Inject
    DSLContext ctx;

    @Inject
    private CustomerDBQueries customerDBQueries;

    @Inject
    private EditCustomerWithCustomer2DBQueries editCustomerWithCustomer2DBQueries;

    @Override
    public CompletableFuture<Result> editCustomerWithCustomer2(EditInput input,
            DataFetchingEnvironment env) throws Exception {
        var ctx = env.getLocalContext() == null ? this.ctx : (DSLContext) env.getLocalContext();
        var select = new SelectionSet(env.getSelectionSet());
        var flatArguments = Arguments.flattenArgumentKeys(env.getArguments());

        var inputRecord = new CustomerRecord();
        inputRecord.attach(ctx.configuration());

        if (input != null) {
            if (flatArguments.contains("input/email")) {
                inputRecord.setEmail(input.getEmail());
            }
            if (flatArguments.contains("input/id")) {
                inputRecord.setId(input.getId());
            }
            if (flatArguments.contains("input/firstName")) {
                inputRecord.setFirstName(input.getFirstName());
            }
        }

        var rowsUpdated = editCustomerWithCustomer2DBQueries.editCustomerWithCustomer2(ctx, inputRecord);
        var inputRecordCustomer = getResultCustomer(ctx, inputRecord, select);

        var result = new Result();
        result.setCustomer(inputRecordCustomer);

        return CompletableFuture.completedFuture(result);
    }

    private Customer getResultCustomer(DSLContext ctx, CustomerRecord idContainer,
            SelectionSet select) {
        if (!select.contains("customer") || idContainer == null) {
            return null;
        }

        var nodes = customerDBQueries.loadCustomerByIdsAsNode(ctx, Set.of(idContainer.getId()), select.withPrefix("customer"));
        return nodes.values().stream().findFirst().orElse(null);
    }
}