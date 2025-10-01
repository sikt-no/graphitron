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

public class CityAddressesPaginatedDBQueries {
    public static Map<Row1<Long>, Integer> countAddressesPaginatedForCity(DSLContext ctx,
                                                                             Set<Row1<Long>> cityResolverKeys) {
        var _city = CITY.as("city_1887334959");
        var city_1887334959_address = _city.address().as("address_1356285680");
        return ctx
                .select(DSL.row(_city.CITY_ID), DSL.count())
                .from(_city)
                .join(city_1887334959_address)
                .where(DSL.row(_city.CITY_ID).in(cityResolverKeys))
                .groupBy(_city.CITY_ID)
                .fetchMap(r -> r.value1().valuesRow(), Record2::value2);
    }
}
