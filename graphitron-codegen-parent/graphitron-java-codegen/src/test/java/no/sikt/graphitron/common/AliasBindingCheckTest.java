package no.sikt.graphitron.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Alias binding check - the guard that keeps unbound table aliases out of generated queries")
public class AliasBindingCheckTest {

    /**
     * The shape count queries had before they nested the count in a correlated subquery: the alias the reference path
     * starts from is declared, joined against and correlated against, but is in neither the FROM nor the JOIN. jOOQ
     * renders the qualifier anyway and the database rejects the statement.
     */
    private static final String UNBOUND = """
            public class CustomerDBQueries {
                public static Map<CustomerRecord, Integer> countPaymentsPaginatedForCustomer(DSLContext _iv_ctx,
                        Set<CustomerRecord> _rk_customer) {
                    var _a_customer = CUSTOMER.as("customer_2168032777");
                    var _a_customer_paymentspaginated = CUSTOMER.as("customer_3807220159");
                    var _a_customer_paymentspaginated_payments_payment = PAYMENT.as("payment_4166410193");
                    return _iv_ctx
                            .select(DSL.row(_a_customer.CUSTOMER_ID), DSL.count())
                            .from(_a_customer)
                            .join(_a_customer_paymentspaginated_payments_payment)
                            .on(ReferenceCustomerCondition.payments(_a_customer_paymentspaginated, _a_customer_paymentspaginated_payments_payment))
                            .where(DSL.row(_a_customer.CUSTOMER_ID).in(_rk_customer))
                            .and(_a_customer.CUSTOMER_ID.eq(_a_customer_paymentspaginated.CUSTOMER_ID))
                            .groupBy(_a_customer.CUSTOMER_ID)
                            .fetchMap(Record2::value1, Record2::value2);
                }
            }
            """;

    private static final String BOUND = """
            public class CustomerDBQueries {
                public static Map<CustomerRecord, Integer> countPaymentsPaginatedForCustomer(DSLContext _iv_ctx,
                        Set<CustomerRecord> _rk_customer) {
                    var _a_customer = CUSTOMER.as("customer_2168032777");
                    var _a_customer_paymentspaginated = CUSTOMER.as("customer_3807220159");
                    var _a_customer_paymentspaginated_payments_payment = PAYMENT.as("payment_4166410193");
                    return _iv_ctx
                            .select(DSL.row(_a_customer.CUSTOMER_ID), DSL.field(
                                    DSL.select(DSL.count())
                                    .from(_a_customer_paymentspaginated)
                                    .join(_a_customer_paymentspaginated_payments_payment)
                                    .on(ReferenceCustomerCondition.payments(_a_customer_paymentspaginated, _a_customer_paymentspaginated_payments_payment))
                                    .where(_a_customer.CUSTOMER_ID.eq(_a_customer_paymentspaginated.CUSTOMER_ID))
                            ))
                            .from(_a_customer)
                            .where(DSL.row(_a_customer.CUSTOMER_ID).in(_rk_customer))
                            .fetchMap(Record2::value1, Record2::value2);
                }
            }
            """;

    /**
     * jOOQ joins an implicit path alias on its own, so the alias needs no join of its own. It is bound by the alias it
     * hangs off, which the query does select from, and that binding reaches across the fragment helper the query
     * embeds.
     */
    private static final String IMPLICIT_PATH_ACROSS_A_FRAGMENT = """
            public class QueryDBQueries {
                public static Customer queryForQuery(DSLContext _iv_ctx, SelectionSet _iv_select) {
                    var _a_customer = CUSTOMER.as("customer_2168032777");
                    var _a_customer_2168032777_address = _a_customer.address().as("address_2138977089");
                    return _iv_ctx
                            .select(queryForQuery_customer())
                            .from(_a_customer)
                            .fetchOne(_iv_it -> _iv_it.into(Customer.class));
                }

                private static SelectField<Customer> queryForQuery_customer() {
                    var _a_customer = CUSTOMER.as("customer_2168032777");
                    var _a_customer_2168032777_address = _a_customer.address().as("address_2138977089");
                    return DSL.row(
                            _a_customer.getId(),
                            DSL.field(
                                    DSL.select(_a_customer_2168032777_address.getId())
                                            .from(_a_customer_2168032777_address)
                            )
                    ).mapping(Functions.nullOnAllNull(Customer::new));
                }
            }
            """;

    /** A class of fragments has no terminal operation, so whichever query embeds them owns the FROM tree. */
    private static final String FRAGMENTS_ONLY = """
            public class CustomerDBQueries {
                public static SelectField<Customer> customerForQuery() {
                    var _a_customer = CUSTOMER.as("customer_2168032777");
                    var _a_customer_2168032777_payment = _a_customer.payment().as("payment_521722061");
                    return DSL.row(DSL.field(DSL.select(DSL.count()).from(_a_customer_2168032777_payment)));
                }
            }
            """;

    @Test
    @DisplayName("Reports an alias that only a join condition and correlation predicates mention")
    void reportsUnboundAlias() {
        assertThat(violationsIn(UNBOUND))
                .singleElement(org.assertj.core.api.InstanceOfAssertFactories.STRING)
                .contains("_a_customer_paymentspaginated")
                .doesNotContain("_a_customer_paymentspaginated_payments_payment");
    }

    @Test
    @DisplayName("Accepts the same count once it nests in a correlated subquery")
    void acceptsCorrelatedSubquery() {
        assertThat(violationsIn(BOUND)).isEmpty();
    }

    @Test
    @DisplayName("Accepts an implicit path alias bound through the fragment helper its query embeds")
    void acceptsImplicitPathAcrossFragment() {
        assertThat(violationsIn(IMPLICIT_PATH_ACROSS_A_FRAGMENT)).isEmpty();
    }

    @Test
    @DisplayName("Skips a class that only holds query fragments")
    void skipsFragments() {
        assertThat(violationsIn(FRAGMENTS_ONLY)).isEmpty();
    }

    private static List<String> violationsIn(String generated) {
        var violations = new ArrayList<String>();
        AliasBindingCheck.checkFile("Generated", generated.lines().toList(), violations);
        return violations;
    }
}
