package no.sikt.graphitron.rewrite;

/**
 * Fixture: record-shaped consumer bean whose component names diverge from the SDL input field
 * names they bind to. The matching SDL input type bridges the divergence with {@code @field(name:)}:
 * {@code title: String @field(name: "heading")} and {@code rating: Int @field(name: "score")}. The
 * resolved {@code FieldBinding}s must carry {@code javaFieldName} = the component name (the directive
 * value) while {@code sdlFieldName} stays the SDL field name (the {@code Map} key the helper reads).
 *
 * <p>Sibling to {@link TestInputBean}, whose components are exact name mirrors of its SDL fields;
 * this is the first record fixture whose member names do not coincide with the SDL field names.
 */
public record TestInputBeanRenamed(
    String heading,
    Integer score
) {
}
