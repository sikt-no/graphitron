package fake.code.generated.resolvers.mutation;

import graphql.schema.DataFetchingEnvironment;
import java.lang.Exception;
import java.lang.Override;
import java.lang.String;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import javax.inject.Inject;
import fake.graphql.example.package.api.EditCustomerMutationResolver;
import fake.graphql.example.package.model.EndreInput;
import fake.code.generated.queries.mutation.EditCustomerDBQueries;
import no.fellesstudentsystem.graphitron.transforms.SomeTransform;
import no.fellesstudentsystem.graphql.helpers.arguments.Arguments;
import no.sikt.graphitron.jooq.generated.testdata.tables.records.CustomerRecord;
import org.jooq.DSLContext;

public class EditCustomerGeneratedResolver implements EditCustomerMutationResolver {
    @Inject
    DSLContext ctx;

    @Inject
    private EditCustomerDBQueries editCustomerDBQueries;

    @Override
    public CompletableFuture<String> editCustomer(EndreInput in, DataFetchingEnvironment env) throws
            Exception {
        var ctx = env.getLocalContext() == null ? this.ctx : (DSLContext) env.getLocalContext();
        var flatArguments = Arguments.flattenArgumentKeys(env.getArguments());

        var inRecord = new CustomerRecord();
        inRecord.attach(ctx.configuration());

        if (in != null) {
            if (flatArguments.contains("in/id")) {
                inRecord.setId(in.getId());
            }
            if (flatArguments.contains("in/firstName")) {
                inRecord.setFirstName(in.getFirstName());
            }
            inRecord = SomeTransform.someTransform(ctx, List.of(inRecord)).stream().findFirst().get();
        }

        var rowsUpdated = editCustomerDBQueries.editCustomer(ctx, inRecord);

        return CompletableFuture.completedFuture(inRecord.getId());
    }
}
