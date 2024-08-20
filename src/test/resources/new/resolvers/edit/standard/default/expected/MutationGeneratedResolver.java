package fake.code.generated.resolvers.mutation;

import fake.code.generated.queries.mutation.MutationDBQueries;
import fake.code.generated.transform.RecordTransformer;
import fake.graphql.example.api.MutationMutationResolver;
import fake.graphql.example.model.CustomerInputTable;
import graphql.schema.DataFetchingEnvironment;
import java.lang.Exception;
import java.lang.Override;
import java.lang.String;
import java.util.concurrent.CompletableFuture;
import javax.inject.Inject;
import org.jooq.DSLContext;

public class MutationGeneratedResolver implements MutationMutationResolver {
    @Inject
    DSLContext ctx;

    @Override
    public CompletableFuture<String> mutation(CustomerInputTable in, DataFetchingEnvironment env)
            throws Exception {
        var transform = new RecordTransformer(env, this.ctx);
        var inRecord = transform.customerInputTableToJOOQRecord(in, "in");
        var rowsUpdated = MutationDBQueries.mutation(transform.getCtx(), inRecord);
        return CompletableFuture.completedFuture(inRecord.getId());
    }
}
