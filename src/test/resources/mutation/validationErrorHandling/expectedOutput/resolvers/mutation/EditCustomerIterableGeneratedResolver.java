package fake.code.generated.resolvers.mutation;

import fake.code.generated.transform.RecordTransformer;
import fake.code.generated.queries.mutation.EditCustomerIterableDBQueries;
import fake.graphql.example.api.EditCustomerIterableMutationResolver;
import fake.graphql.example.model.EditInput;
import fake.graphql.example.model.EditResponse;
import graphql.schema.DataFetchingEnvironment;
import java.lang.Exception;
import java.lang.Override;
import java.lang.String;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import javax.inject.Inject;
import no.fellesstudentsystem.graphql.helpers.resolvers.ResolverHelpers;
import org.jooq.DSLContext;

public class EditCustomerIterableGeneratedResolver implements EditCustomerIterableMutationResolver {
    @Inject
    DSLContext ctx;

    @Inject
    private EditCustomerIterableDBQueries editCustomerIterableDBQueries;

    @Override
    public CompletableFuture<List<EditResponse>> editCustomerIterable(List<String> id,
            List<EditInput> in, DataFetchingEnvironment env) throws Exception {
        var ctx = ResolverHelpers.selectContext(env, this.ctx);

        var transform = new RecordTransformer(env, this.ctx);

        var inRecordList = transform.editInputToJOOQRecord(in, "in", "in");

        transform.validate();

        var rowsUpdated = editCustomerIterableDBQueries.editCustomerIterable(ctx, id, inRecordList);

        var editResponseList = new ArrayList<EditResponse>();
        for (var itInRecordList : inRecordList) {
            var editResponse = new EditResponse();
            editResponse.setId(id.stream().map(it -> it.getId()).collect(Collectors.toList()));
            editResponseList.add(editResponse);
        }

        return CompletableFuture.completedFuture(editResponseList);
    }
}