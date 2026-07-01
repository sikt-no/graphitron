package no.sikt.graphitron.mcp.rag;

import java.util.Optional;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Best-effort quieting of the console noise the RAG warms emit when {@code graphitron:dev} starts
 * (R409). The heavy RAG dependency set (langchain4j ONNX + Lucene) is dependency-quarantined in this
 * module (R341 / R372) precisely so the plugin's compile surface never learns the logger names below;
 * keeping the suppression here rather than in {@code DevMojo} is the same
 * separation-of-business-logic-from-API axis the {@code graphitron-lsp} / {@code graphitron-mcp}
 * split serves ({@link BgeEmbedder} names the seam). The two warmed loggers are facts about the RAG
 * dependency set, not about the Mojo.
 *
 * <p>Two of the three startup-warning groups are graphitron-emitted and handled here; the third
 * (Maven-runtime jansi / guava JVM warnings) is printed by the JVM for Maven's own {@code lib/} jars
 * before any plugin code runs, so a plugin cannot un-print it and it is documented instead (see the
 * "Quieting startup warnings" note in {@code getting-started.adoc}).
 *
 * <p><strong>Group 1, DJL tokenizer (mute).</strong> {@code maxLength is not explicitly specified,
 * use modelMaxLength: 512}, emitted by the DJL HuggingFace tokenizer inside langchain4j's bge ONNX
 * model when the embedder warms. The bundled model exposes no public knob for {@code maxLength}, so
 * this is non-actionable noise with no perf signal: muting it is pure win. It is muted defensively
 * across logging providers, the slf4j-simple level property <em>and</em> the tokenizer logger's
 * {@link java.util.logging} level are both raised, so a Maven binding swap or a change in DJL's lazy
 * logger-init timing degrades to "noise returns" rather than silently making a single-provider
 * approach a no-op.
 *
 * <p><strong>Group 2, Lucene vectorization (demote, do not swallow).</strong> {@code Java vector
 * incubator module is not readable ... pass '--add-modules jdk.incubator.vector'}, from Lucene's
 * {@code VectorizationProvider} when the docs-index store warms. This warning is actionable and
 * directional: the named flag both silences it and enables the faster Vector API path. Muting it in
 * code would hide a fixable, perf-relevant condition, so its multi-line library-internal warning is
 * demoted and, only when the incubator module is actually absent, one concise graphitron-owned line
 * carries the actionable fix in its place (net: one line instead of a Lucene stack dump). If the user
 * already passed the flag the module is present, Lucene never warns, and we stay silent.
 *
 * <p><strong>Scope.</strong> These are <em>process-global</em> mutations (a JUL level, a JVM system
 * property), so this helper is scoped to the {@code dev} goal alone: it is <strong>not</strong>
 * shared with {@code GenerateMojo} / {@code ValidateMojo}, <strong>not</strong> triggered by the
 * {@link no.sikt.graphitron.mcp.GraphitronMcpServer} constructor (which the generate / validate paths
 * could reach), and <strong>not</strong> invoked from production code paths other than the dev warm.
 * Do not "helpfully" hoist the call into {@code GraphitronMcpServer}.
 *
 * <p>The state mutations are inherently idempotent (setting a system property or a JUL level to the
 * same value twice is a no-op), matching the "call once per harness" warm lifecycle. {@code DevMojo}
 * must call this <em>before</em> starting the warms so the suppression is established on the dev
 * thread before thread-start publishes it to the {@code graphitron-warm-*} daemon threads that load
 * the noisy classes.
 */
public final class RagLogQuieting {

    /**
     * The DJL HuggingFace tokenizer logger (group 1). Confirmed against the {@code 1.16.3-beta26} bge
     * embeddings jar: {@code HuggingFaceTokenizer}'s static logger is
     * {@code LoggerFactory.getLogger(HuggingFaceTokenizer.class)}, so its name is this FQCN.
     */
    static final String DJL_TOKENIZER_LOGGER = "ai.djl.huggingface.tokenizers.HuggingFaceTokenizer";

    /**
     * The Lucene vectorization-provider logger (group 2). Confirmed name from the emitted warning:
     * the multi-line "Java vector incubator module is not readable" notice originates here.
     */
    static final String LUCENE_VECTORIZATION_LOGGER =
        "org.apache.lucene.internal.vectorization.VectorizationProvider";

    /** The incubator module Lucene's Vector API path needs; presence gates the group-2 hint line. */
    static final String INCUBATOR_VECTOR_MODULE = "jdk.incubator.vector";

    private RagLogQuieting() {
    }

    /**
     * Establish the best-effort suppression and, when the incubator module is absent, emit the single
     * group-2 hint line through {@code devLog}. Call once, on the dev thread, before the warms start.
     *
     * @param devLog sink for the one graphitron-owned dev line (typically {@code getLog()::info}); it
     *               is invoked only when {@code jdk.incubator.vector} is not readable
     */
    public static void quietRagWarmLogs(Consumer<String> devLog) {
        muteDjlTokenizer();
        demoteLuceneVectorization();
        incubatorHint(incubatorVectorPresent()).ifPresent(devLog);
    }

    /** Group 1: mute the non-actionable DJL {@code maxLength} warning across both providers. */
    private static void muteDjlTokenizer() {
        // slf4j-simple (Maven's provider today) reads the per-logger level from the system property at
        // logger-construction time, which is still ahead of us here since the warm has not started.
        System.setProperty("org.slf4j.simpleLogger.log." + DJL_TOKENIZER_LOGGER, "error");
        // Belt-and-suspenders: if the binding is JUL-backed instead, this arm carries the muting.
        Logger.getLogger(DJL_TOKENIZER_LOGGER).setLevel(Level.SEVERE);
    }

    /** Group 2: demote Lucene's multi-line vectorization warning; the hint replaces it when relevant. */
    private static void demoteLuceneVectorization() {
        Logger.getLogger(LUCENE_VECTORIZATION_LOGGER).setLevel(Level.SEVERE);
    }

    /** True when {@code jdk.incubator.vector} is readable, i.e. the user already passed the flag. */
    static boolean incubatorVectorPresent() {
        return ModuleLayer.boot().findModule(INCUBATOR_VECTOR_MODULE).isPresent();
    }

    /**
     * The group-2 hint decision as a pure function of module presence: present means the fast path is
     * already on and Lucene stays silent, so no line; absent means one concise line naming the flag.
     *
     * @param modulePresent whether {@code jdk.incubator.vector} is readable
     * @return the hint line to emit, or empty when nothing should be said
     */
    static Optional<String> incubatorHint(boolean modulePresent) {
        if (modulePresent) {
            return Optional.empty();
        }
        return Optional.of(
            "graphitron:dev: RAG vector search is on the scalar fallback path. Add "
                + "'--add-modules jdk.incubator.vector' to your project's .mvn/jvm.config for the "
                + "faster Vector API path (see getting-started, \"Quieting startup warnings\").");
    }
}
