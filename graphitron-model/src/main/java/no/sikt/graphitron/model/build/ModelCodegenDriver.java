package no.sikt.graphitron.model.build;

import no.sikt.graphitron.model.boot.GraphitronModelStore;
import org.jooq.codegen.GenerationTool;
import org.jooq.meta.jaxb.Configuration;
import org.jooq.meta.jaxb.Database;
import org.jooq.meta.jaxb.Generate;
import org.jooq.meta.jaxb.Generator;
import org.jooq.meta.jaxb.Target;

/**
 * Generates the module's jOOQ compile-time surface from the fact schema.
 *
 * <p>Run from the build at {@code generate-sources}, on the project classpath rather than a
 * plugin classloader, which is what lets it call
 * {@link GraphitronModelStore#open()} directly: the driver does not restate boot, it performs
 * it, and points jOOQ's live H2 metadata generation at the store the bootstrap handed back. No
 * external database process is involved and no simulation of the DDL happens, so a DDL error or
 * a bootstrap regression fails the build with a real H2 error.
 *
 * <p>The generated tree is derived output and is never committed; the DDL resource is the single
 * source. Because this module builds before {@code graphitron}, editing the DDL fails javac in
 * every consumer that touched the changed relation.
 */
public final class ModelCodegenDriver {

    /** The package the generated classes land in; consumers import the model from here. */
    private static final String PACKAGE_NAME = "no.sikt.graphitron.model";

    private ModelCodegenDriver() {}

    /**
     * @param args one element: the directory to write the generated sources to (the build passes
     *             {@code target/generated-sources/jooq}, which build-helper then adds as a
     *             source root)
     */
    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            throw new IllegalArgumentException(
                "expected exactly one argument, the generated-sources target directory");
        }
        try (GraphitronModelStore store = GraphitronModelStore.open()) {
            GenerationTool tool = new GenerationTool();
            tool.setConnection(store.connection());
            tool.run(configuration(args[0]));
        }
    }

    private static Configuration configuration(String targetDirectory) {
        return new Configuration().withGenerator(new Generator()
            .withDatabase(new Database()
                .withName("org.jooq.meta.h2.H2Database")
                .withInputSchema("PUBLIC")
                .withIncludes(".*")
                .withExcludes("")
                // The schema declares no routines and none are planned; the H2-functions spike
                // left the scalar-alias surface as a documented contingency, not a mechanism.
                .withIncludeRoutines(false)
                .withIncludeIndexes(false)
                .withIncludeSequences(false)
                .withIncludeUDTs(false))
            .withGenerate(new Generate()
                // The DDL's COMMENT ON clauses are the schema's documentation; carrying them into
                // the generated Javadoc is what makes the model self-describing at the call site
                // as well as at the SQL prompt.
                .withComments(true)
                .withCommentsOnCatalogs(false)
                .withCommentsOnSchemas(false)
                // Every element family points at the same few parents, so the inbound key names
                // collide and jOOQ would emit an "Ambiguous key name" warning per collision. No
                // consumer navigates by generated to-many path methods; queries state their joins.
                .withImplicitJoinPathsToMany(false)
                .withDaos(false)
                .withPojos(false)
                .withInterfaces(false)
                .withGlobalObjectNames(true))
            .withTarget(new Target()
                .withPackageName(PACKAGE_NAME)
                .withDirectory(targetDirectory)
                .withClean(true)));
    }
}
