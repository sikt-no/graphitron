package no.sikt.graphitron.lsp.parsing;

/**
 * Marker for "what completes / validates / hovers at this coordinate." Each
 * arm is a sealed record; the actual completion / diagnostic / hover code
 * lives in the consumer files ({@code ClassNameCompletions}, {@code Diagnostics},
 * {@code Hovers}). The overlay is the dispatch index, not the implementation.
 *
 * <p>Capabilities express what is uniformly true at a coordinate; the sealed
 * switch in each consumer expresses what varies by identity. New consumer
 * arms slot in by adding a Behavior variant and an arm to the consumer's
 * switch.
 */
public sealed interface Behavior {

    /** Class-name binding: complete and validate against the catalog scan. */
    record ClassNameBinding() implements Behavior {}

    /**
     * Method-name binding: depends on a sibling {@code className} coordinate
     * being filled in to know which class's methods to offer.
     */
    record MethodNameBinding(SchemaCoordinate classNameCoord) implements Behavior {}

    /** Catalog table-name binding (validates against jOOQ catalog). */
    record CatalogTableBinding() implements Behavior {}

    /**
     * Catalog column-name binding (validates against the enclosing
     * {@code @table}'s column set).
     */
    record CatalogColumnBinding() implements Behavior {}

    /** Catalog FK-name binding. */
    record CatalogFkBinding() implements Behavior {}

    /**
     * {@code argMapping} content-syntax binding. The actual
     * {@code "javaParam: graphqlArg, ..."} parser lives in a sibling roadmap
     * item; this arm is a marker that R119 ships and that follow-up wires up.
     */
    record ArgMappingBinding() implements Behavior {}

    /**
     * {@code @scalarType(scalar:)} binding: completes against the
     * extended-scalars convention table (preferring the entry that matches
     * the enclosing {@code scalar X} declaration's name), and validates the
     * shape of the FQN plus that the named class is present in the catalog's
     * external-reference scan.
     */
    record ScalarTypeBinding() implements Behavior {}
}
