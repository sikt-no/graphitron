package no.sikt.graphitron.rewrite;

/**
 * Fixture: record-shaped consumer bean that is <em>flat</em> where its SDL input clusters fields
 * under a nested input object. {@code title} is declared at the top level of the input type;
 * {@code length} and {@code rentalDays} are declared under a directiveless grouping input that
 * names no component here, so they flatten onto this record and their
 * {@link no.sikt.graphitron.rewrite.model.CallSiteExtraction.FieldBinding} access paths are two
 * elements long while {@code title}'s stays one.
 *
 * <p>Sibling to {@link TestInputBean}, whose components mirror its SDL fields one for one at a
 * single level; this is the first record fixture whose SDL nesting and Java shape disagree.
 */
public record TestInputBeanGrouped(
    String title,
    Integer length,
    Integer rentalDays
) {
}
