package no.sikt.graphitron.model.run;

import no.sikt.graphitron.model.capture.document.GraphQLAssemblyCapture.AssemblyReading;
import no.sikt.graphitron.model.schema.EmittedRegistry;
import no.sikt.graphitron.model.schema.SchemaAssembly;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The two schemas one capture builds: the documents as written, and the post-synthesis schema the
 * macros' rows make of them.
 *
 * <p>Neither is stored. The gatherers build them on the way, and a run that generates renders
 * from them rather than building them again.
 *
 * @param written     the documents as written, composed and assembled, with what each stage refused
 * @param synthesised the post-synthesis schema, which is what graphitron emits; absent where the
 *                    loading rewrites refused, there being no composition to synthesise over
 * @param narrowings  the generated types whose inherited tags deriving the post-synthesis schema
 *                    narrowed
 */
public record CapturedSchema(AssemblyReading written, Optional<SchemaAssembly> synthesised,
                             List<EmittedRegistry.TagNarrowing> narrowings) {

    public CapturedSchema {
        Objects.requireNonNull(written, "written");
        Objects.requireNonNull(synthesised, "synthesised");
        narrowings = List.copyOf(narrowings);
    }
}
