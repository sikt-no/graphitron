package fake.code.generated.resolvers.mutation;

import fake.code.generated.queries.mutation.EditCustomer1DBQueries;
import fake.code.generated.transform.RecordTransformer;
import fake.graphql.example.api.EditCustomer1MutationResolver;
import fake.graphql.example.model.EditInput;
import fake.graphql.example.model.EditResponse1;
import graphql.schema.DataFetchingEnvironment;
import java.lang.Exception;
import java.lang.Override;
import java.lang.String;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import javax.inject.Inject;
import no.fellesstudentsystem.graphql.helpers.resolvers.ResolverHelpers;
import org.jooq.DSLContext;

public class EditCustomer1GeneratedResolver implements EditCustomer1MutationResolver {
    @Inject
    DSLContext ctx;

    @Override
    public CompletableFuture<EditResponse1> editCustomer1(List<String> id, List<EditInput> in,
            DataFetchingEnvironment env) throws Exception {
        var ctx = ResolverHelpers.selectContext(env, this.ctx);

        var transform = new RecordTransformer(env, this.ctx);

        var inRecordList = transform.editInputToJOOQRecord(in, "in");

        var rowsUpdated = EditCustomer1DBQueries.editCustomer1(ctx, id, inRecordList);

        var editResponse1 = new EditResponse1();
        editResponse1.setId1(id.stream().map(it -> it.getId()).collect(Collectors.toList()));

        return CompletableFuture.completedFuture(editResponse1);
    }
}
