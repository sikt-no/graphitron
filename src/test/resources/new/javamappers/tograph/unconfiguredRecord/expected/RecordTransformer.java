package fake.code.generated.transform;

import fake.graphql.example.model.Customer;
import fake.graphql.example.model.CustomerJOOQ;
import fake.graphql.example.model.CustomerJava;
import graphql.schema.DataFetchingEnvironment;
import java.lang.String;
import java.util.List;
import no.fellesstudentsystem.graphitron_newtestorder.codereferences.records.CustomerJavaRecord;
import no.fellesstudentsystem.graphql.helpers.transform.AbstractTransformer;
import no.sikt.graphitron.jooq.generated.testdata.tables.records.CustomerRecord;
import org.jooq.DSLContext;

public class RecordTransformer extends AbstractTransformer {
    public RecordTransformer(DataFetchingEnvironment env, DSLContext ctx) {
        super(env, ctx);
    }

    public List<CustomerJOOQ> customerJOOQRecordToGraphType(List<CustomerRecord> input,
                                                            String path) {
        return List.of();
    }

    public List<Customer> customerToGraphType(List<CustomerJavaRecord> input, String path) {
        return List.of();
    }

    public List<CustomerJava> customerJavaToGraphType(List<CustomerJavaRecord> input, String path) {
        return List.of();
    }

    public CustomerJOOQ customerJOOQRecordToGraphType(CustomerRecord input, String path) {
        return null;
    }

    public Customer customerToGraphType(CustomerJavaRecord input, String path) {
        return null;
    }

    public CustomerJava customerJavaToGraphType(CustomerJavaRecord input, String path) {
        return null;
    }
}
