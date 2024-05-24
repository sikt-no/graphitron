package fake.code.generated.resolvers.mutation;

import fake.code.generated.transform.RecordTransformer;
import fake.graphql.example.api.EditCustomerRecord2MutationResolver;
import fake.graphql.example.model.EditResponse2;
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

public class EditCustomerRecord2GeneratedResolver implements EditCustomerRecord2MutationResolver {
    @Inject
    DSLContext ctx;

    @Override
    public CompletableFuture<EditResponse2> editCustomerRecord2(List<String> id,
                                                                DataFetchingEnvironment env) throws Exception {
        var ctx = ResolverHelpers.selectContext(env, this.ctx);
        var testCustomerService = new TestCustomerService(ctx);
        var transform = new RecordTransformer(env, this.ctx);

        var editCustomerRecord2 = testCustomerService.editCustomerRecord2(id);

        var editResponse2 = transform.editResponse2ToGraphType(editCustomerRecord2, "");

        return CompletableFuture.completedFuture(editResponse2);
    }
}
