package fake.code.generated.resolvers.mutation;

import fake.code.generated.transform.RecordTransformer;
import fake.graphql.example.api.EditCustomerNestedMutationResolver;
import fake.graphql.example.model.EditCustomerResponse;
import fake.graphql.example.model.EditInputLevel1;
import graphql.schema.DataFetchingEnvironment;
import java.lang.Exception;
import java.lang.Override;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import javax.inject.Inject;
import no.fellesstudentsystem.graphitron.services.TestCustomerService;
import no.fellesstudentsystem.graphql.helpers.resolvers.ResolverHelpers;
import no.sikt.graphitron.jooq.generated.testdata.tables.records.CustomerRecord;
import org.jooq.DSLContext;

public class EditCustomerNestedGeneratedResolver implements EditCustomerNestedMutationResolver {
    @Inject
    DSLContext ctx;

    @Override
    public CompletableFuture<EditCustomerResponse> editCustomerNested(EditInputLevel1 input,
            DataFetchingEnvironment env) throws Exception {
        var ctx = ResolverHelpers.selectContext(env, this.ctx);
        var testCustomerService = new TestCustomerService(ctx);
        var transform = new RecordTransformer(env, ctx);

        var inputRecord = transform.editInputLevel1ToJOOQRecord(input, "input", "input");
        var editA1Record = new CustomerRecord();
        editA1Record.attach(ctx.configuration());
        var editA2Record = new CustomerRecord();
        editA2Record.attach(ctx.configuration());
        var editBRecord = new CustomerRecord();
        editBRecord.attach(ctx.configuration());
        var edit3RecordList = new ArrayList<CustomerRecord>();
        var edit4RecordList = new ArrayList<CustomerRecord>();

        if (input != null) {
            var editA1 = input.getEditA1();
            editA1Record = transform.editInputLevel2AToJOOQRecord(editA1, "input/editA1", "input/editA1");
            var editA2 = input.getEditA2();
            editA2Record = transform.editInputLevel2AToJOOQRecord(editA2, "input/editA2", "input/editA2");
            var editB = input.getEditB();
            if (editB != null) {
                editBRecord = transform.editInputLevel2BToJOOQRecord(editB, "input/editB", "input/editB");
                var edit3 = editB.getEdit3();
                if (edit3 != null) {
                    edit3RecordList = transform.editInputLevel3ToJOOQRecord(edit3, "input/editB/edit3", "input/editB/edit3");
                    for (int itEdit3Index = 0; itEdit3Index < edit3.size(); itEdit3Index++) {
                        var itEdit3 = edit3.get(itEdit3Index);
                        if (itEdit3 == null) continue;
                        var edit4 = itEdit3.getEdit4();
                        edit4RecordList.addAll(transform.editInputLevel4ToJOOQRecord(edit4, "input/editB/edit3/edit4", "input/editB/edit3/" + itEdit3Index + "/edit4"));
                    }
                }
            }
        }

        transform.validate();

        var editCustomerNested = testCustomerService.editCustomerNested(inputRecord, editA1Record, editA2Record, editBRecord, edit3RecordList, edit4RecordList);

        var editCustomerResponse = transform.editCustomerResponseToGraphType(editCustomerNested, "");

        return CompletableFuture.completedFuture(editCustomerResponse);
    }
}