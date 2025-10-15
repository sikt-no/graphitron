import static no.sikt.graphitron.jooq.generated.testdata.pg_catalog.Tables.*;
import static no.sikt.graphitron.jooq.generated.testdata.public_.Tables.*;
import java.lang.Integer;
import java.lang.Long;
import java.util.Map;
import java.util.Set;
import org.jooq.DSLContext;
import org.jooq.Record2;
import org.jooq.Row1;
import org.jooq.impl.DSL;

public class CityDBQueries {
    public static Map<Row1<Long>, Integer> countAddressesPaginatedForCity(DSLContext ctx,
                                                                             Set<Row1<Long>> cityResolverKeys) {
        var _a_city = CITY.as("city_760939060");
        var _a_city_760939060_address = _a_city.address().as("address_609487378");
        return ctx
                .select(DSL.row(_a_city.CITY_ID), DSL.count())
                .from(_a_city)
                .join(_a_city_760939060_address)
                .where(DSL.row(_a_city.CITY_ID).in(cityResolverKeys))
                .groupBy(_a_city.CITY_ID)
                .fetchMap(r -> r.value1().valuesRow(), Record2::value2);
    }
}