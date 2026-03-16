package fake.code.generated.queries.query;

import static no.sikt.graphitron.jooq.generated.testdata.pg_catalog.Tables.*;
import static no.sikt.graphitron.jooq.generated.testdata.public_.Tables.*;
import fake.graphql.example.model.CustomerTable;
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

public class WrapperDBQueries {
    public static Map<AddressRecord, CustomerTable> queryForWrapper(DSLContext _iv_ctx,
                                                                 Set<AddressRecord> _rk_wrapper, SelectionSet _iv_select) {
        var _a_address = ADDRESS.as("address_223244161");
        var _a_address_223244161_customer = _a_address.customer().as("customer_1589604633");
        var _iv_orderFields = _a_address_223244161_customer.fields(_a_address_223244161_customer.getPrimaryKey().getFieldsArray());
        return _iv_ctx
                .select(
                        DSL.row(_a_address.ADDRESS_ID).convertFrom(_iv_it -> QueryHelper.intoTableRecord(_iv_it, List.of(_a_address.ADDRESS_ID))),
                        DSL.field(
                                DSL.select(queryForWrapper_customerTable())
                                        .from(_a_address_223244161_customer)
                        )
                )
                .from(_a_address)
                .where(DSL.row(_a_address.ADDRESS_ID).in(_rk_wrapper.stream().map(_iv_it -> _iv_it.key().valuesRow()).toList()))
                .fetchMap(Record2::value1, Record2::value2);
    }

    private static SelectField<CustomerTable> queryForWrapper_customerTable() {
        var _a_address = ADDRESS.as("address_223244161");
        var _a_address_223244161_customer = _a_address.customer().as("customer_1589604633");
        return DSL.row(_a_address_223244161_customer.getId()).mapping(Functions.nullOnAllNull(CustomerTable::new));
    }
}

