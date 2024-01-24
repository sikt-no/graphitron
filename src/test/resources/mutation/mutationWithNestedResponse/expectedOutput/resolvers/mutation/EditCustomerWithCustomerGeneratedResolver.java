package fake.code.generated.resolvers.mutation;

import fake.code.generated.queries.mutation.EditCustomerWithCustomerDBQueries;
import fake.code.generated.queries.query.CustomerDBQueries;
import fake.graphql.example.package.api.EditCustomerWithCustomerMutationResolver;
import fake.graphql.example.package.model.Customer;
import fake.graphql.example.package.model.EditInput;
import fake.graphql.example.package.model.Response;
import fake.graphql.example.package.model.Result;
import graphql.schema.DataFetchingEnvironment;
import java.lang.Exception;
import java.lang.Override;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import javax.inject.Inject;
import no.fellesstudentsystem.graphql.helpers.arguments.Arguments;
import no.fellesstudentsystem.graphql.helpers.selection.SelectionSet;
import no.fellesstudentsystem.graphql.helpers.resolvers.ResolverHelpers;
import no.sikt.graphitron.jooq.generated.testdata.tables.records.CustomerRecord;
import org.jooq.DSLContext;

public class EditCustomerWithCustomerGeneratedResolver implements EditCustomerWithCustomerMutationResolver {
    @Inject
    DSLContext ctx;

    @Inject
    private CustomerDBQueries customerDBQueries;

    @Inject
    private EditCustomerWithCustomerDBQueries editCustomerWithCustomerDBQueries;

    @Override
    public CompletableFuture<Response> editCustomerWithCustomer(EditInput input,
            DataFetchingEnvironment env) throws Exception {
        var ctx = ResolverHelpers.selectContext(env, this.ctx);
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

        var rowsUpdated = editCustomerWithCustomerDBQueries.editCustomerWithCustomer(ctx, inputRecord);
        var inputRecordCustomer = getResultCustomer(ctx, inputRecord, select);

        var response = new Response();

        var result = new Result();
        result.setCustomer(inputRecordCustomer);
        response.setResults(result);

        return CompletableFuture.completedFuture(response);
    }

    private Customer getResultCustomer(DSLContext ctx, CustomerRecord idContainer,
            SelectionSet select) {
        if (!select.contains("results/customer") || idContainer == null) {
            return null;
        }

        var nodes = customerDBQueries.loadCustomerByIdsAsNode(ctx, Set.of(idContainer.getId()), select.withPrefix("results/customer"));
        return nodes.values().stream().findFirst().orElse(null);
    }
}