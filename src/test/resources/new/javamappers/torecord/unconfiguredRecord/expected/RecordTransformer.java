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

    public List<CustomerRecord> customerJOOQToJOOQRecord(List<CustomerJOOQ> input, String path) {
        return List.of();
    }

    public List<CustomerJavaRecord> customerToJavaRecord(List<Customer> input, String path) {
        return List.of();
    }

    public List<CustomerJavaRecord> customerJavaToJavaRecord(List<CustomerJava> input,
                                                             String path) {
        return List.of();
    }

    public CustomerRecord customerJOOQToJOOQRecord(CustomerJOOQ input, String path) {
        return new CustomerRecord();
    }

    public CustomerJavaRecord customerToJavaRecord(Customer input, String path) {
        return new CustomerJavaRecord();
    }

    public CustomerJavaRecord customerJavaToJavaRecord(CustomerJava input, String path) {
        return new CustomerJavaRecord();
    }
}
