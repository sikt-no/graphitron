package fake.code.generated.resolvers.mutation;

import fake.code.generated.queries.mutation.EditCustomer1DBQueries;
import fake.graphql.example.package.api.EditCustomer1MutationResolver;
import fake.graphql.example.package.model.EditInput;
import fake.graphql.example.package.model.EditResponse1;
import graphql.schema.DataFetchingEnvironment;
import java.lang.Exception;
import java.lang.Override;
import java.lang.String;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import javax.inject.Inject;
import no.fellesstudentsystem.graphql.helpers.arguments.Arguments;
import no.fellesstudentsystem.graphql.helpers.resolvers.ResolverHelpers;
import no.sikt.graphitron.jooq.generated.testdata.tables.records.CustomerRecord;
import org.jooq.DSLContext;

public class EditCustomer1GeneratedResolver implements EditCustomer1MutationResolver {
    @Inject
    DSLContext ctx;

    @Inject
    private EditCustomer1DBQueries editCustomer1DBQueries;

    @Override
    public CompletableFuture<EditResponse1> editCustomer1(List<String> id, List<EditInput> in,
            DataFetchingEnvironment env) throws Exception {
        var ctx = ResolverHelpers.selectContext(env, this.ctx);
        var flatArguments = Arguments.flattenArgumentKeys(env.getArguments());

        List<CustomerRecord> inRecordList = new ArrayList<CustomerRecord>();

        if (in != null) {
            for (int itInIndex = 0; itInIndex < in.size(); itInIndex++) {
                var itIn = in.get(itInIndex);
                if (itIn == null) continue;
                var inRecord = new CustomerRecord();
                inRecord.attach(ctx.configuration());
                if (flatArguments.contains("in/firstName")) {
                    inRecord.setFirstName(itIn.getFirstName());
                }
                inRecordList.add(inRecord);
            }
        }

        var rowsUpdated = editCustomer1DBQueries.editCustomer1(ctx, id, inRecordList);

        var editResponse1 = new EditResponse1();
        editResponse1.setId1(id.stream().map(it -> it.getId()).collect(Collectors.toList()));

        return CompletableFuture.completedFuture(editResponse1);
    }
}