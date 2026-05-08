package no.sikt.graphitron.rewrite.test.services;

/**
 * R110 Story 1 fixture: hand-rolled service that returns a {@link CustomerAddressSummary} for
 * a given customer id. The fixture exists for the compile-spec tier, which exercises the
 * generator's {@code @sourceRow + @reference} emit path through {@code mvn compile} on
 * {@code graphitron-sakila-example}; the body is a deterministic stub (no DB round-trip)
 * since the load-bearing assertion is "the generated source compiles", not query semantics.
 */
public final class CustomerAddressSummaryService {

    private CustomerAddressSummaryService() {}

    public static CustomerAddressSummary lookup(Integer customerId) {
        return new CustomerAddressSummary(customerId, /*addressId=*/ 1);
    }
}
