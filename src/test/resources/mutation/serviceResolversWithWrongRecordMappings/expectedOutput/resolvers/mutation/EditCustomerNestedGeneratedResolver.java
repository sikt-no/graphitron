package fake.code.generated.resolvers.mutation;

import fake.code.generated.queries.query.CustomerDBQueries;
import fake.code.generated.transform.RecordTransformer;
import fake.graphql.example.api.EditCustomerNestedMutationResolver;
import fake.graphql.example.model.EditCustomerResponse;
import fake.graphql.example.model.EditInputLevel1;
import graphql.schema.DataFetchingEnvironment;
import java.lang.Exception;
import java.lang.Override;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import javax.inject.Inject;
import no.fellesstudentsystem.graphitron.services.TestCustomerService;
import no.fellesstudentsystem.graphql.helpers.resolvers.ResolverHelpers;
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
        var transform = new RecordTransformer(env, ctx);

        var inputRecord = transform.editInputLevel1ToJavaRecord(input, "input");

        var editCustomerNested = testCustomerService.editCustomerWithRecordInputsList(inputRecord);

        var editCustomerResponse = new EditCustomerResponse();

        if (editCustomerNested != null && transform.getSelect().contains("customer")) {
            editCustomerResponse.setCustomer(customerDBQueries.loadCustomerByIdsAsNode(ctx, editCustomerNested.stream().map(it -> it.getId()).collect(Collectors.toSet()), transform.getSelect().withPrefix("customer")).values().stream().collect(Collectors.toList()));
        }

        return CompletableFuture.completedFuture(editCustomerResponse);
    }
}
