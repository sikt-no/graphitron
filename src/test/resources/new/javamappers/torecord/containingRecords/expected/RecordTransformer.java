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

    public List<CityRecord> addressCityJOOQToJOOQRecord(List<AddressCityJOOQ> input, String path) {
        return List.of();
    }

    public List<MapperAddressJavaRecord> addressToJavaRecord(List<Address> input, String path) {
        return List.of();
    }

    public List<MapperCityJavaRecord> addressCityJavaToJavaRecord(List<AddressCityJava> input,
                                                                  String path) {
        return List.of();
    }

    public CityRecord addressCityJOOQToJOOQRecord(AddressCityJOOQ input, String path) {
        return new CityRecord();
    }

    public MapperAddressJavaRecord addressToJavaRecord(Address input, String path) {
        return new MapperAddressJavaRecord();
    }

    public MapperCityJavaRecord addressCityJavaToJavaRecord(AddressCityJava input, String path) {
        return new MapperCityJavaRecord();
    }
}
