package no.sikt.graphitron.rewrite.capture;

import no.sikt.graphitron.rewrite.lint.LintConfig;
import no.sikt.graphitron.rewrite.session.SessionStateConfig;

import static no.sikt.graphitron.model.Tables.STORE_GRAPH_LINT_DISABLED_RULE;
import static no.sikt.graphitron.model.Tables.STORE_GRAPH_LINT_EXCLUDED_TYPE;
import static no.sikt.graphitron.model.Tables.STORE_GRAPH_OUTPUT;
import static no.sikt.graphitron.model.Tables.STORE_GRAPH_SESSION_DISCONNECT;
import static no.sikt.graphitron.model.Tables.STORE_GRAPH_SESSION_HOOK;
import static no.sikt.graphitron.model.Tables.STORE_GRAPH_SESSION_STATE;
import static no.sikt.graphitron.model.Tables.STORE_GRAPH_SESSION_VARIABLE;
import static no.sikt.graphitron.model.Tables.STORE_GRAPH_SUPERGRAPH;
import static no.sikt.graphitron.model.Tables.STORE_GRAPH_TENANT_COLUMN;

/**
 * Transcribes the configuration a run held in hand, written fresh by every run from its resolved
 * values; the warm path's graph-scoped clear has already emptied the previous run's rows, and
 * removal therefore propagates structurally rather than by upsert care. Every relation here is
 * graph-keyed, so {@link StoreRefresh} ownership-scopes it by default and a pom that drops an
 * element simply leaves nothing rewritten.
 *
 * <p>Absence is a row that is not written, never a row carrying a synthesised value: nothing here
 * mints a value the run did not have, which is what would make a transcribed fact able to disagree
 * with the run it describes.
 */
final class ConfigurationFactCapture {

    /** {@code store_graph_session_state.arm}, closed by the relation's CHECK. */
    static final String ARM_FUNCTION_HOOKS = "function_hooks";
    static final String ARM_VARIABLES = "variables";

    private ConfigurationFactCapture() {}

    static void capture(FactSink sink, FactCapture.SubjectConfig config) {
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
    private static void writeOutput(FactSink sink, FactCapture.OutputCoordinates output) {
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
     * The {@code <sessionState>} alternation, arm first. The switch is exhaustive over the seal, so
     * a new form is a compile error here rather than a silently untranscribed configuration, and the
     * relations follow the seal so no reader can spell the both-forms state
     * {@link SessionStateConfig#from} throws on.
     */
    private static void writeSessionState(FactSink sink, SessionStateConfig sessionState) {
        switch (sessionState) {
            case SessionStateConfig.None ignored -> { }
            case SessionStateConfig.Variables variables -> {
                writeArm(sink, ARM_VARIABLES);
                int ordinal = 0;
                for (SessionStateConfig.Variable variable : variables.variables()) {
                    var row = sink.dsl().newRecord(STORE_GRAPH_SESSION_VARIABLE);
                    row.setOrdinal(ordinal++);
                    row.setVariableName(variable.name());
                    row.setClaim(variable.claim());
                    sink.add(row);
                }
            }
            case SessionStateConfig.FunctionHooks hooks -> {
                writeArm(sink, ARM_FUNCTION_HOOKS);
                var hookRow = sink.dsl().newRecord(STORE_GRAPH_SESSION_HOOK);
                hookRow.setConnectCall(hooks.connectCall());
                sink.add(hookRow);
                switch (hooks.unmount()) {
                    // No row is the declared unmount-free opt-out. The unmount arm is total on the
                    // value type, so absence here carries the arm with nothing left ambiguous.
                    case SessionStateConfig.Unmount.UnmountFree ignored -> { }
                    case SessionStateConfig.Unmount.PairedDisconnect paired -> {
                        var row = sink.dsl().newRecord(STORE_GRAPH_SESSION_DISCONNECT);
                        row.setDisconnectCall(paired.call());
                        row.setOutHandle(paired.handle());
                        row.setStateSurvivesTransactions(paired.survivesTransactions());
                        sink.add(row);
                    }
                }
            }
        }
    }

    private static void writeArm(FactSink sink, String arm) {
        var row = sink.dsl().newRecord(STORE_GRAPH_SESSION_STATE);
        row.setArm(arm);
        sink.add(row);
    }
}
