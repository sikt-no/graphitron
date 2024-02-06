package fake.code.generated.resolvers.mutation;

import fake.code.generated.transform.InputTransformer;
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
        var ctx = ResolverHelpers.selectContext(env, this.ctx);
        var testCustomerService = new TestCustomerService(ctx);

        var transform = new InputTransformer(env, ctx);

        var inRecord = transform.editAddressInputToJOOQRecord(in, "in");

        var editCustomerAddressResult = testCustomerService.editCustomerAddress(inRecord);


        var editAddressResponse = new EditAddressResponse();
        editAddressResponse.setId(editCustomerAddressResult.getId());
        editAddressResponse.setAddressId(editCustomerAddressResult.getAddressId());

        return CompletableFuture.completedFuture(editAddressResponse);
    }
}