package fake.code.generated.resolvers.mutation;

import fake.code.generated.transform.RecordTransformer;
import fake.graphql.example.api.EditCustomerIDListMutationResolver;
import fake.graphql.example.model.EditResponseList;
import graphql.schema.DataFetchingEnvironment;
import java.lang.Exception;
import java.lang.Override;
import java.lang.String;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import javax.inject.Inject;
import no.fellesstudentsystem.graphitron.services.TestCustomerService;
import no.fellesstudentsystem.graphql.helpers.resolvers.ResolverHelpers;
import org.jooq.DSLContext;

public class EditCustomerIDListGeneratedResolver implements EditCustomerIDListMutationResolver {
    @Inject
    DSLContext ctx;

    @Override
    public CompletableFuture<EditResponseList> editCustomerIDList(List<String> id,
                                                                  DataFetchingEnvironment env) throws Exception {
        var ctx = ResolverHelpers.selectContext(env, this.ctx);
        var testCustomerService = new TestCustomerService(ctx);
        var transform = new RecordTransformer(env, ctx);

        var editCustomerIDList = testCustomerService.editCustomerIDList(id);

        var editResponseList = transform.editResponseListToGraphType(editCustomerIDList, "");


        return CompletableFuture.completedFuture(editResponseList);
    }
}
