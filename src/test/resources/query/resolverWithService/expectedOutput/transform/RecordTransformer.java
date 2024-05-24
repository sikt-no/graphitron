package fake.code.generated.transform;

import fake.code.generated.mappers.CustomerTypeMapper;
import fake.graphql.example.model.Customer;
import graphql.schema.DataFetchingEnvironment;
import java.lang.String;
import java.util.List;
import no.fellesstudentsystem.graphql.helpers.transform.AbstractTransformer;
import no.sikt.graphitron.jooq.generated.testdata.tables.Address;
import no.sikt.graphitron.jooq.generated.testdata.tables.records.AddressRecord;
import no.sikt.graphitron.jooq.generated.testdata.tables.records.CustomerRecord;
import org.jooq.DSLContext;

public class RecordTransformer extends AbstractTransformer {
    public RecordTransformer(DataFetchingEnvironment env, DSLContext ctx) {
        super(env, ctx);
    }

    public List<Customer> customerRecordToGraphType(List<CustomerRecord> input, String path) {
        return CustomerTypeMapper.recordToGraphType(input, path, this);
    }

    public Customer customerRecordToGraphType(CustomerRecord input, String path) {
        return customerRecordToGraphType(List.of(input), path).stream().findFirst().orElse(null);
    }

    public List<Address> AddressRecordToGraphType(List<AddressRecord> input, String path) {
        return AddressTypeMapper.recordToGraphType(input, path, this);
    }

    public Address AddressRecordToGraphType(AddressRecord input, String path) {
        return AddressRecordToGraphType(List.of(input), path).stream().findFirst().orElse(null);
    }
}
