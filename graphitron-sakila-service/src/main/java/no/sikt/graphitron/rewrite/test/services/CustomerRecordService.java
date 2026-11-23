package no.sikt.graphitron.rewrite.test.services;

import no.sikt.graphitron.rewrite.test.jooq.public_.tables.Customer;
import no.sikt.graphitron.rewrite.test.jooq.public_.tables.records.CustomerRecord;

/**
 * Compilation / execution-tier fixtures: a jOOQ {@link CustomerRecord} bound directly as a
 * {@code @service} input param, populated from <em>nested</em> grouping inputs that flatten onto the one
 * customer table: the {@code identity.customerId} {@code @nodeId} decodes into {@code customer_id},
 * and the {@code details.firstName} / {@code details.lastName} {@code @field} columns load from the
 * nested {@code details} group, each enclosing {@code Map} descended null-safely.
 *
 * <p>{@link #describeCustomerUpsert} reports the jOOQ {@code touched}-flags and values of the record
 * the service receives, without writing, so the execution tier reads the transparent-unpack contract
 * on the column axis: an omitted nested leaf stays {@code changed=false}; a present-{@code null} nested leaf
 * writes {@code NULL} ({@code changed=true}) whatever else the input sends, a decoded identity or a
 * value for the sibling leaf, on {@code first_name} and {@code last_name} though both are
 * {@code NOT NULL}; a {@code null} / omitted nullable group leaves every column under it
 * untouched; and a non-null identity inside an omitted nullable group is <em>skipped</em> rather than
 * throwing (skip-not-throw). A malformed id in a <em>present</em> identity group still throws,
 * which surfaces as a request error rather than through this method.
 */
public final class CustomerRecordService {

    private CustomerRecordService() {}

    /**
     * Reports the constructed record's {@code changed}-flag state and values for the {@code @nodeId}
     * identity ({@code customer_id}) and the two nested plain columns ({@code first_name},
     * {@code last_name}), so the execution tier can read the full transparent-unpack matrix off one
     * helper by varying only the wire input.
     */
    public static String describeCustomerUpsert(CustomerRecord in) {
        var t = Customer.CUSTOMER;
        // touched(Field) is jOOQ 3.20's non-deprecated name for the per-column changed flag.
        return "customerId[changed=" + in.touched(t.CUSTOMER_ID) + ",val=" + in.getCustomerId() + "]"
            + " first[changed=" + in.touched(t.FIRST_NAME) + ",val=" + in.getFirstName() + "]"
            + " last[changed=" + in.touched(t.LAST_NAME) + ",val=" + in.getLastName() + "]";
    }
}
