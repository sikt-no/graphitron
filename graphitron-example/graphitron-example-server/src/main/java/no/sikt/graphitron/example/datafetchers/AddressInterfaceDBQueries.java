package no.sikt.graphitron.example.datafetchers;

import no.sikt.graphitron.example.generated.graphitron.model.City;
import no.sikt.graphitron.example.generated.jooq.tables.records.AddressRecord;
import no.sikt.graphql.NodeIdStrategy;
import no.sikt.graphql.helpers.query.QueryHelper;
import no.sikt.graphql.helpers.selection.SelectionSet;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.Result;
import org.jooq.impl.DSL;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static no.sikt.graphitron.example.generated.graphitron.queries.AddressInAnotherAreaDBQueries.cityForAddressInAnotherArea;
import static no.sikt.graphitron.example.generated.jooq.Tables.ADDRESS;
import static no.sikt.graphitron.example.generated.jooq.Tables.LANGUAGE;

public class AddressInterfaceDBQueries {

    /*
    Eksempel på ny DB query som bare returnerer en Result<Record> for single table interface
    Det gjøres ikke noe mapping, så kolonner fra f.eks. wrappertyper flates ut
     */
    public static Result<? extends Record> singleTableInterfaceForQuery(DSLContext _iv_ctx, NodeIdStrategy _iv_nodeIdStrategy, SelectionSet _iv_select) {
        var _a_address = ADDRESS.as("address_223244161");
        var _a_address_inner = ADDRESS.as("address_447414342");
        var _a_language = LANGUAGE.as("language_3908262635");
        var _a_city = _a_address.city().as("city_621065670");
        var _a_country = _a_city.country();
        var _iv_orderFields = _a_address.fields(_a_address.getPrimaryKey().getFieldsArray());
        return _iv_ctx.select(
                        DSL.row(_a_address.ADDRESS_ID).convertFrom(_iv_it -> QueryHelper.intoTableRecord(_iv_it, List.of(_a_address.ADDRESS_ID))).as("address_pkey"),
                        _a_address.POSTAL_CODE,
                        _a_address.ADDRESS_ID,
                        _a_address.ADDRESS_,
                        _a_address.ADDRESS2,
                        _iv_nodeIdStrategy.createId("TEST_1", _a_address.ADDRESS_ID).as("AddressImplOne_id"),
                        _iv_nodeIdStrategy.createId("TEST_2", _a_address.ADDRESS_ID).as("AddressImplTwo_id"),
                        DSL.field(
                                DSL.select(
                                                DSL.row(
                                                        DSL.row(_a_language.LANGUAGE_ID).convertFrom(_iv_it -> QueryHelper.intoTableRecord(_iv_it, List.of(_a_language.LANGUAGE_ID))),
                                                        _iv_nodeIdStrategy.createId("Language", _a_language.LANGUAGE_ID),
                                                        _a_language.NAME)
                                        )
                                        .from(_a_address_inner)
                                        .join(_a_language)
                                        .on(no.sikt.graphitron.example.service.conditions.LanguageConditions.spokenLanguageForAddressByPostalCode(_a_address_inner, _a_language))
                                        .where(_a_address.ADDRESS_ID.eq(_a_address_inner.ADDRESS_ID))
                        ).as("spokenLanguage"),
                        DSL.field(
                                DSL.select(
                                                DSL.row(_a_city.CITY_ID,
                                                        DSL.field(
                                                                DSL.select(DSL.row(_a_country.COUNTRY_ID)) //.as("country") etter row blir ignorert
                                                                        .from(_a_country)

                                                        )//.as("country") Får feil
                                                )
                                        )
                                        .from(_a_city)

                        ).as("cityAsSubquery")
                )
                .from(_a_address)
                .where(_a_address.POSTAL_CODE.in("9668", "22474"))
                .orderBy(_iv_orderFields)
                .fetch();
    }

    public static Map<AddressRecord, City> city(DSLContext _iv_ctx,
                                                NodeIdStrategy _iv_nodeIdStrategy, Set<AddressRecord> _rk_addressInAnotherArea,
                                                SelectionSet _iv_select) {
        // bruker bare DTO videre her
        return cityForAddressInAnotherArea(_iv_ctx, _iv_nodeIdStrategy, _rk_addressInAnotherArea, _iv_select);
    }
}
