package no.sikt.graphitron.rewrite.test.services;

import no.sikt.graphitron.rewrite.test.jooq.tables.Customer;
import no.sikt.graphitron.rewrite.test.jooq.tables.records.CustomerRecord;

/**
 * R336 compilation / execution-tier fixtures: a jOOQ {@link CustomerRecord} bound directly as a
 * {@code @service} input param, populated from <em>nested</em> grouping inputs that flatten onto the one
 * customer table. The generated {@code createCustomerRecord} helper decodes the
 * {@code identity.customerId} {@code @nodeId} into {@code customer_id} and loads the
 * {@code details.firstName} / {@code details.lastName} {@code @field} columns from the nested
 * {@code details} group, descending through each enclosing {@code Map} null-safely (R336, D4).
 *
 * <p>{@code CustomerRecord} backs these fixtures rather than {@code FilmRecord} so the
 * {@code createCustomerRecord} helper does not collide with {@code ModifyFilmRecordInput}'s
 * {@code createFilmRecord} on the per-record-class helper dedup on {@code QueryFetchers}.
 *
 * <p>{@link #describeCustomerUpsert} reports the constructed record's jOOQ {@code touched}-flags and
 * values without writing, the only tier that can observe the R336 transparent-unpack contract on the
 * column axis: an omitted nested leaf stays {@code changed=false}, a present-{@code null} nested leaf is
 * {@code NULL} (changed=true), a {@code null} / omitted nullable group leaves every column under it
 * untouched, and a non-null identity inside an omitted nullable group is <em>skipped</em> rather than
 * throwing (skip-not-throw). A malformed id in a <em>present</em> identity group still throws (R195),
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
