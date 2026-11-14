package no.sikt.graphitron.model.capture.document;

import no.sikt.graphitron.model.sink.AnchorStage;
import no.sikt.graphitron.model.derive.TenantColumns;
import no.sikt.graphitron.model.derive.OccurrenceTenants;
import no.sikt.graphitron.model.derive.ArgMappingCandidates;
import no.sikt.graphitron.model.derive.BindingClaims;
import no.sikt.graphitron.model.derive.ArgMappingLandings;
import no.sikt.graphitron.model.derive.ArgMappingPairDefects;
import no.sikt.graphitron.model.derive.EntryDefectClaims;
import no.sikt.graphitron.model.derive.EntryDefectMembers;
import no.sikt.graphitron.model.derive.EntryDefectKeyColumns;
import no.sikt.graphitron.model.derive.EntryDefectTables;
import no.sikt.graphitron.model.derive.Occurrences;
import no.sikt.graphitron.model.derive.EntityKeyAlternatives;
import no.sikt.graphitron.model.derive.EntryDefects;
import no.sikt.graphitron.model.derive.Fetchers;
import no.sikt.graphitron.model.derive.NodeKeyColumns;
import no.sikt.graphitron.model.derive.NodeLegacyKeyShapes;
import no.sikt.graphitron.model.derive.OccurrenceBindings;
import no.sikt.graphitron.model.derive.OccurrenceBuilds;
import no.sikt.graphitron.model.derive.OccurrenceDepthDefects;
import no.sikt.graphitron.model.derive.OccurrenceDepths;
import no.sikt.graphitron.model.derive.OccurrenceLists;
import no.sikt.graphitron.model.derive.OccurrenceOverrides;
import no.sikt.graphitron.model.derive.OccurrenceParticipants;
import no.sikt.graphitron.model.derive.OccurrenceProducers;
import no.sikt.graphitron.model.derive.OccurrenceSourceClaims;
import no.sikt.graphitron.model.derive.OccurrenceValues;
import no.sikt.graphitron.model.derive.Outboxes;
import no.sikt.graphitron.model.derive.PathClaims;
import no.sikt.graphitron.model.derive.PathElements;
import no.sikt.graphitron.model.derive.ProducerClaims;
import no.sikt.graphitron.model.derive.RoutineResults;
import no.sikt.graphitron.model.derive.SpelledTables;
import no.sikt.graphitron.model.derive.TypeBackings;
import no.sikt.graphitron.model.derive.TypeClaims;
import org.jooq.DSLContext;

import java.time.LocalDateTime;
import java.util.List;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_FIELD_TARGET;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_FIELD_TARGET_RULE;
import static org.jooq.impl.DSL.excluded;
import static org.jooq.impl.DSL.val;

/**
 * The stages that resolve: the tail of the graphitron anchor, what graphitron makes of the store
 * once the documents, the catalog and the classpath have been read.
 *
 * <p>Last in the anchor, and the ordering is a dependency rather than a preference. These stages
 * read the {@code graphql_} anchors for coordinates, the {@code graphitron_} entries for what each
 * directive application said, the {@code sql_} families for the tables and columns a spelling
 * resolves against, and {@code code_} for the methods one names, and several read the
 * coordinate-keyed decode, which {@link no.sikt.graphitron.model.run.ModelCapture} runs ahead of
 * the anchor for that reason.
 *
 * <h2>Why this is not the decode beside it</h2>
 *
 * <p>{@link no.sikt.graphitron.model.capture.graphitron.GraphitronFactCapture} holds the decode, which restates one directive application in
 * graphitron's vocabulary and joins nothing. The two were one class because they share that
 * vocabulary, not because they share a writer or a moment: a decode is a function of one document
 * and runs where the document is, where a resolution needs the store whole and cannot run inside a
 * walk at all.
 *
 * <p>Nothing here buffers. Every stage writes as it goes, which is what lets the sequence be read
 * as the order it states rather than as an order plus a flush somebody has to place correctly.
 */
public final class GraphitronAssemblyCapture {

    private GraphitronAssemblyCapture() {}

    /**
     * The stages that resolve {@code graph}'s facts against everything the reading captured, in the
     * order they run, as the anchor's last stages; {@link GraphitronAstCapture#STAGES} runs them.
     *
     * <p>The instant is the caller's, on every gatherer's terms: the relations these stages mark and
     * sweep tell this reading's rows from the last one's by it.
     */
    static final List<AnchorStage> STAGES = List.of(
        // The tables the configured tenant column scopes, off the configuration and the catalog alone.
        new AnchorStage("tenant-columns", TenantColumns.class, TenantColumns::derive),
        // What each argMapping may write at its site, off the sites the anchor just wrote and the
        // transcription; the views the defects below read over it see this reading's rows.
        new AnchorStage("argmapping-candidates", ArgMappingCandidates.class,
            (dsl, graph, readAt) -> ArgMappingCandidates.derive(dsl, graph)),
        // Over the nodes the anchor settled.
        new AnchorStage("node-key-columns", NodeKeyColumns.class,
            (dsl, graph, readAt) -> NodeKeyColumns.derive(dsl, graph)),
        // The legacy key shape each type id answers to, against the current keys just written.
        new AnchorStage("node-legacy-key-shapes", NodeLegacyKeyShapes.class,
            (dsl, graph, readAt) -> NodeLegacyKeyShapes.derive(dsl, graph)),
        // Each field's target type, over the population the generator emits.
        new AnchorStage("field-targets", GraphitronAssemblyCapture.class,
            GraphitronAssemblyCapture::targets),
        // What each field's written directives claim it runs, off the anchor's fields and
        // applications; the fetchers and the entry defects below read it.
        new AnchorStage("producer-claims", ProducerClaims.class,
            ProducerClaims::derive),
        // And what each field, input field and argument's written directives claim it binds to, off
        // the same elements and applications; the bindings and their defects read it.
        new AnchorStage("binding-claims", BindingClaims.class,
            BindingClaims::derive),
        // And what each type's written directives claim it stands for, off the same applications.
        new AnchorStage("type-claims", TypeClaims.class,
            TypeClaims::derive),
        // The resolutions of written references, the base one first: what a written table name
        // resolves to against the catalog census, then the hops a @reference path element could
        // take, which read it.
        new AnchorStage("spelled-tables", SpelledTables.class,
            (dsl, graph, readAt) -> SpelledTables.derive(dsl, graph)),
        // What each path element names, its spelling resolved against the catalog and classpath
        // once, keyed by the element.
        new AnchorStage("path-elements", PathElements.class,
            PathElements::derive),
        // Where a field's path ends on a @routine, off the targets and the elements just resolved.
        new AnchorStage("routine-results", RoutineResults.class,
            RoutineResults::derive),
        // Every place a field or an argument is reached, off the targets written above and the
        // anchor's table types: a chain grows through a field whose target is an object type with no
        // table.
        new AnchorStage("occurrences", Occurrences.class,
            Occurrences::derive),
        // Where each written argMapping path lands under each occurrence of its call, a walk over the
        // occurrences just written.
        new AnchorStage("argmapping-landings", ArgMappingLandings.class,
            ArgMappingLandings::derive),
        // Where each path is written, by the declared element and the type it reaches, off the path
        // entries and the targets above; the paths follow these.
        new AnchorStage("path-claims", PathClaims.class,
            PathClaims::derive),
        // Then which class stands for each type: the table binding just written read through its
        // record class, and what the producers' resolved methods ground, closed over the members
        // each backed class offers. The classpath was read before this gatherer ran.
        new AnchorStage("type-backings", TypeBackings.class,
            TypeBackings::derive),
        // Each output field's fetcher, off the targets, bindings and backings above.
        new AnchorStage("fetchers", Fetchers.class,
            Fetchers::derive),
        // The types each polymorphic occurrence's rows may be and how a row is told apart as each, off
        // the occurrences, the table types and the discriminator directives;
        // before the depths, whose table bindings read them.
        new AnchorStage("occurrence-participants", OccurrenceParticipants.class,
            OccurrenceParticipants::derive),
        // Which lists each input field is read once per element of, off the occurrences and the
        // declared elements alone; before the depths, whose readers it serves.
        new AnchorStage("occurrence-lists", OccurrenceLists.class,
            OccurrenceLists::derive),
        // Which occurrences an override: true @condition at or above them stands in for, off the
        // occurrences and the directive applications alone; before the depths.
        new AnchorStage("occurrence-overrides", OccurrenceOverrides.class,
            OccurrenceOverrides::derive),
        // Each occurrence's producer, its service call's pin and fills and what it produces, for the
        // whole graph: they read nothing the depths write, and the depths' sources read them.
        new AnchorStage("occurrence-producers", OccurrenceProducers.class,
            OccurrenceProducers::derive),
        // What the source rules that read nothing a depth writes claim, for the whole graph, off the
        // producers and the service calls' fills just written.
        new AnchorStage("occurrence-source-claims", OccurrenceSourceClaims.class,
            OccurrenceSourceClaims::derive),
        // Each occurrence's source, needs and paths, a depth at a time, off the producers, the
        // landings and the claims above: each depth reading what the depth above it wrote.
        new AnchorStage("occurrence-depths", OccurrenceDepths.class,
            OccurrenceDepths::derive),
        // What each occurrence binds where its path lands, the lifts, node ids, columns, tables and
        // @condition calls, for the whole graph off the depths: nothing in the depths reads them.
        new AnchorStage("occurrence-bindings", OccurrenceBindings.class,
            OccurrenceBindings::derive),
        // The class each input object is built into and the member each field fills, the shape each
        // node id lands in, and the defects judging them, off the calls and parameters just pinned.
        new AnchorStage("occurrence-builds", OccurrenceBuilds.class,
            OccurrenceBuilds::derive),
        // The call each $tenant or @tenant names the tenant of, and every value a call's tenant is read
        // from, off the landings, the column and node id bindings and the classes just built.
        new AnchorStage("occurrence-tenants", OccurrenceTenants.class, OccurrenceTenants::derive),
        // The defects judging a child producer's lift and a field's column, off the two stages above.
        new AnchorStage("occurrence-depth-defects", OccurrenceDepthDefects.class,
            OccurrenceDepthDefects::derive),
        // The member each occurrence with an object source reads, off the accessor paths above.
        new AnchorStage("occurrence-values", OccurrenceValues.class,
            OccurrenceValues::derive),
        // The federation @key refusals, off how each key field binds at its entity's table, which the
        // two stages above state between them.
        new AnchorStage("entity-key-alternatives", EntityKeyAlternatives.class,
            EntityKeyAlternatives::derive),
        // The outbox targets the configuration resolves to, and each @outbox as the message its
        // field's call writes, judged against the occurrences' producers above; then their defects.
        new AnchorStage("outbox", Outboxes.class, Outboxes::derive),
        // Last, the defects of every code no earlier stage writes itself, off everything above: the
        // rule's rows marked with this reading, the details under them, then the argMapping entries
        // under the strings they fault.
        new AnchorStage("entry-defects", EntryDefects.class, (dsl, graph, readAt) -> {
            EntryDefects.derive(dsl, graph, readAt, EntryDefects.GRAPHITRON);
            EntryDefectClaims.derive(dsl, graph, readAt);
            EntryDefectMembers.derive(dsl, graph, readAt, EntryDefectMembers.GRAPHITRON, EntryDefects.GRAPHITRON.stage());
            EntryDefectKeyColumns.derive(dsl, graph, readAt);
            EntryDefectTables.derive(dsl, graph, readAt);
            ArgMappingPairDefects.derive(dsl, graph);
        }));

    /**
     * Each field's target type, over the population the generator emits rather than over the
     * document, which is {@code graphitron_field} and the reason the anchor above it is derived
     * first: the connection's item, with the connection as its wrapper, where the field's named
     * type is a connection, and the named type itself otherwise. The rule is the view beside the
     * target; stored because a reader joins on the target's name and wants an index on that column.
     *
     * <p>The rule unwraps a connection through {@code graphitron_connection} and its edge, which
     * macro expansion wrote beside the types.
     */
    static void targets(DSLContext dsl, String graph, LocalDateTime touchedAt) {
        var target = GRAPHITRON_FIELD_TARGET;
        var rule = GRAPHITRON_FIELD_TARGET_RULE;
        dsl.insertInto(target)
            .columns(target.GRAPH_NAME, target.TYPE_NAME, target.FIELD_NAME,
                target.TARGET_TYPE_NAME, target.WRAPPER_TYPE_NAME, target.TOUCHED_AT)
            .select(dsl
                .select(rule.GRAPH_NAME, rule.TYPE_NAME, rule.FIELD_NAME,
                    rule.TARGET_TYPE_NAME, rule.WRAPPER_TYPE_NAME,
                    val(touchedAt, target.TOUCHED_AT))
                .from(rule)
                .where(rule.GRAPH_NAME.eq(graph)))
            .onDuplicateKeyUpdate()
            .set(target.TARGET_TYPE_NAME,
                excluded(target.TARGET_TYPE_NAME))
            .set(target.WRAPPER_TYPE_NAME,
                excluded(target.WRAPPER_TYPE_NAME))
            .set(target.TOUCHED_AT,
                excluded(target.TOUCHED_AT))
            .execute();
        // The coordinates this reading no longer names, which an upsert cannot find: there is no
        // incoming row to match. A field the author removed goes through the cascade instead.
        dsl.deleteFrom(target)
            .where(target.GRAPH_NAME.eq(graph))
            .and(target.TOUCHED_AT.ne(touchedAt))
            .execute();
    }
}
