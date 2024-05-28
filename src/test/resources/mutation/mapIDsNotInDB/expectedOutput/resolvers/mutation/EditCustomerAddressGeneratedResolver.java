package fake.code.generated.resolvers.mutation;

import fake.code.generated.transform.RecordTransformer;
import fake.graphql.example.api.EditCustomerAddressMutationResolver;
import fake.graphql.example.model.EditAddressInput;
import fake.graphql.example.model.EditAddressResponse;
import graphql.schema.DataFetchingEnvironment;
import java.lang.Exception;
import java.lang.Override;
import java.util.concurrent.CompletableFuture;
import javax.inject.Inject;
import no.fellesstudentsystem.graphitron.services.TestCustomerService;
import no.fellesstudentsystem.graphql.helpers.resolvers.ResolverHelpers;
import org.jooq.DSLContext;

public class EditCustomerAddressGeneratedResolver implements EditCustomerAddressMutationResolver {
    @Inject
    DSLContext ctx;

    @Override
    public CompletableFuture<EditAddressResponse> editCustomerAddress(EditAddressInput in,
                                                                      DataFetchingEnvironment env) throws Exception {
        var testCustomerService = new TestCustomerService(ResolverHelpers.selectContext(env, this.ctx));
        var transform = new RecordTransformer(env, this.ctx);

        var inRecord = transform.editAddressInputToJOOQRecord(in, "in");

        var editCustomerAddress = testCustomerService.editCustomerAddress(inRecord);

        var editAddressResponse = transform.editAddressResponseToGraphType(editCustomerAddress, "");

        return CompletableFuture.completedFuture(editAddressResponse);
    }
}
