package fake.code.generated.resolvers.mutation;

import fake.graphql.example.package.api.EditCustomerAddressMutationResolver;
import fake.graphql.example.package.model.EditAddressInput;
import fake.graphql.example.package.model.EditAddressResponse;
import graphql.schema.DataFetchingEnvironment;
import java.lang.Exception;
import java.lang.Override;
import java.util.concurrent.CompletableFuture;
import javax.inject.Inject;
import no.fellesstudentsystem.graphitron.services.TestCustomerService;
import no.fellesstudentsystem.graphql.helpers.arguments.Arguments;
import no.sikt.graphitron.jooq.generated.testdata.tables.records.CustomerRecord;
import org.jooq.DSLContext;

public class EditCustomerAddressGeneratedResolver implements EditCustomerAddressMutationResolver {
    @Inject
    DSLContext ctx;

    @Override
    public CompletableFuture<EditAddressResponse> editCustomerAddress(EditAddressInput in,
            DataFetchingEnvironment env) throws Exception {
        var ctx = env.getLocalContext() == null ? this.ctx : (DSLContext) env.getLocalContext();
        var testCustomerService = new TestCustomerService(ctx);
        var flatArguments = Arguments.flattenArgumentKeys(env.getArguments());

        var inRecord = new CustomerRecord();
        inRecord.attach(ctx.configuration());

        if (in != null) {
            if (flatArguments.contains("in/id")) {
                inRecord.setId(in.getId());
            }
            if (flatArguments.contains("in/addressId")) {
                inRecord.setAddressId(in.getAddressId());
            }
        }

        var editCustomerAddressResult = testCustomerService.editCustomerAddress(inRecord);


        var editAddressResponse = new EditAddressResponse();
        editAddressResponse.setId(editCustomerAddressResult.getId());
        editAddressResponse.setAddressId(editCustomerAddressResult.getAddressId());

        return CompletableFuture.completedFuture(editAddressResponse);
    }
}