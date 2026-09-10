package no.sikt.graphitron.model.capture.store;

import no.sikt.graphitron.model.config.SessionStateConfig;
import no.sikt.graphitron.model.lint.LintConfig;
import no.sikt.graphitron.model.run.OutputCoordinates;
import no.sikt.graphitron.model.run.SubjectConfig;
import no.sikt.graphitron.model.schema.input.SchemaRecipe;
import no.sikt.graphitron.model.schema.input.SchemaSource;
import org.jooq.DSLContext;
import org.jooq.Rows;
import org.jooq.Table;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.IntStream;

import static no.sikt.graphitron.model.Tables.STORE_GRAPH_LINT_DISABLED_RULE;
import static no.sikt.graphitron.model.Tables.STORE_GRAPH_LINT_EXCLUDED_TYPE;
import static no.sikt.graphitron.model.Tables.STORE_GRAPH_OUTPUT;
import static no.sikt.graphitron.model.Tables.STORE_GRAPH_SCHEMA_EXTENSION;
import static no.sikt.graphitron.model.Tables.STORE_GRAPH_SCHEMA_INPUT;
import static no.sikt.graphitron.model.Tables.STORE_GRAPH_SESSION_MOUNT;
import static no.sikt.graphitron.model.Tables.STORE_GRAPH_SESSION_UNMOUNT;
import static no.sikt.graphitron.model.Tables.STORE_GRAPH_SUPERGRAPH;
import static no.sikt.graphitron.model.Tables.STORE_GRAPH_TENANT_COLUMN;
import static org.jooq.impl.DSL.excluded;
import static org.jooq.impl.DSL.val;

/**
 * Writes what the run was configured with, as configured. Nine relations and eight methods, one per
 * parameter or per group of parameters that arrive together, so a method is changed alone; the
 * mount and the unmount share theirs because one sealed value carries both.
 *
 * <p>Transcription and nothing else. The value types were validated by whoever built them, the
 * sealed ones already say which arm they are, and this class resolves nothing, compares nothing and
 * defaults nothing. A parameter the run was not asked for has no value to write, so no row is
 * written: absence is a missing row, which is the one spelling of it a reader cannot confuse with a
 * configured blank.
 *
 * <p>The rows say what the run held rather than what the run then did with it. The recipe's
 * patterns are recorded including ones that matched no file, which is what lets a currency check
 * re-expand them over the base directory without building the module; which files they actually
 * resolved to is the reading's business and lands in {@code store_graph_source}.
 *
 * <p>Mark and sweep, per graph. Every row carries the reading's instant and the reading ends by
 * deleting this graph's rows carrying a different one. Those are the parameters the author removed
 * from the build file, and an upsert cannot find them: there is no incoming row to match. The
 * relations are listed in writing order, parents first, and the sweep walks the list backwards,
 * which is what lets {@code store_graph_session_unmount} hold a foreign key into the mount beside
 * it.
 *
 * <p>The graph's own anchor row is not written here. Every relation below holds a foreign key into
 * {@code store_graph}, so the reading that mints it has to have run first; {@code ModelCapture}
 * orders the two.
 */
public final class StoreEntries {

    private StoreEntries() {}

    /**
     * The three {@code store_graph_schema_input.kind} values. Closed by the relation's own
     * {@code CHECK}, which is the taxonomy of record: a value spelled here that the constraint does
     * not admit is refused at the insert rather than stored.
     *
     * <p>The decode that reads these rows back spells them a second time, in the gatherer this one
     * stands beside, and the constraint only catches a value neither of them admits. What holds the
     * two spellings together meanwhile is a round trip over this writer and that decode, and it is
     * a case rather than a constraint because that is the shape the question has: the taxonomy is
     * agreed on or it is not, and only reading a row back can say which.
     */
    private static final String KIND_PATTERN = "pattern";
    private static final String KIND_FILE = "file";
    private static final String KIND_NAMED = "named";

    /**
     * Makes {@code graph}'s configuration rows be exactly what {@code config} declares.
     *
     * <p>The instant is the caller's, the nine relations having to agree on which reading is
     * current, and the graph is a parameter rather than read off the configuration: a run that
     * declared nothing at all still has rows to sweep and nothing left to say whose they were.
     */
    public static void write(DSLContext dsl, String graph, SubjectConfig config,
                             LocalDateTime touchedAt) {
        config.recipe().ifPresent(recipe -> {
            schemaInputs(dsl, graph, recipe, touchedAt);
            schemaExtensions(dsl, graph, recipe, touchedAt);
        });
        config.supergraph().ifPresent(name -> supergraph(dsl, graph, name, touchedAt));
        config.output().ifPresent(output -> output(dsl, graph, output, touchedAt));
        config.tenantColumn().ifPresent(column -> tenantColumn(dsl, graph, column, touchedAt));
        lintDisabledRules(dsl, graph, config.lint(), touchedAt);
        lintExcludedTypes(dsl, graph, config.lint(), touchedAt);
        sessionState(dsl, graph, config.sessionState(), touchedAt);
        sweep(dsl, graph, touchedAt);
    }

    /**
     * What the sweep deletes from, and the only thing that reads it. Listed rather than found by
     * prefix: {@code store_graph} and {@code store_graph_source} share the prefix and are not this
     * gatherer's, and a relation added above and not here would keep its stale rows silently.
     *
     * <p>In writing order, the mount before the unmount that references it. The sweep walks it
     * backwards.
     */
    private static final List<Table<?>> TABLES_TO_SWEEP = List.of(
        STORE_GRAPH_SCHEMA_INPUT, STORE_GRAPH_SCHEMA_EXTENSION, STORE_GRAPH_SUPERGRAPH,
        STORE_GRAPH_OUTPUT, STORE_GRAPH_TENANT_COLUMN, STORE_GRAPH_LINT_DISABLED_RULE,
        STORE_GRAPH_LINT_EXCLUDED_TYPE, STORE_GRAPH_SESSION_MOUNT, STORE_GRAPH_SESSION_UNMOUNT);

    /**
     * Deletes this graph's rows that this reading did not touch, which are the parameters the run
     * no longer had. Scoped to the graph, so a reading of one module says nothing about a
     * sibling's rows in a shared store.
     */
    private static void sweep(DSLContext dsl, String graph, LocalDateTime touchedAt) {
        // Table.field(Field) is a lookup by name returning the loop's own typed column, so one
        // relation's two name them on all nine.
        var named = STORE_GRAPH_SCHEMA_INPUT;
        for (Table<?> table : TABLES_TO_SWEEP.reversed()) {
            dsl.deleteFrom(table)
                .where(table.field(named.GRAPH_NAME).eq(graph))
                .and(table.field(named.TOUCHED_AT).ne(touchedAt))
                .execute();
        }
    }

    /**
     * A recipe entry paired with the position that keys it and the taxonomy that spells it. The
     * kind and the value come off one dispatch over the entry seal, so the two cannot be read from
     * different arms of it.
     */
    private record Numbered(int ordinal, SchemaRecipe.Binding binding, String kind, String value) {}

    private static Numbered numbered(int ordinal, SchemaRecipe.Binding binding) {
        return switch (binding.entry()) {
            case SchemaRecipe.Entry.Pattern pattern ->
                new Numbered(ordinal, binding, KIND_PATTERN, pattern.glob());
            case SchemaRecipe.Entry.Literal literal -> new Numbered(ordinal, binding,
                switch (literal.source()) {
                    case SchemaSource.File ignored -> KIND_FILE;
                    case SchemaSource.Named ignored -> KIND_NAMED;
                },
                literal.source().sourceName());
        };
    }

    /**
     * The recipe's bindings, keyed by their position in it. The ordinal is the recipe's spine and
     * the reason the three entry kinds share one relation rather than taking one each: splitting
     * them would shatter the one ordering the recipe has.
     *
     * <p>The tag and the description note are transcribed rather than treated as decoration. A run
     * stamps them onto every element of the source they bind, so a replay that dropped them would
     * mint different rows than the graph's own build did.
     */
    private static void schemaInputs(DSLContext dsl, String graph, SchemaRecipe recipe,
                                     LocalDateTime touchedAt) {
        var t = STORE_GRAPH_SCHEMA_INPUT;
        var bindings = recipe.bindings();
        var rows = IntStream.range(0, bindings.size())
            .mapToObj(i -> numbered(i, bindings.get(i)))
            .collect(Rows.toRowList(
                entry -> val(graph, t.GRAPH_NAME),
                entry -> val(entry.ordinal(), t.ORDINAL),
                entry -> val(entry.kind(), t.KIND),
                entry -> val(entry.value(), t.ENTRY_VALUE),
                entry -> val(entry.binding().tag().orElse(null), t.TAG),
                entry -> val(entry.binding().descriptionNote().orElse(null), t.DESCRIPTION_NOTE),
                entry -> val(touchedAt, t.TOUCHED_AT)));
        if (rows.isEmpty()) {
            return;
        }
        dsl.insertInto(t, t.GRAPH_NAME, t.ORDINAL, t.KIND, t.ENTRY_VALUE, t.TAG, t.DESCRIPTION_NOTE,
                t.TOUCHED_AT)
            .valuesOfRows(rows)
            .onDuplicateKeyUpdate()
            .set(t.KIND, excluded(t.KIND))
            .set(t.ENTRY_VALUE, excluded(t.ENTRY_VALUE))
            .set(t.TAG, excluded(t.TAG))
            .set(t.DESCRIPTION_NOTE, excluded(t.DESCRIPTION_NOTE))
            .set(t.TOUCHED_AT, excluded(t.TOUCHED_AT))
            .execute();
    }

    /**
     * The extensions a schema file has to carry to be read. A per-run set rather than a per-binding
     * one, which is why it is its own relation beside the bindings rather than a column on them.
     */
    private static void schemaExtensions(DSLContext dsl, String graph, SchemaRecipe recipe,
                                         LocalDateTime touchedAt) {
        var t = STORE_GRAPH_SCHEMA_EXTENSION;
        var extensions = recipe.extensions();
        var rows = IntStream.range(0, extensions.size()).boxed().collect(Rows.toRowList(
            i -> val(graph, t.GRAPH_NAME),
            i -> val(i, t.ORDINAL),
            i -> val(extensions.get(i), t.EXTENSION),
            i -> val(touchedAt, t.TOUCHED_AT)));
        if (rows.isEmpty()) {
            return;
        }
        dsl.insertInto(t, t.GRAPH_NAME, t.ORDINAL, t.EXTENSION, t.TOUCHED_AT)
            .valuesOfRows(rows)
            .onDuplicateKeyUpdate()
            .set(t.EXTENSION, excluded(t.EXTENSION))
            .set(t.TOUCHED_AT, excluded(t.TOUCHED_AT))
            .execute();
    }

    /**
     * Which supergraph this graph declared itself a subgraph of. The row's presence is the fact, so
     * a standalone graph and a graph nobody asked leave the same absence, which is the answer both
     * want: not a peer.
     */
    private static void supergraph(DSLContext dsl, String graph, String supergraph,
                                   LocalDateTime touchedAt) {
        var t = STORE_GRAPH_SUPERGRAPH;
        dsl.insertInto(t, t.GRAPH_NAME, t.SUPERGRAPH_NAME, t.TOUCHED_AT)
            .values(graph, supergraph, touchedAt)
            .onDuplicateKeyUpdate()
            .set(t.SUPERGRAPH_NAME, excluded(t.SUPERGRAPH_NAME))
            .set(t.TOUCHED_AT, excluded(t.TOUCHED_AT))
            .execute();
    }

    /**
     * The three output coordinates, which travel together: they are present together on a
     * generating run and absent together on a validating one. A validate-only run carries a
     * package sentinel to satisfy its context's non-null contract, and {@link SubjectConfig} has
     * already turned that into the absence it is, so there is nothing to strip here.
     */
    private static void output(DSLContext dsl, String graph, OutputCoordinates output,
                               LocalDateTime touchedAt) {
        var t = STORE_GRAPH_OUTPUT;
        dsl.insertInto(t, t.GRAPH_NAME, t.OUTPUT_PACKAGE, t.JOOQ_PACKAGE, t.OUTPUT_DIRECTORY,
                t.TOUCHED_AT)
            .values(graph, output.outputPackage(), output.jooqPackage(),
                output.outputDirectory().toString(), touchedAt)
            .onDuplicateKeyUpdate()
            .set(t.OUTPUT_PACKAGE, excluded(t.OUTPUT_PACKAGE))
            .set(t.JOOQ_PACKAGE, excluded(t.JOOQ_PACKAGE))
            .set(t.OUTPUT_DIRECTORY, excluded(t.OUTPUT_DIRECTORY))
            .set(t.TOUCHED_AT, excluded(t.TOUCHED_AT))
            .execute();
    }

    /** The database-per-tenant column, where the build declared one. */
    private static void tenantColumn(DSLContext dsl, String graph, String column,
                                     LocalDateTime touchedAt) {
        var t = STORE_GRAPH_TENANT_COLUMN;
        dsl.insertInto(t, t.GRAPH_NAME, t.COLUMN_NAME, t.TOUCHED_AT)
            .values(graph, column, touchedAt)
            .onDuplicateKeyUpdate()
            .set(t.COLUMN_NAME, excluded(t.COLUMN_NAME))
            .set(t.TOUCHED_AT, excluded(t.TOUCHED_AT))
            .execute();
    }

    /**
     * The rules the author silenced. Keyed by the rule id, there being no position to record: the
     * configured value is a set, and an ordinal here would record the JVM's iteration order over it
     * and call it a position.
     */
    private static void lintDisabledRules(DSLContext dsl, String graph, LintConfig lint,
                                          LocalDateTime touchedAt) {
        var t = STORE_GRAPH_LINT_DISABLED_RULE;
        var rows = lint.disabledRuleIds().stream().collect(Rows.toRowList(
            ruleId -> val(graph, t.GRAPH_NAME),
            ruleId -> val(ruleId, t.RULE_ID),
            ruleId -> val(touchedAt, t.TOUCHED_AT)));
        if (rows.isEmpty()) {
            return;
        }
        dsl.insertInto(t, t.GRAPH_NAME, t.RULE_ID, t.TOUCHED_AT)
            .valuesOfRows(rows)
            .onDuplicateKeyUpdate()
            .set(t.TOUCHED_AT, excluded(t.TOUCHED_AT))
            .execute();
    }

    /**
     * The type-name globs excluded from the lint engine, keyed by position: the configured value is
     * a list and the order is the author's, which is the other half of why the two lint parameters
     * are two relations rather than one.
     */
    private static void lintExcludedTypes(DSLContext dsl, String graph, LintConfig lint,
                                          LocalDateTime touchedAt) {
        var t = STORE_GRAPH_LINT_EXCLUDED_TYPE;
        var patterns = lint.excludedTypePatterns();
        var rows = IntStream.range(0, patterns.size()).boxed().collect(Rows.toRowList(
            i -> val(graph, t.GRAPH_NAME),
            i -> val(i, t.ORDINAL),
            i -> val(patterns.get(i), t.TYPE_PATTERN),
            i -> val(touchedAt, t.TOUCHED_AT)));
        if (rows.isEmpty()) {
            return;
        }
        dsl.insertInto(t, t.GRAPH_NAME, t.ORDINAL, t.TYPE_PATTERN, t.TOUCHED_AT)
            .valuesOfRows(rows)
            .onDuplicateKeyUpdate()
            .set(t.TYPE_PATTERN, excluded(t.TYPE_PATTERN))
            .set(t.TOUCHED_AT, excluded(t.TOUCHED_AT))
            .execute();
    }

    /**
     * The session-state hooks, as authored. Only the {@code fqcn#method} strings the build carried
     * land here; what reflection later makes of them is a model fact and never comes back to this
     * family.
     *
     * <p>The switch is exhaustive over the seal, so a third form of the parameter is a compile
     * error here rather than a configuration nothing transcribes. Two relations rather than two
     * columns because a mount without an unmount is the supported configuration and an unmount
     * without a mount is a defect, which the foreign key between them states.
     */
    private static void sessionState(DSLContext dsl, String graph, SessionStateConfig sessionState,
                                     LocalDateTime touchedAt) {
        switch (sessionState) {
            case SessionStateConfig.None ignored -> { }
            case SessionStateConfig.MethodHooks hooks -> {
                var mount = STORE_GRAPH_SESSION_MOUNT;
                dsl.insertInto(mount, mount.GRAPH_NAME, mount.MOUNT_METHOD, mount.TOUCHED_AT)
                    .values(graph, hooks.mount().raw(), touchedAt)
                    .onDuplicateKeyUpdate()
                    .set(mount.MOUNT_METHOD, excluded(mount.MOUNT_METHOD))
                    .set(mount.TOUCHED_AT, excluded(mount.TOUCHED_AT))
                    .execute();
                hooks.unmount().ifPresent(unmount -> {
                    var t = STORE_GRAPH_SESSION_UNMOUNT;
                    dsl.insertInto(t, t.GRAPH_NAME, t.UNMOUNT_METHOD, t.TOUCHED_AT)
                        .values(graph, unmount.raw(), touchedAt)
                        .onDuplicateKeyUpdate()
                        .set(t.UNMOUNT_METHOD, excluded(t.UNMOUNT_METHOD))
                        .set(t.TOUCHED_AT, excluded(t.TOUCHED_AT))
                        .execute();
                });
            }
        }
    }
}
