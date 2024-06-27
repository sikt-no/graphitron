package fake.code.generated.transform;

import fake.code.generated.mappers.CityTypeMapper;
import fake.code.generated.mappers.RecordCityTypeMapper;
import fake.graphql.example.model.City;
import fake.graphql.example.model.RecordCity;
import graphql.schema.DataFetchingEnvironment;
import java.lang.String;
import java.util.List;
import no.fellesstudentsystem.graphitron.records.TestIDRecord;
import no.fellesstudentsystem.graphql.helpers.transform.AbstractTransformer;
import no.sikt.graphitron.jooq.generated.testdata.tables.records.CityRecord;
import org.jooq.DSLContext;

public class RecordTransformer extends AbstractTransformer {
    public RecordTransformer(DataFetchingEnvironment env, DSLContext ctx) {
        super(env, ctx);
    }

    public List<City> cityRecordToGraphType(List<CityRecord> input, String path) {
        return CityTypeMapper.recordToGraphType(input, path, this);
    }

    public List<RecordCity> recordCityToGraphType(List<TestIDRecord> input, String path) {
        return RecordCityTypeMapper.toGraphType(input, path, this);
    }

    public City cityRecordToGraphType(CityRecord input, String path) {
        return cityRecordToGraphType(List.of(input), path).stream().findFirst().orElse(null);
    }

    public RecordCity recordCityToGraphType(TestIDRecord input, String path) {
        return recordCityToGraphType(List.of(input), path).stream().findFirst().orElse(null);
    }
}
