package no.sikt.graphitron.codereferences.dummyreferences;

/**
 * Test stand-in for the generated
 * {@code <outputPackage>.schema.ValidationViolationGraphQLException}. The carrier-classifier
 * rule-9 check matches by simple name {@code ValidationViolationGraphQLException} so the
 * shadowing decision fires regardless of which output package the consumer's schema lives
 * under; this fixture exercises that branch.
 */
public class ValidationViolationGraphQLException extends RuntimeException {
    public ValidationViolationGraphQLException(String message) {
        super(message);
    }
}
