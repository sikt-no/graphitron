package fake.code.generated.transform;

import fake.code.generated.mappers.CustomerTypeMapper;
import fake.graphql.example.model.Customer;
import graphql.schema.DataFetchingEnvironment;
import java.lang.String;
import java.util.List;
import no.fellesstudentsystem.graphitron_newtestorder.codereferences.dummyreferences.DummyRecord;
import no.fellesstudentsystem.graphql.helpers.transform.AbstractTransformer;
import org.jooq.DSLContext;

public class RecordTransformer extends AbstractTransformer {
    public RecordTransformer(DataFetchingEnvironment env, DSLContext ctx) {
        super(env, ctx);
    }

    public List<Customer> customerToGraphType(List<DummyRecord> input, String path) {
        return CustomerTypeMapper.toGraphType(input, path, this);
    }

    public Customer customerToGraphType(DummyRecord input, String path) {
        return customerToGraphType(List.of(input), path).stream().findFirst().orElse(null);
    }
}
