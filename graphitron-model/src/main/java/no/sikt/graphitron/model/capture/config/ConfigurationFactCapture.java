package no.sikt.graphitron.model.capture.config;

import no.sikt.graphitron.model.config.SessionStateConfig;
import no.sikt.graphitron.model.lint.LintConfig;
import no.sikt.graphitron.model.run.OutputCoordinates;
import no.sikt.graphitron.model.run.SubjectConfig;
import no.sikt.graphitron.model.sink.FactSink;

import static no.sikt.graphitron.model.Tables.STORE_GRAPH_LINT_DISABLED_RULE;
import static no.sikt.graphitron.model.Tables.STORE_GRAPH_LINT_EXCLUDED_TYPE;
import static no.sikt.graphitron.model.Tables.STORE_GRAPH_OUTPUT;
import static no.sikt.graphitron.model.Tables.STORE_GRAPH_SESSION_MOUNT;
import static no.sikt.graphitron.model.Tables.STORE_GRAPH_SESSION_UNMOUNT;
import static no.sikt.graphitron.model.Tables.STORE_GRAPH_SUPERGRAPH;
import static no.sikt.graphitron.model.Tables.STORE_GRAPH_TENANT_COLUMN;

/**
 * Transcribes the configuration a run held in hand, written fresh by every run from its resolved
 * values; the warm path's graph-scoped clear has already emptied the previous run's rows, and
 * removal therefore propagates structurally rather than by upsert care. Every relation here is
 * graph-keyed, so {@code StoreRefresh} ownership-scopes it by default and a pom that drops an
 * element simply leaves nothing rewritten.
 *
 * <p>Absence is a row that is not written, never a row carrying a synthesised value: nothing here
 * mints a value the run did not have, which is what would make a transcribed fact able to disagree
 * with the run it describes.
 */
public final class ConfigurationFactCapture {

    private ConfigurationFactCapture() {}

    public static void capture(FactSink sink, SubjectConfig config) {
        config.recipe().ifPresent(recipe -> StoredRecipe.write(sink, recipe));
        config.supergraph().ifPresent(supergraph -> writeSupergraph(sink, supergraph));
        config.output().ifPresent(output -> writeOutput(sink, output));
        config.tenantColumn().ifPresent(column -> writeTenantColumn(sink, column));
        writeLint(sink, config.lint());
        writeSessionState(sink, config.sessionState());
    }

    /**
     * The graph's declared supergraph membership. Written only when a declaration is in hand: the
     * row's presence is the fact, so a standalone graph, a programmatic run that was never asked and
     * a graph whose anchor a diagnostics preamble minted all leave none, and every reader's safe
     * answer to all three is the same "not a peer".
     */
    private static void writeSupergraph(FactSink sink, String supergraph) {
        var row = sink.dsl().newRecord(STORE_GRAPH_SUPERGRAPH);
        row.setSupergraphName(supergraph);
        sink.add(row);
    }

    /** The three output coordinates, present together on a generating run and absent together. */
    private static void writeOutput(FactSink sink, OutputCoordinates output) {
        var row = sink.dsl().newRecord(STORE_GRAPH_OUTPUT);
        row.setOutputPackage(output.outputPackage());
        row.setJooqPackage(output.jooqPackage());
        row.setOutputDirectory(output.outputDirectory().toString());
        sink.add(row);
    }

    private static void writeTenantColumn(FactSink sink, String column) {
        var row = sink.dsl().newRecord(STORE_GRAPH_TENANT_COLUMN);
        row.setColumnName(column);
        sink.add(row);
    }

    /**
     * The {@code <lint>} block's two halves. A conjunction rather than an alternation, so both are
     * written when both are configured; the set half is keyed by its value and the list half by its
     * position, which is why they are two relations.
     */
    private static void writeLint(FactSink sink, LintConfig lint) {
        for (String ruleId : lint.disabledRuleIds()) {
            var row = sink.dsl().newRecord(STORE_GRAPH_LINT_DISABLED_RULE);
            row.setRuleId(ruleId);
            sink.add(row);
        }
        int ordinal = 0;
        for (String pattern : lint.excludedTypePatterns()) {
            var row = sink.dsl().newRecord(STORE_GRAPH_LINT_EXCLUDED_TYPE);
            row.setOrdinal(ordinal++);
            row.setTypePattern(pattern);
            sink.add(row);
        }
    }

    /**
     * The {@code <sessionState>} method references, verbatim: only the authored
     * {@code fqcn#method} strings land here, per this family's authored-facts-only rule (the
     * reflected signatures are model facts, never stored back). Row presence is the fact on
     * both relations: no mount row means no identity is mounted, and no unmount row beside a
     * mount is the supported mount-only configuration. The switch is exhaustive over the seal,
     * so a new form is a compile error here rather than a silently untranscribed configuration.
     */
    private static void writeSessionState(FactSink sink, SessionStateConfig sessionState) {
        switch (sessionState) {
            case SessionStateConfig.None ignored -> { }
            case SessionStateConfig.MethodHooks hooks -> {
                var mountRow = sink.dsl().newRecord(STORE_GRAPH_SESSION_MOUNT);
                mountRow.setMountMethod(hooks.mount().raw());
                sink.add(mountRow);
                hooks.unmount().ifPresent(unmount -> {
                    var row = sink.dsl().newRecord(STORE_GRAPH_SESSION_UNMOUNT);
                    row.setUnmountMethod(unmount.raw());
                    sink.add(row);
                });
            }
        }
    }
}
