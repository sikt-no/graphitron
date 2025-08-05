package no.sikt.graphitron.example.datafetchers;

import no.sikt.graphitron.example.generated.graphitron.model.Address;
import no.sikt.graphitron.example.generated.graphitron.model.City;
import no.sikt.graphitron.example.generated.graphitron.model.Payment;
import no.sikt.graphql.NodeIdStrategy;
import no.sikt.graphql.helpers.query.QueryHelper;
import no.sikt.graphql.helpers.selection.SelectionSet;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.commons.lang3.tuple.Pair;
import org.jooq.DSLContext;
import org.jooq.Functions;
import org.jooq.Record1;
import org.jooq.Record2;
import org.jooq.impl.DSL;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static no.sikt.graphitron.example.generated.jooq.Tables.CITY;

public class CityDBQueries {
    public static Map<Record1<Integer>, List<Address>> addressesForCity(DSLContext ctx,
            NodeIdStrategy nodeIdStrategy, Set<Record1<Integer>> cityResolverKeys,
            SelectionSet select) {
        var _city = CITY.as("city_1887334959");
        var city_1887334959_address = _city.address().as("address_1356285680");
        var address_1356285680_city = city_1887334959_address.city().as("city_3305147469");
        var city_3305147469_country = address_1356285680_city.country().as("country_2919323473");
        var city_3305147469_address = address_1356285680_city.address().as("address_4030525878");
        var address_4030525878_customer = city_3305147469_address.customer().as("customer_2344256985");
        var customer_2344256985_payment = address_4030525878_customer.payment().as("payment_3770664199");
        var orderFields = city_1887334959_address.fields(city_1887334959_address.getPrimaryKey().getFieldsArray());
        return ctx
                .select(
                        DSL.row(_city.CITY_ID),
                        DSL.multiset(
                                DSL.select(
                                        DSL.row(
                                                nodeIdStrategy.createId("Address", city_1887334959_address.fields(city_1887334959_address.getPrimaryKey().getFieldsArray())),
                                                city_1887334959_address.ADDRESS_,
                                                city_1887334959_address.ADDRESS2,
                                                DSL.field(
                                                        DSL.select(
                                                                DSL.row(
                                                                        DSL.row(address_1356285680_city.CITY_ID),
                                                                        nodeIdStrategy.createId("CityType", address_1356285680_city.fields(address_1356285680_city.getPrimaryKey().getFieldsArray())),
                                                                        address_1356285680_city.CITY_,
                                                                        DSL.field(
                                                                                DSL.select(city_3305147469_country.COUNTRY_)
                                                                                .from(city_3305147469_country)
                                                                        ),
                                                                        DSL.row(
                                                                                DSL.multiset(
                                                                                        DSL.select(
                                                                                                DSL.row(
                                                                                                        customer_2344256985_payment.AMOUNT,
                                                                                                        customer_2344256985_payment.PAYMENT_DATE
                                                                                                ).mapping(Functions.nullOnAllNull(Payment::new))
                                                                                        )
                                                                                        .from(city_3305147469_address).join(address_4030525878_customer)
                                                                                        .join(customer_2344256985_payment)

                                                                                        .orderBy(customer_2344256985_payment.fields(customer_2344256985_payment.getPrimaryKey().getFieldsArray()))
                                                                                )
                                                                        ).mapping(a0 -> a0.map(Record1::value1))
                                                                ).mapping(Functions.nullOnAllNull(City::new))
                                                        )
                                                        .from(address_1356285680_city)
                                                ),
                                                city_1887334959_address.POSTAL_CODE,
                                                city_1887334959_address.PHONE
                                        ).mapping(Functions.nullOnAllNull(Address::new))
                                )
                                .from(city_1887334959_address)
                                .orderBy(orderFields)
                        )
                )
                .from(_city)
                .where(DSL.row(_city.CITY_ID).in(cityResolverKeys.stream().map(Record1::valuesRow).toList()))
                .fetchMap(Record2::value1, r -> r.value2().map(Record1::value1));
    }

    public static Map<Record1<Integer>, List<Pair<String, Address>>> addressesPaginatedForCity(
            DSLContext ctx, NodeIdStrategy nodeIdStrategy, Set<Record1<Integer>> cityResolverKeys,
            Integer pageSize, String after, SelectionSet select) {
        var _city = CITY.as("city_1887334959");
        var city_1887334959_address = _city.address().as("address_1356285680");
        var address_1356285680_city = city_1887334959_address.city().as("city_3305147469");
        var city_3305147469_country = address_1356285680_city.country().as("country_2919323473");
        var city_3305147469_address = address_1356285680_city.address().as("address_4030525878");
        var address_4030525878_customer = city_3305147469_address.customer().as("customer_2344256985");
        var customer_2344256985_payment = address_4030525878_customer.payment().as("payment_3770664199");
        var orderFields = city_1887334959_address.fields(city_1887334959_address.getPrimaryKey().getFieldsArray());
        return ctx
                .select(
                        DSL.row(_city.CITY_ID),
                        DSL.multiset(
                                DSL.select(

                                        QueryHelper.getOrderByToken(city_1887334959_address, orderFields),
                                        DSL.row(
                                                nodeIdStrategy.createId("Address", city_1887334959_address.fields(city_1887334959_address.getPrimaryKey().getFieldsArray())),
                                                city_1887334959_address.ADDRESS_,
                                                city_1887334959_address.ADDRESS2,
                                                DSL.field(
                                                        DSL.select(
                                                                DSL.row(
                                                                        DSL.row(address_1356285680_city.CITY_ID),
                                                                        nodeIdStrategy.createId("CityType", address_1356285680_city.fields(address_1356285680_city.getPrimaryKey().getFieldsArray())),
                                                                        address_1356285680_city.CITY_,
                                                                        DSL.field(
                                                                                DSL.select(city_3305147469_country.COUNTRY_)
                                                                                .from(city_3305147469_country)
                                                                        ),
                                                                        DSL.row(
                                                                                DSL.multiset(
                                                                                        DSL.select(
                                                                                                DSL.row(
                                                                                                        customer_2344256985_payment.AMOUNT,
                                                                                                        customer_2344256985_payment.PAYMENT_DATE
                                                                                                ).mapping(Functions.nullOnAllNull(Payment::new))
                                                                                        )
                                                                                        .from(city_3305147469_address).join(address_4030525878_customer)
                                                                                        .join(customer_2344256985_payment)

                                                                                        .orderBy(customer_2344256985_payment.fields(customer_2344256985_payment.getPrimaryKey().getFieldsArray()))
                                                                                )
                                                                        ).mapping(a0 -> a0.map(Record1::value1))
                                                                ).mapping(Functions.nullOnAllNull(City::new))
                                                        )
                                                        .from(address_1356285680_city)
                                                ),
                                                city_1887334959_address.POSTAL_CODE,
                                                city_1887334959_address.PHONE
                                        ).mapping(Functions.nullOnAllNull(Address::new))
                                )
                                .from(city_1887334959_address)
                                .orderBy(orderFields).seek(QueryHelper.getOrderByValues(ctx, orderFields, after))
                                .limit(pageSize + 1)

                        )
                )
                .from(_city)
                .where(DSL.row(_city.CITY_ID).in(cityResolverKeys.stream().map(Record1::valuesRow).toList()))
                .fetchMap(
                    Record2::value1,
                    it ->  it.value2().map(r -> r.value2() == null ? null : new ImmutablePair<>(r.value1(), r.value2())));
    }

    public static Map<Record1<Integer>, Integer> countAddressesPaginatedForCity(DSLContext ctx,
                                                                                NodeIdStrategy nodeIdStrategy, Set<Record1<Integer>> cityResolverKeys) {
        var _city = CITY.as("city_1887334959");
        var city_1887334959_address = _city.address().as("address_1356285680");
        return ctx
                .select(DSL.row(_city.CITY_ID), DSL.count())
                .from(_city)
                .join(city_1887334959_address)
                .where(DSL.row(_city.CITY_ID).in(cityResolverKeys.stream().map(Record1::valuesRow).toList()))
                .groupBy(_city.CITY_ID)
                .fetchMap(Record2::value1, r -> r.value2());

    }

    public static Map<String, City> cityForNode(DSLContext ctx, NodeIdStrategy nodeIdStrategy,
            Set<String> ids, SelectionSet select) {
        var _city = CITY.as("city_1887334959");
        var city_1887334959_country = _city.country().as("country_250273815");
        var city_1887334959_address = _city.address().as("address_1356285680");
        var address_1356285680_customer = city_1887334959_address.customer().as("customer_2335947072");
        var customer_2335947072_payment = address_1356285680_customer.payment().as("payment_1133592668");
        return ctx
                .select(
                        nodeIdStrategy.createId("CityType", _city.fields(_city.getPrimaryKey().getFieldsArray())),
                        DSL.row(
                                DSL.row(_city.CITY_ID),
                                nodeIdStrategy.createId("CityType", _city.fields(_city.getPrimaryKey().getFieldsArray())),
                                _city.CITY_,
                                DSL.field(
                                        DSL.select(city_1887334959_country.COUNTRY_)
                                        .from(city_1887334959_country)
                                ),
                                DSL.row(
                                        DSL.multiset(
                                                DSL.select(
                                                        DSL.row(
                                                                customer_2335947072_payment.AMOUNT,
                                                                customer_2335947072_payment.PAYMENT_DATE
                                                        ).mapping(Functions.nullOnAllNull(Payment::new))
                                                )
                                                .from(city_1887334959_address).join(address_1356285680_customer)
                                                .join(customer_2335947072_payment)

                                                .orderBy(customer_2335947072_payment.fields(customer_2335947072_payment.getPrimaryKey().getFieldsArray()))
                                        )
                                ).mapping(a0 -> a0.map(Record1::value1))
                        ).mapping(Functions.nullOnAllNull(City::new))
                )
                .from(_city)
                .where(nodeIdStrategy.hasIds("CityType", ids, _city.fields(_city.getPrimaryKey().getFieldsArray())))
                .fetchMap(Record2::value1, Record2::value2);
    }
}
