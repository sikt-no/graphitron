package fake.code.generated.resolvers.mutation;

import fake.graphql.example.api.EditCustomerRecord1MutationResolver;
import fake.graphql.example.model.EditResponse1;
import graphql.schema.DataFetchingEnvironment;
import java.lang.Exception;
import java.lang.Override;
import java.lang.String;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import javax.inject.Inject;
import no.fellesstudentsystem.graphitron.services.TestCustomerService;
import no.fellesstudentsystem.graphql.helpers.resolvers.ResolverHelpers;
import org.jooq.DSLContext;

public class EditCustomerRecord1GeneratedResolver implements EditCustomerRecord1MutationResolver {
    @Inject
    DSLContext ctx;

    @Override
    public CompletableFuture<EditResponse1> editCustomerRecord1(List<String> id,
            DataFetchingEnvironment env) throws Exception {
        var ctx = ResolverHelpers.selectContext(env, this.ctx);
        var testCustomerService = new TestCustomerService(ctx);
        var editCustomerRecord1Result = testCustomerService.editCustomerRecord1(id);

        var editResponse1 = new EditResponse1();
        editResponse1.setId1(editCustomerRecord1Result.stream().map(itId1 -> itId1.getId()).collect(Collectors.toList()));

        return CompletableFuture.completedFuture(editResponse1);
    }
}