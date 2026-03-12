import static no.sikt.graphitron.jooq.generated.testdata.pg_catalog.Tables.*;
import static no.sikt.graphitron.jooq.generated.testdata.public_.Tables.*;
import fake.graphql.example.model.City;
import java.util.List;
import java.util.Map;
import java.util.Set;
import no.sikt.graphitron.jooq.generated.testdata.public_.tables.records.AddressRecord;
import no.sikt.graphql.helpers.query.QueryHelper;
import no.sikt.graphql.helpers.selection.SelectionSet;
import org.jooq.DSLContext;
import org.jooq.Functions;
import org.jooq.Record2;
import org.jooq.SelectField;
import org.jooq.impl.DSL;

public class AddressDBQueries {
    public static Map<AddressRecord, City> citySplitQueryForAddress(DSLContext _iv_ctx,
                                                                 Set<AddressRecord> _rk_address, SelectionSet _iv_select) {
        var _a_address = ADDRESS.as("address_223244161");
        var _a_address_223244161_city = _a_address.city().as("city_621065670");
        var _iv_orderFields = _a_address_223244161_city.fields(_a_address_223244161_city.getPrimaryKey().getFieldsArray());
        return _iv_ctx
                .select(
                        DSL.row(_a_address.ADDRESS_ID).convertFrom(_iv_it -> QueryHelper.intoTableRecord(_iv_it, List.of(_a_address.ADDRESS_ID))),
                        DSL.field(
                                DSL.select(citySplitQueryForAddress_city())
                                        .from(_a_address_223244161_city)

                        )
                )
                .from(_a_address)
                .where(DSL.row(_a_address.ADDRESS_ID).in(_rk_address.stream().map(_iv_it -> _iv_it.key().valuesRow()).toList()))
                .fetchMap(Record2::value1, Record2::value2);
    }

    private static SelectField<City> citySplitQueryForAddress_city() {
        var _a_address = ADDRESS.as("address_223244161");
        var _a_address_223244161_city = _a_address.city().as("city_621065670");
        return DSL.row(
                DSL.row(_a_address_223244161_city.CITY_ID).convertFrom(_iv_it -> QueryHelper.intoTableRecord(_iv_it, List.of(_a_address_223244161_city.CITY_ID))),
                _a_address_223244161_city.getId(),
                _a_address_223244161_city.CITY_
        ).mapping(Functions.nullOnAllNull(City::new));
    }
}