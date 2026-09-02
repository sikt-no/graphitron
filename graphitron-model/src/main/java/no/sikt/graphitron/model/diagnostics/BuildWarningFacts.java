package no.sikt.graphitron.model.diagnostics;

import graphql.language.SourceLocation;
import no.sikt.graphitron.model.tables.records.BuildWarningNoRuleRecord;
import no.sikt.graphitron.model.tables.records.LintFindingFixEditRecord;
import no.sikt.graphitron.model.tables.records.LintFindingFixRecord;
import no.sikt.graphitron.model.tables.records.LintFindingRecord;
import no.sikt.graphitron.model.diagnostics.BuildWarning;
import no.sikt.graphitron.model.run.GraphIdentity;
import no.sikt.graphitron.model.lint.LintFix;
import org.jooq.DSLContext;
import org.jooq.TableRecord;
import org.jooq.exception.DataAccessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static no.sikt.graphitron.model.Tables.BUILD_WARNING_NO_RULE;
import static no.sikt.graphitron.model.Tables.LINT_FINDING;
import static no.sikt.graphitron.model.Tables.LINT_FINDING_FIX;
import static no.sikt.graphitron.model.Tables.LINT_FINDING_FIX_EDIT;

/**
 * The warning arms' writer: transcribes the suppression-filtered warning list into
 * {@code lint_finding} (the {@link BuildWarning.LintFinding} arm, in the linter's vocabulary)
 * and {@code build_warning_no_rule} (the {@link BuildWarning.NoRule} advisory arm, in the
 * sealed hierarchy's own words), forking on the sealed arm through an exhaustive switch with
 * no {@code default}. A finding carrying a {@link LintFix} additionally writes the fix and its
 * ordered edits, so the correction a rule computed survives the run that computed it and an editor
 * can offer it without a second evaluator.
 *
 * <p>The input is exactly the list the report is assembled from, after the disabled-rule
 * filter: suppression is applied over the combined list before the report fuses, which is what
 * makes stored lint rows post-suppression survivors like the report's, while advisory rows
 * never met the filter. A loader reading a pre-suppression stream would resurrect disabled
 * findings on the wire.
 *
 * <p>Cadence and failure posture are {@link no.sikt.graphitron.model.capture.compile.CompileFacts}'s:
 * the dev session's live store handle, one graph-scoped delete-and-insert transaction per
 * snapshot, and store trouble costs warmth, never the dev loop.
 */
public final class BuildWarningFacts {

    private static final Logger LOG = LoggerFactory.getLogger(BuildWarningFacts.class);

    private final DSLContext dsl;
    private final GraphIdentity graph;
    private final boolean[] ownershipWarned = new boolean[1];

    /**
     * @param dsl   the dev session's store handle; live, shared with the session's in-process
     *              readers, never a per-snapshot open of the writer's own
     * @param graph the session's graph: the partition every statement is scoped by, and the base
     *              directory the graph's ownership is checked against
     */
    public BuildWarningFacts(DSLContext dsl, GraphIdentity graph) {
        this.dsl = Objects.requireNonNull(dsl, "dsl");
        this.graph = Objects.requireNonNull(graph, "graph");
    }

    /** Replaces the graph's warning partitions with {@code warnings}, each arm in emit order. */
    public void write(List<BuildWarning> warnings) {
        try {
            dsl.transaction(tx -> writeWarnings(tx.dsl(), warnings));
        } catch (DataAccessException e) {
            LOG.warn("build warnings for graph '{}' could not be written to the fact store; "
                + "store-side readers answer without this snapshot", graph.name(), e);
        }
    }

    private void writeWarnings(DSLContext tx, List<BuildWarning> warnings) {
        if (!OwnedGraphPartition.prepare(tx, graph, LOG, ownershipWarned)) {
            return;
        }
        // Children before parents, the delete being the insert's order reversed.
        tx.deleteFrom(LINT_FINDING_FIX_EDIT)
            .where(LINT_FINDING_FIX_EDIT.GRAPH_NAME.eq(graph.name()))
            .execute();
        tx.deleteFrom(LINT_FINDING_FIX)
            .where(LINT_FINDING_FIX.GRAPH_NAME.eq(graph.name()))
            .execute();
        tx.deleteFrom(LINT_FINDING).where(LINT_FINDING.GRAPH_NAME.eq(graph.name())).execute();
        tx.deleteFrom(BUILD_WARNING_NO_RULE)
            .where(BUILD_WARNING_NO_RULE.GRAPH_NAME.eq(graph.name()))
            .execute();
        var rows = new ArrayList<TableRecord<?>>(warnings.size());
        int lintOrdinal = 0;
        int advisoryOrdinal = 0;
        for (BuildWarning warning : warnings) {
            switch (warning) {
                case BuildWarning.LintFinding finding -> {
                    int ordinal = lintOrdinal++;
                    LintFindingRecord row = tx.newRecord(LINT_FINDING);
                    row.setGraphName(graph.name());
                    row.setOrdinal(ordinal);
                    row.setLintRule(finding.rule().id());
                    row.setMessage(finding.message());
                    location(row::setFile, row::setSourceLine, row::setSourceColumn,
                        finding.location());
                    rows.add(row);
                    // Appended straight after the finding they belong to, so the batch inserts each
                    // parent ahead of its children without a second pass to order them.
                    finding.fix().ifPresent(fix -> fixRows(tx, ordinal, fix, rows));
                }
                case BuildWarning.NoRule advisory -> {
                    BuildWarningNoRuleRecord row = tx.newRecord(BUILD_WARNING_NO_RULE);
                    row.setGraphName(graph.name());
                    row.setOrdinal(advisoryOrdinal++);
                    row.setMessage(advisory.message());
                    location(row::setFile, row::setSourceLine, row::setSourceColumn,
                        advisory.location());
                    rows.add(row);
                }
            }
        }
        if (!rows.isEmpty()) {
            tx.batchInsert(rows).execute();
        }
    }

    /**
     * The fix rows for one finding: the fix itself, then its edits in the order the rule wrote them.
     * A rule may write two edits whose spans do not run in source order, so the position column
     * carries the rule's order rather than a sort of the spans.
     */
    private void fixRows(DSLContext tx, int findingOrdinal, LintFix fix,
                         List<TableRecord<?>> rows) {
        LintFindingFixRecord fixRow = tx.newRecord(LINT_FINDING_FIX);
        fixRow.setGraphName(graph.name());
        fixRow.setFindingOrdinal(findingOrdinal);
        fixRow.setDescription(fix.description());
        rows.add(fixRow);
        int position = 0;
        for (LintFix.Edit edit : fix.edits()) {
            LintFindingFixEditRecord editRow = tx.newRecord(LINT_FINDING_FIX_EDIT);
            editRow.setGraphName(graph.name());
            editRow.setFindingOrdinal(findingOrdinal);
            editRow.setPosition(position++);
            editRow.setStartLine(edit.start().getLine());
            editRow.setStartColumn(edit.start().getColumn());
            editRow.setEndLine(edit.end().getLine());
            editRow.setEndColumn(edit.end().getColumn());
            editRow.setReplacement(edit.replacement());
            rows.add(editRow);
        }
    }

    private static void location(java.util.function.Consumer<String> file,
                                 java.util.function.Consumer<Integer> line,
                                 java.util.function.Consumer<Integer> column,
                                 SourceLocation location) {
        if (location == null) {
            return;
        }
        if (location.getSourceName() != null && !location.getSourceName().isEmpty()) {
            file.accept(location.getSourceName());
        }
        if (location.getLine() > 0) {
            line.accept(location.getLine());
            column.accept(location.getColumn());
        }
    }
}
