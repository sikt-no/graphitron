package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.rewrite.schema.input.SchemaInput;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * Per-invocation configuration the rewrite generator runs against.
 *
 * <p>Minimal today: just the ordered list of schema inputs and the project
 * basedir. The rewrite-owned Maven plugin will expand this record with the
 * remaining plugin knobs (output paths, packages, named references, scalars,
 * page-size cap); the record's canonical constructor absorbs those fields
 * when that plan lands. Callers that construct a context today stay working;
 * new fields are purely additive.
 *
 * <p>This context is the rewrite-core boundary for externally supplied
 * configuration. It is never held in a static or {@link ThreadLocal}; it
 * flows through the constructor of
 * {@link GraphQLRewriteGenerator#GraphQLRewriteGenerator(RewriteContext)}
 * and is accessible to every pipeline stage through the generator instance.
 */
public record RewriteContext(
    List<SchemaInput> schemaInputs,
    Path basedir
) {
    public RewriteContext {
        Objects.requireNonNull(schemaInputs, "schemaInputs");
        Objects.requireNonNull(basedir, "basedir");
        schemaInputs = List.copyOf(schemaInputs);
    }
}
