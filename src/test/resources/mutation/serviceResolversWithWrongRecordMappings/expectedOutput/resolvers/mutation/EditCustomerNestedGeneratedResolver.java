package fake.code.generated.resolvers.mutation;

import fake.code.generated.queries.query.CustomerDBQueries;
import fake.code.generated.transform.InputTransformer;
import fake.graphql.example.api.EditCustomerNestedMutationResolver;
import fake.graphql.example.model.Customer;
import fake.graphql.example.model.EditCustomerResponse;
import fake.graphql.example.model.EditInputLevel1;
import graphql.schema.DataFetchingEnvironment;
import java.lang.Exception;
import java.lang.Override;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import javax.inject.Inject;
import no.fellesstudentsystem.graphitron.services.TestCustomerService;
import no.fellesstudentsystem.graphql.helpers.resolvers.ResolverHelpers;
import no.fellesstudentsystem.graphql.helpers.selection.SelectionSet;
import no.sikt.graphitron.jooq.generated.testdata.tables.records.CustomerRecord;
import org.jooq.DSLContext;

public class EditCustomerNestedGeneratedResolver implements EditCustomerNestedMutationResolver {
    @Inject
    DSLContext ctx;

    @Inject
    private CustomerDBQueries customerDBQueries;

    @Override
    public CompletableFuture<EditCustomerResponse> editCustomerNested(EditInputLevel1 input,
                                                                      DataFetchingEnvironment env) throws Exception {
        var ctx = ResolverHelpers.selectContext(env, this.ctx);
        var testCustomerService = new TestCustomerService(ctx);
        var select = new SelectionSet(env.getSelectionSet());

        var transform = new InputTransformer(env, ctx);

        var inputRecord = transform.editInputLevel1ToJavaRecord(input, "input");


        var editCustomerNestedResult = testCustomerService.editCustomerWithRecordInputs(inputRecord);
        var editCustomerResponseCustomer = getEditCustomerResponseCustomer(ctx, editCustomerNestedResult, select);

        var editCustomerResponse = new EditCustomerResponse();
        editCustomerResponse.setCustomer(editCustomerResponseCustomer);

        return CompletableFuture.completedFuture(editCustomerResponse);
    }

    private Customer getEditCustomerResponseCustomer(DSLContext ctx, CustomerRecord idContainer,
                                                     SelectionSet select) {
        if (!select.contains("customer") || idContainer == null) {
            return null;
        }

        var nodes = customerDBQueries.loadCustomerByIdsAsNode(ctx, Set.of(idContainer.getId()), select.withPrefix("customer"));
        return nodes.values().stream().findFirst().orElse(null);
    }
}

