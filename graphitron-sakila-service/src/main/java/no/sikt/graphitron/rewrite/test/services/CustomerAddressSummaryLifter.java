package no.sikt.graphitron.rewrite.test.services;

import org.jooq.Row1;
import org.jooq.impl.DSL;

/**
 * Lifter helper for {@link CustomerAddressSummary} used by the
 * {@code @sourceRow + @reference} composition on
 * {@code CustomerAddressSummary.address}. Demonstrates the path-keyed shape: the lifter's
 * {@code Row1<Integer>} matches the first FK hop's source-side column
 * ({@code customer.address_id} on {@code customer_address_id_fkey}), and {@code @reference}
 * resolves the FK chain to the leaf {@code address} table.
 */
public final class CustomerAddressSummaryLifter {

    private CustomerAddressSummaryLifter() {}

    public static Row1<Integer> addressIdOf(CustomerAddressSummary parent) {
        return DSL.row(parent.addressId());
    }
}
