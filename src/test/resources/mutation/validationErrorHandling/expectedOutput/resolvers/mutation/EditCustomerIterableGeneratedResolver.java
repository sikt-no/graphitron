package fake.code.generated.resolvers.mutation;

import fake.code.generated.queries.mutation.EditCustomerIterableDBQueries;
import fake.graphql.example.package.api.EditCustomerIterableMutationResolver;
import fake.graphql.example.package.model.EditInput;
import fake.graphql.example.package.model.EditResponse;
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
import java.util.stream.Collectors;
import javax.inject.Inject;
import no.fellesstudentsystem.graphql.exception.ValidationViolationGraphQLException;
import no.fellesstudentsystem.graphql.helpers.arguments.Arguments;
import no.fellesstudentsystem.graphql.helpers.resolvers.ResolverHelpers;
import no.fellesstudentsystem.graphql.helpers.validation.RecordValidator;
import no.sikt.graphitron.jooq.generated.testdata.tables.records.CustomerRecord;
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
        var flatArguments = Arguments.flattenArgumentKeys(env.getArguments());
        var validationErrors = new HashSet<GraphQLError>();
        List<CustomerRecord> inRecordList = new ArrayList<CustomerRecord>();

        if (in != null) {
           for (int itInIndex = 0; itInIndex < in.size(); itInIndex++) {
                var itIn = in.get(itInIndex);
                if (itIn == null) continue;
                var pathsForProperties = new HashMap<String, List<String>>();
                var inRecord = new CustomerRecord();
                inRecord.attach(ctx.configuration());
                var name = itIn.getName();
                if (name != null) {
                    if (flatArguments.contains("in/name/firstName")) {
                        inRecord.setFirstName(name.getFirstName());
                        pathsForProperties.put("firstName", List.of(("in/" + itInIndex + "/name/firstName").split("/")));
                    }
                    if (flatArguments.contains("in/name/surname")) {
                        inRecord.setLastName(name.getSurname());
                        pathsForProperties.put("lastName", List.of(("in/" + itInIndex + "/name/surname").split("/")));
                    }
                }
                if (flatArguments.contains("in/id")) {
                    inRecord.setId(itIn.getId());
                    pathsForProperties.put("id", List.of(("in/" + itInIndex + "/id").split("/")));
                }
                inRecordList.add(inRecord);
                validationErrors.addAll(RecordValidator.validatePropertiesAndGenerateGraphQLErrors(inRecord, pathsForProperties, env));
            }
        }

        if (!validationErrors.isEmpty()) {
            throw new ValidationViolationGraphQLException(validationErrors);
        }
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