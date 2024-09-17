package fake.code.generated.resolvers.mutation;

import fake.code.generated.queries.mutation.MutationDBQueries;
import fake.code.generated.queries.query.CustomerDBQueries;
import fake.code.generated.transform.RecordTransformer;
import fake.graphql.example.api.MutationMutationResolver;
import fake.graphql.example.model.CustomerInputTable;
import fake.graphql.example.model.Response;
import graphql.schema.DataFetchingEnvironment;
import java.lang.Exception;
import java.lang.Override;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import javax.inject.Inject;
import org.jooq.DSLContext;

public class MutationGeneratedResolver implements MutationMutationResolver {
    @Inject
    DSLContext ctx;

    @Override
    public CompletableFuture<Response> mutation(CustomerInputTable in, DataFetchingEnvironment env)
            throws Exception {
        var transform = new RecordTransformer(env, this.ctx);
        var inRecord = transform.customerInputTableToJOOQRecord(in, "in");
        var rowsUpdated = MutationDBQueries.mutation(transform.getCtx(), inRecord);

        var response = new Response();
        if (inRecord != null && transform.getSelect().contains("customer")) {
            response.setCustomer(CustomerDBQueries.loadCustomerByIdsAsNode(transform.getCtx(), Set.of(inRecord.getId()), transform.getSelect().withPrefix("customer")).values().stream().findFirst().orElse(null));
        }

        return CompletableFuture.completedFuture(response);
    }
}
