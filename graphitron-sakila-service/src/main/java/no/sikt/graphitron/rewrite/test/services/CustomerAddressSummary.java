package no.sikt.graphitron.rewrite.test.services;

/**
 * Fixture: a free-form (non-table-backed) {@code @record} payload modelling a
 * third-party DTO that carries a customer's {@code address_id} but no jOOQ FK metadata. The
 * schema's {@code CustomerAddressSummary} type points at this record via
 * {@code @record(record: {className: "..."})}. The payload's {@code address} child field
 * carries {@code @sourceRow + @reference(path: [{key: "customer_address_id_fkey"}])};
 * {@link CustomerAddressSummaryLifter#addressIdOf} materialises the lifter's
 * {@code Row1<Integer>} for the path-keyed DataLoader fetch.
 */
public record CustomerAddressSummary(Integer customerId, Integer addressId) {}
