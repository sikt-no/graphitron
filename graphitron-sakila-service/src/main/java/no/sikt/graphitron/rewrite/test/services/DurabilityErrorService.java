package no.sikt.graphitron.rewrite.test.services;

import org.jooq.DSLContext;
import org.jooq.Record1;

import java.util.Map;
import java.util.Set;

/**
 * Durability-test fixture: a {@code @service}-backed leaf that always throws.
 * Wired onto {@code Film} via the synthetic SDL field {@code durabilityError}; querying
 * it in a {@code @mutation} response forces graphql-java to invoke the field's DataLoader
 * batch mid-traversal, which throws here and surfaces as a field error on every Film row
 * in the response.
 *
 * <p>The point is to prove the two-step DML emit's durability invariant: the row was
 * committed when the mutation fetcher's {@code transactionResult(...)} returned, so a
 * field error during the response SELECT or nested traversal *cannot* undo it. Tests
 * assert the row exists in the DB after the response, and the response carries the
 * GraphQL field error keyed on {@code durabilityError}.
 *
 * <p>Method shape matches the existing {@code @service} child-field convention
 * ({@code Set<Record1<Integer>>} keys, {@code DSLContext}, returns {@code Map}). The body
 * never reaches the Map construction; it throws immediately.
 */
public final class DurabilityErrorService {

    private DurabilityErrorService() {}

    public static Map<Record1<Integer>, String> synthesize(Set<Record1<Integer>> filmIds, DSLContext dsl) {
        throw new RuntimeException("R75 durability-test fixture: synthetic mid-traversal error");
    }
}
