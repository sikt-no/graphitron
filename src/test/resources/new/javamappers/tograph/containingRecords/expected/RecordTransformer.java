package fake.code.generated.transform;

import fake.graphql.example.model.Address;
import fake.graphql.example.model.AddressCityJOOQ;
import fake.graphql.example.model.AddressCityJava;
import graphql.schema.DataFetchingEnvironment;
import java.lang.String;
import java.util.List;
import no.fellesstudentsystem.graphitron_newtestorder.codereferences.records.MapperAddressJavaRecord;
import no.fellesstudentsystem.graphitron_newtestorder.codereferences.records.MapperCityJavaRecord;
import no.fellesstudentsystem.graphql.helpers.transform.AbstractTransformer;
import no.sikt.graphitron.jooq.generated.testdata.tables.records.CityRecord;
import org.jooq.DSLContext;

public class RecordTransformer extends AbstractTransformer {
    public RecordTransformer(DataFetchingEnvironment env, DSLContext ctx) {
        super(env, ctx);
    }

    public List<AddressCityJOOQ> addressCityJOOQRecordToGraphType(List<CityRecord> input,
                                                                  String path) {
        return List.of();
    }

    public List<Address> addressToGraphType(List<MapperAddressJavaRecord> input, String path) {
        return List.of();
    }

    public List<AddressCityJava> addressCityJavaToGraphType(List<MapperCityJavaRecord> input,
                                                            String path) {
        return List.of();
    }

    public AddressCityJOOQ addressCityJOOQRecordToGraphType(CityRecord input, String path) {
        return null;
    }

    public Address addressToGraphType(MapperAddressJavaRecord input, String path) {
        return null;
    }

    public AddressCityJava addressCityJavaToGraphType(MapperCityJavaRecord input, String path) {
        return null;
    }
}
