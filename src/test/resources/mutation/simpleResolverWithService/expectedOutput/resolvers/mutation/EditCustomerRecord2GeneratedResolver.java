package fake.code.generated.resolvers.mutation;

import fake.code.generated.queries.query.CustomerDBQueries;
import fake.graphql.example.package.api.EditCustomerRecord2MutationResolver;
import fake.graphql.example.package.model.Customer;
import fake.graphql.example.package.model.EditResponse2;
import graphql.schema.DataFetchingEnvironment;
import java.lang.Exception;
import java.lang.Override;
import java.lang.String;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import javax.inject.Inject;
import no.fellesstudentsystem.graphitron.services.TestCustomerService;
import no.fellesstudentsystem.graphql.helpers.selection.SelectionSet;
import org.jooq.DSLContext;

public class EditCustomerRecord2GeneratedResolver implements EditCustomerRecord2MutationResolver {
    @Inject
    DSLContext ctx;

    @Inject
    private CustomerDBQueries customerDBQueries;

    @Override
    public CompletableFuture<EditResponse2> editCustomerRecord2(List<String> id,
            DataFetchingEnvironment env) throws Exception {
        var ctx = env.getLocalContext() == null ? this.ctx : (DSLContext) env.getLocalContext();
        var testCustomerService = new TestCustomerService(ctx);
        var select = new SelectionSet(env.getSelectionSet());
        var editCustomerRecord2Result = testCustomerService.editCustomerRecord2(id);
        var editResponse2Customers = getEditResponse2Customers(ctx, editCustomerRecord2Result, select);

        var editResponse2 = new EditResponse2();
        editResponse2.setId2(editCustomerRecord2Result.stream().map(itId2 -> itId2.getId()).collect(Collectors.toList()));
        editResponse2.setCustomers(new ArrayList<>(editResponse2Customers.values()));

        return CompletableFuture.completedFuture(editResponse2);
    }

    private Map<String, Customer> getEditResponse2Customers(DSLContext ctx,
            List<no.sikt.graphitron.jooq.generated.testdata.tables.records.CustomerRecord> idContainer,
            SelectionSet select) {
        if (!select.contains("customers") || idContainer == null) {
            return Map.of();
        }

        var ids = idContainer.stream().map(it -> it.getId()).collect(Collectors.toSet());
        return customerDBQueries.loadCustomerByIdsAsNode(ctx, ids, select.withPrefix("customers"));
    }
}