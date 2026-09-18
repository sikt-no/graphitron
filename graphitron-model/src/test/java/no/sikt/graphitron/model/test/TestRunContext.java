package no.sikt.graphitron.model.test;

import no.sikt.graphitron.model.config.RunContext;

import java.nio.file.Path;
import java.util.List;

/**
 * The run a fixture stands in for: enough of a {@link RunContext} to name the generated jOOQ
 * package a case reads its catalog from, and nothing else.
 *
 * <p>Here rather than in the generator's test tree, where the same six lines used to live, because
 * nothing about it is the generator's. {@link RunContext} is this module's type, the jOOQ package
 * is generated upstream of this module and is what every store-reading case here already names,
 * and the output package is a string no reader of these cases looks at. A gate over the store that
 * had to reach into the generator's fixtures to state its own inputs was a gate filed in the wrong
 * module, which is what moving those cases down here corrects.
 */
public final class TestRunContext {

    /** The jOOQ package the fixtures' catalog is generated into, upstream of this module. */
    public static final String JOOQ_PACKAGE = "no.sikt.graphitron.rewrite.test.jooq";

    /** A name no emitted source exists under, the cases here emitting none. */
    public static final String OUTPUT_PACKAGE = "fake.code.generated";

    private TestRunContext() {}

    /** The context a store-reading case runs under. */
    public static RunContext of() {
        return new RunContext(List.of(), Path.of(""), "TestRunContext", Path.of(""),
            OUTPUT_PACKAGE, JOOQ_PACKAGE);
    }
}
