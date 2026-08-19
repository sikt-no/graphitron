package no.sikt.graphitron.render;

import no.sikt.graphitron.javapoet.ClassName;

/**
 * Where the generated node-id encoder lives, as a function of the one thing that decides it: the
 * configured output package. {@code <outputPackage>.util.NodeIdEncoder}.
 *
 * <p>Not a fact and therefore not a store column. The class is one this generator emits, so its name
 * is generator configuration plus a fixed convention, and the place that knows the configuration is
 * render. A command row carrying it would be carrying half an emission decision, and a captured
 * relation holding it would be recording something about ourselves rather than about the consumer's
 * world.
 *
 * <p>Deliberately narrow. This is not a naming authority for the encoder family: the per-type
 * {@code decode<TypeName>} helpers on that class are minted once during schema building and read
 * everywhere else, and consolidating those belongs to the emitter migration, where a command row's
 * output key is the natural home. What this class answers is only which class to qualify a
 * {@code decodeValues} call with, which is the one part a store-sourced caller cannot know.
 */
public final class NodeIdEncoderRef {

    /** The simple name the encoder generator emits, restated here rather than imported across tiers. */
    private static final String SIMPLE_NAME = "NodeIdEncoder";

    private NodeIdEncoderRef() {}

    /** The encoder class for a run emitting into {@code outputPackage}. */
    public static ClassName of(String outputPackage) {
        return ClassName.get(outputPackage + ".util", SIMPLE_NAME);
    }
}
