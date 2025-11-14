import static no.sikt.graphitron.jooq.generated.testdata.pg_catalog.Tables.*;
import static no.sikt.graphitron.jooq.generated.testdata.public_.Tables.*;
import fake.graphql.example.model.City;
import java.lang.Long;
import java.util.Map;
import java.util.Set;
import no.sikt.graphql.helpers.selection.SelectionSet;
import org.jooq.DSLContext;
import org.jooq.Functions;
import org.jooq.Record2;
import org.jooq.Row1;
import org.jooq.SelectField;
import org.jooq.impl.DSL;

public class AddressDBQueries {
    public static Map<Row1<Long>, City> citySplitQueryForAddress(DSLContext _iv_ctx,
                                                                 Set<Row1<Long>> _rk_address, SelectionSet _iv_select) {
        var _a_address = ADDRESS.as("address_223244161");
        var _a_address_223244161_city = _a_address.city().as("city_621065670");
        var _iv_orderFields = _a_address_223244161_city.fields(_a_address_223244161_city.getPrimaryKey().getFieldsArray());
        return _iv_ctx
                .select(
                        DSL.row(_a_address.ADDRESS_ID),
                        DSL.field(
                                DSL.select(citySplitQueryForAddress_city())
                                        .from(_a_address_223244161_city)

                        )
                )
                .from(_a_address)
                .where(DSL.row(_a_address.ADDRESS_ID).in(_rk_address))
                .fetchMap(_iv_r -> _iv_r.value1().valuesRow(), Record2::value2);
    }

    private static SelectField<City> citySplitQueryForAddress_city() {
        var _a_address = ADDRESS.as("address_223244161");
        var _a_address_223244161_city = _a_address.city().as("city_621065670");
        return DSL.row(
                DSL.row(_a_address_223244161_city.CITY_ID),
                _a_address_223244161_city.getId(),
                _a_address_223244161_city.CITY_
        ).mapping(Functions.nullOnAllNull(City::new));
    }
}