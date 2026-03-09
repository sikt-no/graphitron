import static no.sikt.graphitron.jooq.generated.testdata.pg_catalog.Tables.*;
import static no.sikt.graphitron.jooq.generated.testdata.public_.Tables.*;
import java.lang.Integer;
import java.util.List;
import java.util.Map;
import java.util.Set;
import no.sikt.graphitron.jooq.generated.testdata.public_.tables.records.CityRecord;
import no.sikt.graphql.helpers.query.QueryHelper;
import org.jooq.DSLContext;
import org.jooq.Record2;
import org.jooq.impl.DSL;

public class CityDBQueries {
    public static Map<CityRecord, Integer> countAddressesPaginatedForCity(DSLContext _iv_ctx,
            Set<CityRecord> _rk_city) {
        var _a_city = CITY.as("city_760939060");
        var _a_city_760939060_address = _a_city.address().as("address_609487378");
        return _iv_ctx
                .select(DSL.row(_a_city.CITY_ID).convertFrom(_iv_it -> QueryHelper.intoTableRecord(_iv_it, List.of(_a_city.CITY_ID))), DSL.count())
                .from(_a_city)
                .join(_a_city_760939060_address)
                .where(DSL.row(_a_city.CITY_ID).in(_rk_city.stream().map(_iv_it -> _iv_it.key().valuesRow()).toList()))
                .groupBy(_a_city.CITY_ID)
                .fetchMap(Record2::value1, Record2::value2);
    }
}
