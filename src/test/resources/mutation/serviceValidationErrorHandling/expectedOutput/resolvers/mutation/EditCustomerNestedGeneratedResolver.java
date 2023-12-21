package fake.code.generated.resolvers.mutation;

import fake.graphql.example.package.api.EditCustomerNestedMutationResolver;
import fake.graphql.example.package.model.EditCustomerResponse;
import fake.graphql.example.package.model.EditInputLevel1;
import graphql.GraphQLError;
import graphql.schema.DataFetchingEnvironment;
import java.lang.Exception;
import java.lang.Override;
import java.lang.String;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import javax.inject.Inject;
import no.fellesstudentsystem.graphitron.services.TestCustomerService;
import no.fellesstudentsystem.graphql.exception.ValidationViolationGraphQLException;
import no.fellesstudentsystem.graphql.helpers.arguments.Arguments;
import no.fellesstudentsystem.graphql.helpers.validation.RecordValidator;
import no.sikt.graphitron.jooq.generated.testdata.tables.records.CustomerRecord;
import org.jooq.DSLContext;

public class EditCustomerNestedGeneratedResolver implements EditCustomerNestedMutationResolver {
    @Inject
    DSLContext ctx;

    @Override
    public CompletableFuture<EditCustomerResponse> editCustomerNested(EditInputLevel1 input,
            DataFetchingEnvironment env) throws Exception {
        var ctx = env.getLocalContext() == null ? this.ctx : (DSLContext) env.getLocalContext();
        var testCustomerService = new TestCustomerService(ctx);
        var flatArguments = Arguments.flattenArgumentKeys(env.getArguments());
        var validationErrors = new HashSet<GraphQLError>();
        var inputRecord = new CustomerRecord();
        inputRecord.attach(ctx.configuration());
        var editA1Record = new CustomerRecord();
        editA1Record.attach(ctx.configuration());
        var editA2Record = new CustomerRecord();
        editA2Record.attach(ctx.configuration());
        var editBRecord = new CustomerRecord();
        editBRecord.attach(ctx.configuration());
        List<CustomerRecord> edit3RecordList = new ArrayList<CustomerRecord>();
        List<CustomerRecord> edit4RecordList = new ArrayList<CustomerRecord>();

        if (input != null) {
            var pathsForProperties = new HashMap<String, List<String>>();
            var editA1 = input.getEditA1();
            if (editA1 != null) {
                if (flatArguments.contains("input/editA1/firstName")) {
                    editA1Record.setFirstName(editA1.getFirstName());
                    pathsForProperties.put("firstName", List.of(("input/editA1/firstName").split("/")));
                }
            }
            var editA2 = input.getEditA2();
            if (editA2 != null) {
                if (flatArguments.contains("input/editA2/firstName")) {
                    editA2Record.setFirstName(editA2.getFirstName());
                    pathsForProperties.put("firstName", List.of(("input/editA2/firstName").split("/")));
                }
            }
            var editB = input.getEditB();
            if (editB != null) {
                var edit3 = editB.getEdit3();
                if (edit3 != null) {
                    for (int itEdit3Index = 0; itEdit3Index < edit3.size(); itEdit3Index++) {
                        var itEdit3 = edit3.get(itEdit3Index);
                        if (itEdit3 == null) continue;
                        var edit3Record = new CustomerRecord();
                        edit3Record.attach(ctx.configuration());
                        if (flatArguments.contains("input/editB/edit3/email")) {
                            edit3Record.setEmail(itEdit3.getEmail());
                            pathsForProperties.put("email", List.of(("input/editB/edit3/" + itEdit3Index + "/email").split("/")));
                        }
                        var edit4 = itEdit3.getEdit4();
                        if (edit4 != null) {
                            for (int itEdit4Index = 0; itEdit4Index < edit4.size(); itEdit4Index++) {
                                var itEdit4 = edit4.get(itEdit4Index);
                                if (itEdit4 == null) continue;
                                var edit4Record = new CustomerRecord();
                                edit4Record.attach(ctx.configuration());
                                if (flatArguments.contains("input/editB/edit3/edit4/lastName")) {
                                    edit4Record.setLastName(itEdit4.getLastName());
                                    pathsForProperties.put("lastName", List.of(("input/editB/edit3/" + itEdit3Index + "/edit4/" + itEdit4Index + "/lastName").split("/")));
                                }
                                edit4RecordList.add(edit4Record);
                            }
                        }
                        edit3RecordList.add(edit3Record);
                    }
                }
                if (flatArguments.contains("input/editB/firstName")) {
                    editBRecord.setFirstName(editB.getFirstName());
                    pathsForProperties.put("firstName", List.of(("input/editB/firstName").split("/")));
                }
            }
            var editC1 = input.getEditC1();
            if (editC1 != null) {
                if (flatArguments.contains("input/editC1/lastName")) {
                    inputRecord.setLastName(editC1.getLastName());
                    pathsForProperties.put("lastName", List.of(("input/editC1/lastName").split("/")));
                }
            }
            var editC2 = input.getEditC2();
            if (flatArguments.contains("input/id")) {
                inputRecord.setId(input.getId());
                pathsForProperties.put("id", List.of(("input/id").split("/")));
            }
            validationErrors.addAll(RecordValidator.validatePropertiesAndGenerateGraphQLErrors(inputRecord, pathsForProperties, env));
        }

        if (!validationErrors.isEmpty()) {
            throw new ValidationViolationGraphQLException(validationErrors);
        }
        var editCustomerNestedResult = testCustomerService.editCustomerNested(inputRecord, editA1Record, editA2Record, editBRecord, edit3RecordList, edit4RecordList);


        var editCustomerResponse = new EditCustomerResponse();
        editCustomerResponse.setId(editCustomerNestedResult.getId());

        return CompletableFuture.completedFuture(editCustomerResponse);
    }
}