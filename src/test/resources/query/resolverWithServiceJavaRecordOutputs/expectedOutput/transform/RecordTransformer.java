package fake.code.generated.transform;

import fake.code.generated.mappers.AddressCity0TypeMapper;
import fake.code.generated.mappers.AddressCity1TypeMapper;
import fake.code.generated.mappers.AddressTypeMapper;
import fake.code.generated.mappers.CustomerTypeMapper;
import fake.code.generated.mappers.OuterWrapperTypeMapper;
import fake.graphql.example.model.Address;
import fake.graphql.example.model.AddressCity0;
import fake.graphql.example.model.AddressCity1;
import fake.graphql.example.model.Customer;
import fake.graphql.example.model.OuterWrapper;
import graphql.schema.DataFetchingEnvironment;
import java.lang.String;
import java.util.List;
import no.fellesstudentsystem.graphitron.records.TestAddressRecord;
import no.fellesstudentsystem.graphitron.records.TestCityRecord;
import no.fellesstudentsystem.graphitron.records.TestCustomerRecord;
import no.fellesstudentsystem.graphql.helpers.transform.AbstractTransformer;
import no.sikt.graphitron.jooq.generated.testdata.tables.records.CityRecord;
import no.sikt.graphitron.jooq.generated.testdata.tables.records.CustomerRecord;
import org.jooq.DSLContext;

public class RecordTransformer extends AbstractTransformer {
    public RecordTransformer(DataFetchingEnvironment env, DSLContext ctx) {
        super(env, ctx);
    }

    public List<OuterWrapper> outerWrapperToGraphType(List<TestCustomerRecord> input, String path) {
        return OuterWrapperTypeMapper.toGraphType(input, path, this);
    }

    public List<Address> addressToGraphType(List<TestAddressRecord> input, String path) {
        return AddressTypeMapper.toGraphType(input, path, this);
    }

    public List<Customer> customerRecordToGraphType(List<CustomerRecord> input, String path) {
        return CustomerTypeMapper.recordToGraphType(input, path, this);
    }

    public List<AddressCity0> addressCity0RecordToGraphType(List<CityRecord> input, String path) {
        return AddressCity0TypeMapper.recordToGraphType(input, path, this);
    }

    public List<AddressCity1> addressCity1ToGraphType(List<TestCityRecord> input, String path) {
        return AddressCity1TypeMapper.toGraphType(input, path, this);
    }

    public OuterWrapper outerWrapperToGraphType(TestCustomerRecord input, String path) {
        return outerWrapperToGraphType(List.of(input), path).stream().findFirst().orElse(null);
    }

    public Address addressToGraphType(TestAddressRecord input, String path) {
        return addressToGraphType(List.of(input), path).stream().findFirst().orElse(null);
    }

    public Customer customerRecordToGraphType(CustomerRecord input, String path) {
        return customerRecordToGraphType(List.of(input), path).stream().findFirst().orElse(null);
    }

    public AddressCity0 addressCity0RecordToGraphType(CityRecord input, String path) {
        return addressCity0RecordToGraphType(List.of(input), path).stream().findFirst().orElse(null);
    }

    public AddressCity1 addressCity1ToGraphType(TestCityRecord input, String path) {
        return addressCity1ToGraphType(List.of(input), path).stream().findFirst().orElse(null);
    }
}
