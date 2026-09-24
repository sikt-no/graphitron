package no.sikt.graphitron.model.grammar;

/**
 * The values {@code @asConnection} falls back to where an author states none.
 *
 * <p>Here for the same reason {@link ConnectionNaming} is: the expansion is read by capture and by
 * the generator, and a fallback either side spells for itself is a fact with two homes. This module
 * is the one both can see, so the constant lives here and is read rather than restated.
 *
 * <p>It used to be spelled twice, once in {@code the emitted anchoring} and once on the generator's
 * {@code FieldWrapper}, with a test comparing an emitted row against the generator's field to hold
 * them equal. That test was standing in for a constant the two were said to be unable to share,
 * which was not so: the generator depends on this module and could always have read a constant
 * here. Two declarations and a test are what one declaration and the compiler now do.
 */
public final class ConnectionDefaults {

    /**
     * The page size a connection carrier gets when its {@code @asConnection} omits
     * {@code defaultFirstValue}. Written into the minted {@code first} argument's default by
     * capture, and read by the generator when it resolves a carrier's page size.
     */
    public static final int DEFAULT_PAGE_SIZE = 100;

    private ConnectionDefaults() {
    }
}
