package fake.code.generated.resolvers.mutation;

import fake.code.generated.queries.query.CustomerDBQueries;
import fake.code.generated.queries.query.PaymentDBQueries;
import fake.code.generated.transform.InputTransformer;
import fake.graphql.example.api.EditCustomerNestedMutationResolver;
import fake.graphql.example.model.Customer;
import fake.graphql.example.model.EditCustomerResponse;
import fake.graphql.example.model.EditCustomerResponse2;
import fake.graphql.example.model.EditCustomerResponse3;
import fake.graphql.example.model.EditCustomerResponse4;
import fake.graphql.example.model.EditInputLevel1;
import fake.graphql.example.model.Payment;
import graphql.schema.DataFetchingEnvironment;
import java.lang.Exception;
import java.lang.Override;
import java.lang.String;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import javax.inject.Inject;
import no.fellesstudentsystem.graphitron.services.TestCustomerService;
import no.fellesstudentsystem.graphql.helpers.resolvers.ResolverHelpers;
import no.fellesstudentsystem.graphql.helpers.selection.SelectionSet;
import no.sikt.graphitron.jooq.generated.testdata.tables.records.CustomerRecord;
import org.jooq.DSLContext;

public class EditCustomerNestedGeneratedResolver implements EditCustomerNestedMutationResolver {
    @Inject
    DSLContext ctx;

    @Inject
    private CustomerDBQueries customerDBQueries;

    @Inject
    private PaymentDBQueries paymentDBQueries;

    @Override
    public CompletableFuture<EditCustomerResponse> editCustomerNested(EditInputLevel1 input,
                                                                      DataFetchingEnvironment env) throws Exception {
        var ctx = ResolverHelpers.selectContext(env, this.ctx);
        var testCustomerService = new TestCustomerService(ctx);
        var select = new SelectionSet(env.getSelectionSet());

        var transform = new InputTransformer(env, ctx);

        var inputRecord = transform.editInputLevel1ToJOOQRecord(input, "input");
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
            editA1Record = transform.editInputLevel2AToJOOQRecord(editA1, "input/editA1");

            var editA2 = input.getEditA2();
            editA2Record = transform.editInputLevel2AToJOOQRecord(editA2, "input/editA2");

            var editB = input.getEditB();
            if (editB != null) {
                editBRecord = transform.editInputLevel2BToJOOQRecord(editB, "input/editB");
                var edit3 = editB.getEdit3();
                edit3RecordList = transform.editInputLevel3ToJOOQRecord(edit3, "input/editB/edit3");
                if (edit3 != null) {
                    for (int itEdit3Index = 0; itEdit3Index < edit3.size(); itEdit3Index++) {
                        var itEdit3 = edit3.get(itEdit3Index);
                        if (itEdit3 == null) continue;
                        var edit4 = itEdit3.getEdit4();
                        edit4RecordList.addAll(transform.editInputLevel4ToJOOQRecord(edit4, "input/editB/edit3/edit4"));
                    }
                }
            }
        }

        var editCustomerNestedResult = testCustomerService.editCustomerNested(inputRecord, editA1Record, editA2Record, editBRecord, edit3RecordList, edit4RecordList);
        var editCustomerResponse2Result = editCustomerNestedResult.getEditCustomerResponse2();
        var editCustomerResponse3Result = editCustomerNestedResult.getEditCustomerResponse3();
        var edit4Result = editCustomerResponse3Result.stream().flatMap(it -> it.getEdit4().stream()).collect(Collectors.toList());

        var editCustomerResponse2Customer = getEditCustomerResponse2Customer(ctx, editCustomerResponse2Result, select);
        var editCustomerResponse3Customer = getEditCustomerResponse3Customer(ctx, editCustomerResponse3Result, select);
        var editCustomerResponse4Payment = getEditCustomerResponse4Payment(ctx, edit4Result, select);

        var editCustomerResponse = new EditCustomerResponse();
        editCustomerResponse.setId(editCustomerNestedResult.getId());

        var editCustomerResponse2 = new EditCustomerResponse2();
        editCustomerResponse2.setId(editCustomerResponse2Result.getId2());
        editCustomerResponse2.setCustomer(editCustomerResponse2Customer);
        editCustomerResponse.setEditCustomerResponse2(editCustomerResponse2);

        var editCustomerResponse3List = new ArrayList<EditCustomerResponse3>();
        for (var itEditCustomerResponse3Result : editCustomerResponse3Result) {
            var editCustomerResponse3 = new EditCustomerResponse3();
            editCustomerResponse3.setId(itEditCustomerResponse3Result.getId3());
            editCustomerResponse3.setCustomer(editCustomerResponse3Customer.get(editCustomerResponse3.getId()));

            var editCustomerResponse4List = new ArrayList<EditCustomerResponse4>();
            for (var itEdit4Result : itEditCustomerResponse3Result.getEdit4()) {
                var editCustomerResponse4 = new EditCustomerResponse4();
                editCustomerResponse4.setId(itEdit4Result.getId4());
                editCustomerResponse4.setPayment(editCustomerResponse4Payment.get(editCustomerResponse4.getId()));
                editCustomerResponse4List.add(editCustomerResponse4);
            }
            editCustomerResponse3.setEditCustomerResponse4(editCustomerResponse4List);
            editCustomerResponse3List.add(editCustomerResponse3);
        }
        editCustomerResponse.setEditCustomerResponse3(editCustomerResponse3List);

        return CompletableFuture.completedFuture(editCustomerResponse);
    }

    private Customer getEditCustomerResponse2Customer(DSLContext ctx,
            TestCustomerService.EditCustomerResponse idContainer,
            SelectionSet select) {
        if (!select.contains("EditCustomerResponse2/customer") || idContainer == null) {
            return null;
        }

        var nodes = customerDBQueries.loadCustomerByIdsAsNode(ctx, Set.of(idContainer.getCustomer().getId()), select.withPrefix("EditCustomerResponse2/customer"));
        return nodes.values().stream().findFirst().orElse(null);
    }

    private Map<String, Customer> getEditCustomerResponse3Customer(DSLContext ctx,
            List<TestCustomerService.EditCustomerResponse> idContainer,
            SelectionSet select) {
        if (!select.contains("EditCustomerResponse3/customer") || idContainer == null) {
            return Map.of();
        }

        var ids = idContainer.stream().map(it -> it.getCustomer3().getId()).collect(Collectors.toSet());
        return customerDBQueries.loadCustomerByIdsAsNode(ctx, ids, select.withPrefix("EditCustomerResponse3/customer"));
    }

    private Map<String, Payment> getEditCustomerResponse4Payment(DSLContext ctx,
            List<TestCustomerService.EditCustomerResponse> idContainer,
            SelectionSet select) {
        if (!select.contains("EditCustomerResponse3/EditCustomerResponse4/payment") || idContainer == null) {
            return Map.of();
        }

        var ids = idContainer.stream().map(it -> it.getPayment4().getId()).collect(Collectors.toSet());
        return paymentDBQueries.loadPaymentByIdsAsNode(ctx, ids, select.withPrefix("EditCustomerResponse3/EditCustomerResponse4/payment"));
    }
}
