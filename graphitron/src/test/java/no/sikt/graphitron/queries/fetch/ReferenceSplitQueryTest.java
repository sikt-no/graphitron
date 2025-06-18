package no.sikt.graphitron.queries.fetch;

import no.sikt.graphitron.configuration.externalreferences.ExternalReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static no.sikt.graphitron.common.configuration.ReferencedEntry.REFERENCE_CUSTOMER_CONDITION;
import static no.sikt.graphitron.common.configuration.ReferencedEntry.REFERENCE_FILM_CONDITION;
import static no.sikt.graphitron.common.configuration.SchemaComponent.*;

@DisplayName("Fetch queries - Fetching through referenced tables with splitQuery-directive")
public class ReferenceSplitQueryTest extends ReferenceTest {
    @Override
    protected String getSubpath() {
        return super.getSubpath() + "/splitQuery";
    }

    @Override
    protected Set<ExternalReference> getExternalReferences() {
        return makeReferences(REFERENCE_CUSTOMER_CONDITION, REFERENCE_FILM_CONDITION);
    }


    //########  Validate previous query generation ########

    @Test
    @DisplayName("Foreign key columns should be selected in previous query")
    void previousQuery() {
        assertGeneratedContentContains(
                "previousQuery", Set.of(CUSTOMER_QUERY),
                "DSL.row(DSL.row(_customer.ADDRESS_ID)).mapping(Functions.nullOnAllNull(Customer::new"
        );
    }

    @Test
    @DisplayName("Primary key columns should be selected in previous query on condition path")
    void previousQueryConditionPath() {
        assertGeneratedContentContains(
                "previousQueryConditionPath", Set.of(CUSTOMER_QUERY),
                "DSL.row(DSL.row(_customer.CUSTOMER_ID)).mapping(Functions.nullOnAllNull(Customer::new"
        );
    }

    @Test
    @DisplayName("Foreign key columns should be selected in previous query in nested type")
    void previousQueryNested() {
        assertGeneratedContentContains(
                "previousQueryNested", Set.of(CUSTOMER_QUERY),
                "row(customer_2952383337_address.CITY_ID), customer_2952383337_address.getId())" +
                        ".mapping(Functions.nullOnAllNull(Address::"
        );
    }


    // ######## Validate new resolver generation ########

    // ===== Reference directive with only tables =====

    @Test
//    @DisplayName("Table path")
    @DisplayName("Given that A has a field that refers to B and that field has a single reference directive " +
                 "containing only a table B, and a relation exists from A to B, then an explicit join should be " +
                 "generated for the new resolver")
    void table() {
        assertGeneratedContentContains(
                "table", Set.of(CUSTOMER_NOT_GENERATED),
//                ".from(customer_2952383337_address"
                "_customer = CUSTOMER.as",
                "customer_2952383337_address = _customer.address().as",
                ".select(" +
                "_customer.getId()," +
                "DSL.row(customer_2952383337_address.getId()).mapping(Functions.nullOnAllNull(Address::new)))" +
                ".from(_customer)" +
                ".join(customer_2952383337_address)" +
                ".where(_customer.hasIds"
        );
    }

    @Test
//    @DisplayName("Reverse table path")
    @DisplayName("Given that A has a field that refers to B and that field has a single reference directive " +
                 "containing only a table B, and a relation does not exist from A to B, but the (inverse) relation " +
                 "from B to A do, then an implicit join should be generated for the new resolver")
    void tableBackwards() {
        assertGeneratedContentContains(
                "tableBackwards", Set.of(CUSTOMER_TABLE),
//                ".from(address_2030472956_customer"
                "_address = ADDRESS.as",
                "address_2030472956_customer = _address.customer().as",
                ".select(" +
                "_address.getId(), " +
                "DSL.row(address_2030472956_customer.getId()).mapping(Functions.nullOnAllNull(CustomerTable::new)))" +
                ".from(_address)" +
                ".join(address_2030472956_customer)" +
                ".where(_address.hasIds(addressIds))"
        );
    }

    @Test
//    @DisplayName("Reference on a nullable field")
    @DisplayName("Given that A has a field that refers to B and that field is nullable and has a single reference " +
                 "directive containing only a table B, and a relation exists from A to B, then an explicit join " +
                 "should be generated for the new resolver")
    void nullableField() {
        assertGeneratedContentContains(
                "nullableField", Set.of(CUSTOMER_NOT_GENERATED),
//                ".from(customer_2952383337_address"
                "_customer = CUSTOMER.as",
                "customer_2952383337_address = _customer.address().as",
                ".select(" +
                "_customer.getId()," +
                "DSL.row(customer_2952383337_address.getId()).mapping(Functions.nullOnAllNull(Address::new)))" +
                ".from(_customer)" +
                ".join(customer_2952383337_address)" +
                ".where(_customer.hasIds(customerIds))"
        );
    }

    @Test
//    @DisplayName("Indirect table path")
    @DisplayName("Given that A has a field that refers to C, and that field has a single reference directive " +
                 "containing only a table B, and a relation does not exists from A to C but from A to B and from B" +
                 "to C, then an explicit join should be generated for the new resolver")
    void throughTable() {
        assertGeneratedContentContains(
                "throughTable", Set.of(CUSTOMER_NOT_GENERATED),
//                ".from(customer_2952383337_address",
//                ".join(address_1214171484_city"
                "_customer = CUSTOMER.as(\"customer_2952383337\")",
                "customer_2952383337_address = _customer.address().as(\"address_1214171484\")",
                "address_1214171484_city = customer_2952383337_address.city().as(\"city_2554114265\")",
                ".select(" +
                "_customer.getId()," +
                "DSL.row(address_1214171484_city.getId()).mapping(Functions.nullOnAllNull(City::new)))" +
                ".from(_customer)" +
                ".join(customer_2952383337_address)" +
                ".join(address_1214171484_city)" +
                ".where(_customer.hasIds(customerIds)"
        );
    }

    @Test
//    @DisplayName("Indirect reverse table path")
    @DisplayName("Given that A has a field that refers to C, and that field has a single reference directive " +
                 "containing only a table B, and a relation does not exists from A to C and neither from A to B or B" +
                 "to C, but the (inverse) relation from B to A and C to B exists, then an explicit join should be " +
                 "generated for the new resolver")
    void throughTableBackwards() {
        assertGeneratedContentContains(
                "throughTableBackwards", Set.of(CUSTOMER_TABLE),
//                ".from(city_1887334959_address",
//                ".join(address_1356285680_customer"
                "_city = CITY.as(\"city_1887334959\")",
                "city_1887334959_address = _city.address().as(\"address_1356285680\")",
                "address_1356285680_customer = city_1887334959_address.customer().as(\"customer_2335947072\")",
                ".select(" +
                "_city.getId()," +
                "DSL.row(address_1356285680_customer.getId()).mapping(Functions.nullOnAllNull(CustomerTable::new)))" +
                ".from(_city)" +
                ".join(city_1887334959_address)" +
                ".join(address_1356285680_customer)" +
                ".where(_city.hasIds(cityIds))"
        );
    }

    @Test
    //    @DisplayName("Table path to the same table as source")
    @DisplayName("Given that A has a field that refers to the same type A, and this field has a single reference " +
                 "directive that only specifies table A, while a relation already exists from A to A, when a new " +
                 "resolver is generated, we expect that an explicit JOIN containing A path to A is used")
    void selfTableReference() {
        assertGeneratedContentContains(
                "selfTableReference",
                //                "_film.film().as(",
                //                "film_3747728953_film.getId()",
                //                ".from(film_3747728953_film"
                "_film = FILM.as",
                "film_3747728953_film = _film.film().as",
                """
                .select(
                _film.getId(),
                DSL.row(
                DSL.row(film_3747728953_film.FILM_ID),
                film_3747728953_film.getId()
                ).mapping(Functions.nullOnAllNull(Film::new)))
                .from(_film)
                .join(film_3747728953_film)
                .where(_film.hasIds(filmIds))
                """
        );
    }

    @Test
//    @DisplayName("Table path on a list with split query")
    @DisplayName("Given that A has a field that refers to a list of B, and this field has a single reference " +
                 "directive that only specifies B, while a relation already exists from A to B, when a new resolver" +
                 "is generated, we expect that no explicit JOIN is created, neither in the outer or inner MULTISET " +
                 "query")
    void list() {
        assertGeneratedContentContains(
                "list", Set.of(CUSTOMER_NOT_GENERATED),
//                ".from(customer_2952383337_address)",
//                "DSL.multiset(DSL.select(",
//                ".fetchMap(Record2::value1, r -> r.value2().map(Record1::value1))"
                "_customer = CUSTOMER.as",
                "customer_2952383337_address = _customer.address().as",
                ".select(" +
                "_customer.getId()," +
                "DSL.multiset(" +
                "DSL.select(DSL.row(customer_2952383337_address.getId()).mapping(Functions.nullOnAllNull(Address::new)))" +
                ".from(customer_2952383337_address)" +
                ".orderBy(orderFields)))" +
                ".from(_customer)" +
                ".where(_customer.hasIds(customerIds))" +
                ".fetchMap(Record2::value1, r -> r.value2().map(Record1::value1));"
        );
    }

    @Test
//    @DisplayName("Table path on a nullable list with split query")
    @DisplayName("Given that A has a field that refers to a NULLABLE list of B, and this field has a single " +
                 "reference directive that only specifies B, while a relation already exists from A to B, when a new " +
                 "resolver is generated, we expect that no explicit JOIN is created, neither in the outer or inner" +
                 "MULTISET query")
    void nullableList() {
        assertGeneratedContentContains(
                "nullableList", Set.of(CUSTOMER_NOT_GENERATED),
//                ".from(customer_2952383337_address)"
                    "_customer = CUSTOMER.as",
                 "customer_2952383337_address = _customer.address().as",
                 """
                 .select(
                 _customer.getId(),
                 DSL.multiset(
                 DSL.select(DSL.row(customer_2952383337_address.getId()).mapping(Functions.nullOnAllNull(Address::new)))
                 .from(customer_2952383337_address)
                 .orderBy(orderFields)))
                 .from(_customer)
                 .where(_customer.hasIds(customerIds))
                 .fetchMap(Record2::value1, r -> r.value2().map(Record1::value1));
                 """
        );
    }


    // ===== Reference directive with only keys =====

    @Test
//    @DisplayName("Key path with only one possible path between the tables")
     @DisplayName("Given that A has a field that refers to B and this field has a reference directive containing a key" +
                  "defined from same A to B, while a relation already exists from A to B, when a new resolver is " +
                  "generated, we expect that an explicit JOIN between these two tables using the key, is created")
        // TODO: Alias name is not using name based on key. Is this correct? What is the rule here?
    void keyWithSinglePath() {
        assertGeneratedContentContains(
                "keyWithSinglePath", Set.of(CUSTOMER_NOT_GENERATED),
//                ".from(customer_2952383337_address"
                "_customer = CUSTOMER.as",
                "customer_2952383337_address = _customer.address().as",
                """
                .select(
                _customer.getId(),
                DSL.row(customer_2952383337_address.getId()).mapping(Functions.nullOnAllNull(Address::new)))
                .from(_customer)
                .join(customer_2952383337_address)
                .where(_customer.hasIds(customerIds))
                """
        );
    }

    @Test
//    @DisplayName("Key path with multiple possible paths between the tables")
    @DisplayName("Given that A has a field that refers to B and that field has a reference directive containing a " +
                 "key, and multiple relations exists between A and B, when a new resolver is generated, we expect " +
                 "that the chosen key will be used to create an explicit JOIN between these two tables")
    void keyWithMultiplePaths() {
        assertGeneratedContentContains(
                "keyWithMultiplePaths",
//                ".from(film_3747728953_filmoriginallanguageidfkey"
                "_film = FILM.as",
                "film_3747728953_filmoriginallanguageidfkey = _film.filmOriginalLanguageIdFkey().as",
                ".select(" +
                "_film.getId()," +
                "DSL.row(film_3747728953_filmoriginallanguageidfkey.getId()).mapping(Functions.nullOnAllNull(Language::new)))" +
                ".from(_film)" +
                ".join(film_3747728953_filmoriginallanguageidfkey)" +
                ".where(_film.hasIds(filmIds))"
        );
    }

    @Test
//    @DisplayName("Reverse key path")
    @DisplayName("Given that A has a field that refers to B and that field has a reference directive containing a key " +
                 "defined from the inverse B to A, and no relation exist from A to B, then an implicit join between" +
                 "these two tables using the key should be generated for the new resolver")
        // TODO: Alias name is not using name based on key. Is this correct? What is the rule here?
    void keyBackwards() {
        assertGeneratedContentContains(
                "keyBackwards", Set.of(CUSTOMER_TABLE),
//                ".from(address_2030472956_customer"
               "_address = ADDRESS.as",
                "address_2030472956_customer = _address.customer().as",
                ".select(" +
                "_address.getId()," +
                "DSL.row(address_2030472956_customer.getId()).mapping(Functions.nullOnAllNull(CustomerTable::new)))" +
                ".from(_address)" +
                ".join(address_2030472956_customer)" +
                ".where(_address.hasIds(addressIds))"
        );
    }

    @Test
    @DisplayName("Indirect key path")
    void throughKey() {
        assertGeneratedContentContains(
                "throughKey", Set.of(CUSTOMER_NOT_GENERATED),
//                ".from(customer_2952383337_address",
//                ".join(address_1214171484_city"
                "_customer = CUSTOMER.as",
                "customer_2952383337_address = _customer.address().as",
                "address_1214171484_city = customer_2952383337_address.city().as",
                """
                .select(
                        _customer.getId(),
                        DSL.row(address_1214171484_city.getId()).mapping(Functions.nullOnAllNull(City::new))
                )
                .from(_customer)
                .join(customer_2952383337_address)
                .join(address_1214171484_city)
                .where(_customer.hasIds(customerIds))
                """
        );
    }

    @Test
    @DisplayName("Key path to the same table as source")
    void selfKeyReference() {
        assertGeneratedContentContains(
                "selfKeyReference",
//                "_film.film().as(",
//                "film_3747728953_film.getId()",
//                ".from(film_3747728953_film"
               "_film = FILM.as",
               "film_3747728953_film = _film.film().as",
                """
                .select(
                        _film.getId(),
                        DSL.row(
                                DSL.row(film_3747728953_film.FILM_ID),
                                film_3747728953_film.getId()
                        ).mapping(Functions.nullOnAllNull(Film::new))
                )
                .from(_film)
                .join(film_3747728953_film)
                .where(_film.hasIds(filmIds))
                """
        );
    }


    // ===== Reference directive with only conditions =====

    @Test
//    @DisplayName("Condition path")
    @DisplayName("When A has a field that refers to B and that field has a single reference directive containing " +
                 "only a condition, and no implicit relations exists between A and B, then JOIN and ON clauses " +
                 "should be generated. The JOIN clause should contain table B, and the ON clause should use the " +
                 "condition method with tables A and B as arguments for the new resolver")
    void condition() {
        assertGeneratedContentContains(
                "condition", Set.of(CUSTOMER_NOT_GENERATED),
               /* "join(customer_address_addresscustomer_address).on(",
                ".addressCustomer(customer_address, customer_address_addresscustomer_address)",
                ".where(_customer.CUSTOMER_ID.eq(customer_address.CUSTOMER_ID"*/
                "_customer = CUSTOMER.as",
                "customer_address = ADDRESS.as",
                ".select(" +
                "_customer.getId()," +
                "DSL.row(" +
                "customer_address.getId()" +
                ").mapping(Functions.nullOnAllNull(Address::new))" +
                ")" +
                ".from(_customer)" +
                ".join(customer_address)" +
                ".on(no.sikt.graphitron.codereferences.conditions.ReferenceCustomerCondition.addressCustomer(_customer, customer_address))"
        );
    }

    @Test
    @DisplayName("Indirect condition path")
    void throughCondition() {
        assertGeneratedContentContains(
                "throughCondition", Set.of(CUSTOMER_NOT_GENERATED),
//                ".join(customer_city_citycustomer_city).on(",
//                ".cityCustomer(customer_city, customer_city_citycustomer_city)"
                "_customer = CUSTOMER.as",
                "customer_city = CITY.as",
                """
                .select(
                        _customer.getId(),
                        DSL.row(customer_city.getId()).mapping(Functions.nullOnAllNull(City::new))
                )
                .from(_customer)
                .join(customer_city)
                .on(no.sikt.graphitron.codereferences.conditions.ReferenceCustomerCondition.cityCustomer(_customer, customer_city))
                .where(_customer.hasIds(customerIds))
                """
        );
    }

    @Test
    @DisplayName("Condition path to the same table as source")
    void selfConditionReference() {
        assertGeneratedContentContains(
                "selfConditionReference",
//                "FILM.as(",
//                "film_sequel_sequel_film.getId()",
//                ".join(film_sequel_sequel_film).on(",
//                ".sequel(film_sequel, film_sequel_sequel_film"
                "_film = FILM.as",
                "film_sequel = FILM.as",
                """
                .select(
                        _film.getId(),
                        DSL.row(
                                DSL.row(film_sequel.FILM_ID),
                                film_sequel.getId()
                        ).mapping(Functions.nullOnAllNull(Film::new))
                )
                .from(_film)
                .join(film_sequel)
                .on(no.sikt.graphitron.codereferences.conditions.ReferenceFilmCondition.sequel(_film, film_sequel))
                .where(_film.hasIds(filmIds))
                """
        );
    }


    // ===== Reference directive with table and condition =====

    @Test
    @DisplayName("Both a table and a condition set on the same path element")
    void tableAndCondition() {
        assertGeneratedContentContains(
                "tableAndCondition", Set.of(CUSTOMER_NOT_GENERATED),
               /* "customer_address_addresscustomer_address = ADDRESS.as(", // Note, no implicit join anymore.
                ".join(customer_address_addresscustomer_address).on(",
                ".addressCustomer(customer_address, customer_address_addresscustomer_address)" // Note, condition overrides as it uses "on".*/
                "_customer = CUSTOMER.as",
                "customer_address = ADDRESS.as",
                """
                .select(
                        _customer.getId(),
                        DSL.row(customer_address.getId()).mapping(Functions.nullOnAllNull(Address::new))
                )
                .from(_customer)
                .join(customer_address)
                .on(no.sikt.graphitron.codereferences.conditions.ReferenceCustomerCondition.addressCustomer(_customer, customer_address))
                .where(_customer.hasIds(customerIds))
                """
        );
    }


    // ===== Reference directive with key and condition =====

    @Test
    @DisplayName("Both a key and a condition set on the same path element")
    void keyAndCondition() {
        assertGeneratedContentContains(
                "keyAndCondition", Set.of(CUSTOMER_NOT_GENERATED),
                "customer_2952383337_address = _customer.address().as(",// Note, implicit join is present when we use a key, but not table.
                /*".from(customer_2952383337_address).where(",
                ".addressCustomer(_customer, customer_2952383337_address)", // Note, no condition override unlike table case.
                ".where(_customer.hasIds(customerIds"*/
                "_customer = CUSTOMER.as",
                "customer_2952383337_address = _customer.address().as",
                """
                .select(
                        _customer.getId(),
                        DSL.row(customer_2952383337_address.getId()).mapping(Functions.nullOnAllNull(Address::new))
                )
                .from(_customer)
                .join(customer_2952383337_address)
                .where(_customer.hasIds(customerIds))
                .and(no.sikt.graphitron.codereferences.conditions.ReferenceCustomerCondition.addressCustomer(_customer, customer_2952383337_address))
                """
        );
    }


    // ===== Other combinations of directives =====

    @Test
    @DisplayName("Reference from multi table interface")
    void fromMultitableInterface() {
        assertGeneratedContentContains(
                "fromMultitableInterface", Set.of(CUSTOMER_TABLE),
//                "_payment.getId(), DSL.field(",
//                "CustomerTable::new))).from(payment_425747824_customer)",
//                ".from(_payment).where(_payment.hasIds(paymentIds))"
               "_payment = PAYMENT.as",
               "payment_425747824_customer = _payment.customer().as",
                """
                .select(
                        _payment.getId(),
                        DSL.row(payment_425747824_customer.getId()).mapping(Functions.nullOnAllNull(CustomerTable::new))
                )
                .from(_payment)
                .join(payment_425747824_customer)
                .where(_payment.hasIds(paymentIds))
                """
        );
    }
}
