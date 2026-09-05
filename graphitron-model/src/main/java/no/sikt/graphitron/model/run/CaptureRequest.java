package no.sikt.graphitron.model.run;

import graphql.schema.idl.TypeDefinitionRegistry;
import no.sikt.graphitron.model.capture.FactCapture;
import no.sikt.graphitron.model.classpath.CompletionData;
import no.sikt.graphitron.model.derive.ClassifiedRun;
import no.sikt.graphitron.model.jooq.JooqCatalog;
import no.sikt.graphitron.model.schema.SchemaAssembly;
import no.sikt.graphitron.model.schema.SdlVerdicts;
import no.sikt.graphitron.model.schema.input.SchemaInput;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * One pass's whole capture, as a value: which graph it writes under, what that graph declared, and
 * the three corpora a pass reads. Everything about the pass, and nothing about where the facts go,
 * which is {@link CapturePort}'s half and the reason the two are separate types. A caller can hold
 * a port across many passes precisely because a request carries no store, and can build a request
 * before any store exists because it names none.
 *
 * <p>The components are the arguments {@link FactCapture} took as a positional list, unchanged in
 * meaning. Collecting them stops a pass from being describable only by calling the entry point,
 * which is what let the generator's two capture arms drift apart in what they passed.
 *
 * @param graph       the coordinate this pass writes under
 * @param config      what the graph declared about itself, {@link SubjectConfig#none()} for a
 *                    caller that declared nothing
 * @param registry    the parsed document, before the synthesis rewrites: the store transcribes what
 *                    the author wrote, never what graphitron injected into it
 * @param assembly    the assembly of {@code registry} and no other, the assembly stage's verdict
 *                    being a fact about the document the rest of the rows describe
 * @param verdicts    the read stages' verdicts, {@link SdlVerdicts#none()} where no stage ran
 * @param attribution which input each source name came from
 * @param jooq        the catalog to walk, or {@code null} for a pass with none in hand
 * @param extensions  the classpath census this pass scanned
 * @param classpathStamps what the census verified each jar of that scan against, keyed by path,
 *                    empty for a caller whose census carries none. The retention decision reads
 *                    these instead of re-hashing: they have to describe the bytes the rows came
 *                    from, and a value read later describes bytes nobody parsed
 * @param classified  whether the pass has a classified model for the detections to run against;
 *                    {@link ClassifiedRun.Absent} is the failure arm's, where a stage refused the
 *                    document and there is no walk to gate a detection on
 */
public record CaptureRequest(GraphIdentity graph, SubjectConfig config,
                             TypeDefinitionRegistry registry, SchemaAssembly assembly,
                             SdlVerdicts verdicts, Map<String, SchemaInput> attribution,
                             JooqCatalog jooq, List<CompletionData.ExternalReference> extensions,
                             Map<String, String> classpathStamps,
                             ClassifiedRun classified) {
    public CaptureRequest {
        Objects.requireNonNull(graph, "graph");
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(registry, "registry");
        Objects.requireNonNull(assembly, "assembly");
        Objects.requireNonNull(verdicts, "verdicts");
        Objects.requireNonNull(attribution, "attribution");
        Objects.requireNonNull(extensions, "extensions");
        Objects.requireNonNull(classpathStamps, "classpathStamps");
        Objects.requireNonNull(classified, "classified");
    }

    /** What this request writes, against whichever store a port hands it. */
    RunStore.CaptureBody body() {
        return (dsl, warm) -> FactCapture.capture(dsl, warm, graph, config, registry, assembly,
            verdicts, attribution, jooq, extensions, classpathStamps);
    }
}
