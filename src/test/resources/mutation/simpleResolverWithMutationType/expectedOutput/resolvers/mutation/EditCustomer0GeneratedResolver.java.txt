package fake.code.generated.resolvers.mutation;

import fake.code.generated.queries.mutation.EditCustomer0DBQueries;
import fake.graphql.example.package.api.EditCustomer0MutationResolver;
import fake.graphql.example.package.model.EditInput;
import fake.graphql.example.package.model.EditResponse0;
import graphql.schema.DataFetchingEnvironment;
import java.lang.Exception;
import java.lang.Override;
import java.lang.String;
import java.util.concurrent.CompletableFuture;
import javax.inject.Inject;
import no.fellesstudentsystem.graphql.helpers.arguments.Arguments;
import no.sikt.graphitron.jooq.generated.testdata.tables.records.CustomerRecord;
import org.jooq.DSLContext;

public class EditCustomer0GeneratedResolver implements EditCustomer0MutationResolver {
    @Inject
    DSLContext ctx;

    @Inject
    private EditCustomer0DBQueries editCustomer0DBQueries;

    @Override
    public CompletableFuture<EditResponse0> editCustomer0(String id, EditInput in,
            DataFetchingEnvironment env) throws Exception {
        var ctx = env.getLocalContext() == null ? this.ctx : (DSLContext) env.getLocalContext();
        var flatArguments = Arguments.flattenArgumentKeys(env.getArguments());

        var inRecord = new CustomerRecord();
        inRecord.attach(ctx.configuration());

        if (in != null) {
            if (flatArguments.contains("in/firstName")) {
                inRecord.setFirstName(in.getFirstName());
            }
        }

        var rowsUpdated = editCustomer0DBQueries.editCustomer0(ctx, id, inRecord);

        var editResponse0 = new EditResponse0();
        editResponse0.setId0(id);

        return CompletableFuture.completedFuture(editResponse0);
    }
}