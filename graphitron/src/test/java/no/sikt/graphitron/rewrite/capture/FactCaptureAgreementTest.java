package no.sikt.graphitron.rewrite.capture;

import no.sikt.graphitron.model.run.ModelCapture;
import no.sikt.graphitron.model.Public;
import no.sikt.graphitron.model.boot.GraphitronModelStore;
import no.sikt.graphitron.model.test.CapturedStore;
import no.sikt.graphitron.model.config.ClasspathEntry;
import no.sikt.graphitron.model.jooq.JooqCatalog;
import no.sikt.graphitron.model.test.CatalogInput;
import no.sikt.graphitron.model.grammar.NodeDeclaration;
import no.sikt.graphitron.model.compile.CompileDiagnostic;
import no.sikt.graphitron.model.capture.compile.CompileFacts;
import no.sikt.graphitron.model.compile.CompileRound;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;

import static no.sikt.graphitron.common.configuration.TestConfiguration.testContext;
import static no.sikt.graphitron.model.Tables.JAVAC_DIAGNOSTIC;
import static no.sikt.graphitron.model.Tables.BUILD_WARNING_NO_RULE;
import no.sikt.graphitron.model.schema.input.SchemaInputAttribution;
import static no.sikt.graphitron.model.Tables.LINT_FINDING;
import static no.sikt.graphitron.model.Tables.REJECTION_VALIDATION_ERROR;
import static no.sikt.graphitron.model.Tables.REJECTION_VALIDATION_ERROR_DIRECTIVE;
import static org.jooq.impl.DSL.inline;
import static no.sikt.graphitron.model.Tables.GRAPHQL_ARGUMENT_ELEMENT;
import static no.sikt.graphitron.model.Tables.GRAPHQL_DIRECTIVE_ELEMENT;
import static no.sikt.graphitron.model.Tables.GRAPHQL_DIRECTIVE;
import static no.sikt.graphitron.model.Tables.GRAPHQL_DIRECTIVE_ARGUMENT;
import static no.sikt.graphitron.model.Tables.GRAPHQL_DIRECTIVE_ARGUMENT_ELEMENT;
import static no.sikt.graphitron.model.Tables.GRAPHQL_ELEMENT;
import static no.sikt.graphitron.model.Tables.GRAPHQL_ENUM_VALUE_ELEMENT;
import static no.sikt.graphitron.model.Tables.GRAPHQL_FIELD_ELEMENT;
import static no.sikt.graphitron.model.Tables.GRAPHQL_TYPE_ELEMENT;
import static org.assertj.core.api.Assertions.assertThat;
import no.sikt.graphitron.model.diagnostics.BuildWarning;
import no.sikt.graphitron.model.diagnostics.BuildWarningFacts;
import no.sikt.graphitron.model.run.GraphIdentity;
import no.sikt.graphitron.model.diagnostics.Rejection;
import no.sikt.graphitron.model.diagnostics.RejectionFacts;
import no.sikt.graphitron.model.schema.SchemaAssembly;
import no.sikt.graphitron.model.schema.SdlVerdicts;
import no.sikt.graphitron.model.run.SubjectConfig;
import no.sikt.graphitron.model.diagnostics.ValidationError;

/**
 * The shadow period's honesty check: the store is filled beside the live pipeline and nobody reads
 * it, so nothing but a test can notice capture drifting from the model it shadows.
 *
 * <p>The driver is mechanical. It enumerates the generated jOOQ relations and fails on any one
 * without a registered agreement source, so a relation added to the DDL cannot arrive unchecked.
 * Registrations form a closed set of four arms, which is why there is no skip list:
 * <ul>
 *   <li>{@link Arm#CONTAINMENT} for the SDL side. Capture is total and {@code GraphitronSchema} is
 *       reachability-pruned, so the honest relation is that the store contains the model. The claim
 *       is about authored SDL throughout: a key a macro synthesises is minted into the directive
 *       anchor and never transcribed, so a containment arm here says the store transcribes what
 *       the document declares and nothing else.</li>
 *   <li>{@link Arm#EQUALITY} for the catalog and scanner censuses, which are the same walk reduced
 *       two ways. The {@code java_} family joins them with its anchors elsewhere, in
 *       {@code JavaSourceFactsTest}: it is written by neither capture nor a graph, so its
 *       lifecycle anchor is partitioned by source file where the oracle families' are partitioned
 *       by graph, and there is no fixture here that would fill it.</li>
 *   <li>{@link Arm#DERIVED} for shipped derivations: views, the tables the derivation
 *       stratum's steps write, and the authored rows describing the schema itself. A producer's
 *       table ({@code intent_type_domain}) has a writer that re-derives it inside every
 *       capture, stored only because H2 has no safe recursive view form for a
 *       cyclic type graph; its cadence and clearing follow the derivation, not an oracle, and the
 *       warm/cold census anchors its lifecycle like any capture-written rows. A stage is two
 *       relations under one rule and both are derived: the {@code _rule} view stating it, and the
 *       table its stage writes from that view inside the derivation stratum. The {@code meta_}
 *       rows are derived in the widest sense of the arm, being rows the DDL alone determines
 *       rather than anything a run reads, which is why the rosters sit here. A pure re-projection
 *       (the {@code diagnostic} union) registers the base relations it projects and its
 *       agreement is vacuous by construction; a semantic derivation (the {@code intent_} claim
 *       views and the demand stratum) registers with its own anchor instead, which lives with
 *       its reader's test
 *       (the authored-claim views carry their reason in two places, because a rule reading what an
 *       author wrote is asked both what it makes of a set of applications and whether real SDL
 *       reaches it in that shape: {@code no.sikt.graphitron.rewrite.derive.AuthoredClaimConflictsTest}
 *       binds the lookup arm to {@code LookupFacts.triggersFor} over a real capture, while
 *       {@code no.sikt.graphitron.model.intent.AuthoredClaimTest} binds each arm pair, its position
 *       mask and its ordinal collapse to a census stated row by row in the module whose DDL
 *       declares them; the column-match view carries its reason in two places for the same reason,
 *       though the two questions asked of it have different lifetimes.
 *       {@code no.sikt.graphitron.rewrite.derive.ColumnMatchShadowTest} binds the view and the
 *       reduction over it to the classification walk's fall-through arm over the spec-by-example
 *       corpus, which is the half that retires when the walk does, while
 *       {@code no.sikt.graphitron.model.intent.ColumnMatchClaimTest} binds what the view returns
 *       given rows, and the table resolution it stands on ({@code intent_bound_table}) through the
 *       claim's own table witness, to a catalog stated table by table in the module whose DDL
 *       declares them: the two-tier name match with the tiers deliberately disagreeing, three
 *       decoy tables each differing from the bound one in one part of the witness key, and the
 *       bindings a column claim cannot speak for;
 *       {@code no.sikt.graphitron.model.intent.ReferenceStepTargetTest} binds the spelling
 *       resolution the bindings themselves stand on ({@code graphitron_spelled_table}) and the
 *       {@code @reference} chain over it ({@code graphitron_field_reference_step_hop} and
 *       {@code graphitron_field_reference_step_target}, the hop view pinned through the chain that
 *       reaches or refuses it rather than twice), to a catalog stated table by table in the module
 *       whose DDL declares them, the shapes each case turns on (two foreign keys between one pair
 *       of tables, a key pointing back at its own table, a constraint name two schemas both
 *       declare, a table name two schemas both declare) being ones no one real catalog offers side
 *       by side;
 *       {@code no.sikt.graphitron.model.intent.ArgumentReferenceStepTargetTest} binds the
 *       argument-site pair ({@code intent_argument_reference_step_hop} and
 *       {@code graphitron_argument_reference_step_target}, the hop view pinned through the chain for the
 *       same reason) against that same catalog, and additionally binds the two views to each other:
 *       the arms are textually parallel, so one case seeds one path shape at both sites and asserts
 *       the shared columns come back equal, which is what keeps a change to one from silently
 *       leaving the other behind;
 *       {@code no.sikt.graphitron.model.intent.ConditionMethodRouteTest} binds
 *       {@code graphitron_condition_method_route}, the rung both hop views join for an element that
 *       names a condition and neither a key nor a table, against a seeded census and catalog: the
 *       shapes are the ones a signature can take rather than ones a real classpath offers side by
 *       side, and the asymmetry between the two parameters is asserted from both sides, an
 *       unresolvable arrival being no route where an unresolvable departure is every table in the
 *       graph's sources;
 *       the relation that named why a route was absent is gone, having reached no surface in the
 *       time it existed, so a silence at that hop is now unexplained rather than explained to
 *       nobody;
 *       {@code no.sikt.graphitron.model.intent.ArgumentScopeTableTest} binds
 *       {@code graphitron_argument_scope_table}, which table an argument's column-shaped content binds
 *       against, one case per rung plus the cases where the rungs compete, the precedence being the
 *       claim rather than the table that came out;
 *       {@code no.sikt.graphitron.model.intent.NodeIdInstructionTest} binds
 *       {@code graphitron_node_id_instruction}, which slots carry the {@code @nodeId} instruction and
 *       which node type each names, with one case per basis because the basis is the claim: a
 *       coordinate answered by the wrong rule resolves the wrong node type wherever several rules
 *       could answer, so the assertion is which rule answered and not merely that something did;
 *       {@code no.sikt.graphitron.model.intent.NodeIdDecodeReachTest} binds the pair a decode
 *       navigates on ({@code intent_node_id_decode_endpoint} and
 *       {@code graphitron_node_id_decode_hop}, the hop child pinned through the endpoint relation's
 *       navigation column rather than twice), one case per navigation and one per shape that
 *       contributes no hop, the two silences meaning different things being the claim;
 *       {@code no.sikt.graphitron.model.intent.NodeIdDecodeColumnTest} binds where the decoded
 *       values land ({@code graphitron_node_id_decode_column}, and with it the foreign-key pairing
 *       ({@code intent_foreign_key_column_pair}) and its walk-oriented reading
 *       ({@code graphitron_node_id_decode_hop_column}) that the lift walks over), one case per shape
 *       the lift either carries or drops, the absent local column being the claim rather than the
 *       present one;
 *       {@code no.sikt.graphitron.rewrite.derive.PathTerminusTest} binds
 *       {@code intent_field_path_terminus}, where a {@code @routine} chain lands and what kind of
 *       table that is, and with it the hop view's name-matched arm, which only a chain departing a
 *       function result reaches;
 *       {@code no.sikt.graphitron.rewrite.derive.ReferenceDiscoveryTest} binds
 *       {@code intent_field_reference_discovery}, what an omitted path finds between a field's two
 *       endpoints, over captured SDL against that same catalog for the same reason and because each
 *       shape it has to answer for is a shape the catalog already declares, half its cases pinning
 *       the coordinates that discover nothing because the boundary of a discovery is what the
 *       relation claims;
 *       {@code no.sikt.graphitron.rewrite.derive.RoutineReturnBindingTest} binds
 *       {@code intent_routine_return_binding}, the table a type is bound to by being what a chain
 *       returns, and {@code graphitron_resolved_type_binding}, where that population meets the
 *       {@code @table} one, including the seat the derivation excludes and the arity the
 *       coalescing recount produces;
 *       {@code no.sikt.graphitron.rewrite.derive.NameMatchedKeyPairTest} binds the pair behind a hop
 *       out of a table-valued function's result, {@code sql_name_matched_key_column} stating how
 *       such a hop is keyed and {@code intent_carrier_routine_hop} the population that reaches that
 *       rule from a coordinate no author wrote a path at; the pairing half asserts against the test
 *       catalog's own functions without scoping by graph, the relation carrying no graph partition,
 *       and half of both halves pins what the relations refuse to hold, a rule pairing every
 *       function against every keyed table being mostly boundary;
 *       {@code no.sikt.graphitron.rewrite.derive.CarrierDataFieldTest} binds
 *       {@code graphitron_carrier_data_field}, where a mutation payload's data arrives, over captured
 *       SDL against that same catalog and the test classes' own census, half its cases pinning the
 *       payload shapes that name nothing because a carrier's boundary is most of what the relation
 *       claims;
 *       the fact document {@code field-column-table.graphqls} binds
 *       {@code intent_field_column_table}, the override a column name's site resolves through, and
 *       {@code graphitron_field_target_column}, the column a scalar reads, over real SDL and the
 *       real catalog, the coordinates that produce no override row being as much its claim as the ones
 *       that do;
 *       the member names a backing class offers are no longer a derivation at all, the reading
 *       writing them down as {@code code_read_slot}, so the rule is pinned once rather than twice
 *       and where the reading lives: {@code no.sikt.graphitron.model.capture.code.CodeCaptureTest}
 *       binds it against a real reading of this build's own classes, both arms and every near-miss
 *       of the bean rule stated as a fixture whose declared form the test did not write;
 *       {@code no.sikt.graphitron.model.intent.FieldProducerMethodTest} binds the producer pair,
 *       {@code intent_field_producer_reference} naming the method an authored Java reference spells
 *       and {@code intent_field_producer_method} the census method it resolves to, to rows stated on
 *       both sides in the module whose DDL declares them, a reference and the census it is matched
 *       against being names and nothing else; half its cases pin what the resolution refuses to
 *       decide (an overloaded name is rows and an arity, never a pick) and two pin what survives it,
 *       a reference the census matched nothing for still being a reference;
 *       the fact document {@code field-routine-method.graphqls} binds
 *       {@code intent_field_routine_method}, the generated call surface a {@code @routine}
 *       application resolves to, over the real catalog, with the two causes of absence a catalog
 *       can produce told apart by the spelling (a name matching no catalog object, and one
 *       matching a stored table rather than a callable);
 *       {@code graphitron_type_backing}, which class backs a type and by which population, is
 *       bound by the fact documents {@code type-backing.graphqls},
 *       {@code type-backing-conflict.graphqls} and
 *       {@code type-backing-without-a-record-class.graphqls} over real classes and the real
 *       catalog, with {@code no.sikt.graphitron.model.TypeBackingPartitionTest} carrying the cases
 *       a document cannot state because they vary the classpath (a graph reading its own entries
 *       only, one class name on two entries, a parameter compiled without its name);
 *       {@code no.sikt.graphitron.rewrite.derive.TypeBackingClassTest} binds its reach to captured
 *       SDL over a stated census, its cases pinning how far the frontier goes, that a cycle
 *       terminates, the one condition that stops a hop, and each population it deliberately does
 *       not reach;
 *       {@code no.sikt.graphitron.rewrite.capture.ClassificationDomainTest} states
 *       {@code intent_type_domain}'s membership as hand-written expectations over a real capture,
 *       whole sets where the schema is small enough to write one out, with the two node-seed
 *       widenings and the assembly cliff each carrying their own case, so the relation is pinned to
 *       what its seed rule promises rather than to what the retiring walk reached;
 *       {@code no.sikt.graphitron.rewrite.derive.InputOccurrenceShadowTest} binds the input
 *       occurrence-path pair to a structural reference enumeration and to the walk's use-keyed
 *       cascade verdicts;
 *       {@code no.sikt.graphitron.rewrite.derive.ProducerDeliveryTest} binds
 *       {@code graphitron_producer}'s split and fan-out fields to the walk's own gathered delivery
 *       relation over a real capture, and pins a service payload's table child reaching a backing
 *       the graphitron gatherer anchored from a captured census, with the fact documents
 *       {@code producer-out-of-the-parent-statement.graphqls} and
 *       {@code producer-root-by-binding.graphqls} carrying what the producer rule makes of those
 *       rows;
 *       {@code no.sikt.graphitron.rewrite.derive.AuthoredClaimConflictsTest} binds the
 *       {@code intent_authored_claim_conflict} view to hand-written per-fixture expectations
 *       the view does not produce, the re-aimed anchor left standing after the corpus shadow
 *       proved the cutover and retired with the Java reduction it shadowed, and the two claim
 *       relations it reads are pinned given rows by
 *       {@code no.sikt.graphitron.model.intent.AuthoredClaimTest}; the same class binds
 *       {@code intent_authored_claim_rejection}, the post-capture mint over that view, on the one
 *       claim a minted relation can be held to, that it stands one-to-one with the view it mints
 *       from and words each violation exactly as the build-error projection does, which is what
 *       the diagnostics surface's inner join over it rests on). Later derivation
 *       strata land as registrations here, not as exemptions.</li>
 *   <li>{@link Arm#UNSHADOWED} for relations the shadowed model has no counterpart for, so that
 *       agreement with it is not a question that can be asked of them. Every other arm answers
 *       "does this match what {@code GraphitronSchema} says"; these are part of what replaces it,
 *       and there is no row over there to match. The registration is not an exemption: it names
 *       what does pin the rows instead, and a relation registered here whose facts the old model
 *       turns out to hold is a registration somebody has to correct.</li>
 *   <li>{@link Arm#ORACLE} for relations an oracle writer owns, at the oracle's own cadence
 *       (javac writes after capture; the legacy classification walk's reach writes inside the
 *       capture-and-detect pass), where no independent second walk can re-derive the oracle's
 *       verdict without re-running the oracle. Two anchors, both non-vacuous: a two-graph
 *       lifecycle anchor (seeded rows, so "cleared" is distinguishable from "never written",
 *       under two graphs, so "cleared what it owns" is distinguishable from "cleared
 *       everything") and a write-read content anchor (the same round reduced two ways, at the
 *       oracle's cadence). The one thing genuinely unpinned is the oracle's verdict itself.</li>
 * </ul>
 *
 * <p>These tests retire as consumers migrate off {@code GraphitronSchema} piece by piece; they pin
 * a shadow copy, and a shadow with a reader does not need one.
 */
@PipelineTier
class FactCaptureAgreementTest {





    /**
     * One capture of {@code graph}, by the pass that owns it, with every input named.
     *
     * <p>Each is a fixture's own answer to what the run had, and an arm asserting that one graph's
     * reading does not reach another's needs to say so: a capture handed this build's classpath
     * because the helper always passes it would carry classpath sources into a membership the arm
     * is about to compare against an SDL census.
     */
    private static void capture(GraphitronModelStore store, GraphIdentity graph,
                                SubjectConfig corpus, List<ClasspathEntry> classpath,
                                CatalogInput jooq) {
        ModelCapture.capture(store.dsl(), graph, CapturedStore.withCatalog(corpus, jooq), classpath,
            CapturedStore.loaderOf(jooq),
            java.time.LocalDateTime.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS));
    }

    /**
     * The {@code ORACLE} arm's lifecycle anchor: a compile's verdict is the compile's to replace,
     * and no other writer's to empty.
     *
     * <p>A capture used to empty this partition, and the property was the wholesale graph-scoped
     * clear's rather than anyone's design: that clear emptied every graph-keyed relation it did
     * not know the owner of, which is how a relation capture does not write came to be emptied by
     * one. It reached across a stratum boundary to do it. A javac verdict is a fact about a
     * compile, and between a capture and the compile that follows it an emptied partition says the
     * last compile was clean, which is a wrong answer where the previous round's rows are a stale
     * one.
     *
     * <p>So what is pinned is the writer's own replacement, scoped: a round lands, the next round
     * replaces it whole, and a sibling graph's partition is untouched throughout, which under a
     * shared store is the difference between a correct writer and one that eats another module's
     * diagnostics. Seeding is what distinguishes "replaced" from "never written", since an
     * unseeded emptiness check would pass identically with the writer deleted. A capture between
     * the two rounds is what pins the retirement itself. The one thing genuinely unpinned is
     * javac's verdict: no independent second reduction can re-derive it without re-running javac.
     */
    @Test
    @DisplayName("a round replaces its own graph's javac partition and no other's, and a capture leaves both")
    void oracleLifecycleClearsTheOwnedJavacPartitionOnly(@TempDir Path tmp) throws java.io.IOException {
        Path ownDir = java.nio.file.Files.createDirectories(tmp.resolve("own"));
        Path siblingDir = java.nio.file.Files.createDirectories(tmp.resolve("sibling"));
        try (var store = GraphitronModelStore.open()) {
            var own = new GraphIdentity("own", ownDir);
            var sibling = new GraphIdentity("sibling", siblingDir);

            // Capture writes no oracle row, so a graph nothing has compiled has an empty partition.
            CapturedStore.writeSource(ownDir, "type Query { ping: String }");
            capture(store, own, CapturedStore.corpusOf(ownDir), List.of(), null);
            assertThat(javacPartition(store, "own")).isEmpty();

            // Rounds land after capture, under both graphs.
            var round = new CompileRound(false, List.of(
                new CompileDiagnostic("file:///gen/A.java", 3, 1, "ERROR", "compiler.err.cant.resolve",
                    "cannot find symbol")));
            new CompileFacts(store.dsl(), own).write(round);
            new CompileFacts(store.dsl(), sibling).write(round);
            var ownBefore = javacPartition(store, "own");
            var siblingBefore = javacPartition(store, "sibling");
            assertThat(ownBefore).isNotEmpty();
            assertThat(siblingBefore).isNotEmpty();

            // A capture between the rounds leaves both, writing nothing in this family either way.
            CapturedStore.writeSource(ownDir, "type Query { ping: String }");
            capture(store, own, CapturedStore.corpusOf(ownDir), List.of(), null);
            assertThat(javacPartition(store, "own"))
                .as("a capture writes no row here and empties none: the last compile's verdict stands")
                .isEqualTo(ownBefore);

            // The next round replaces exactly its own graph's partition.
            var second = new CompileRound(true, List.of());
            new CompileFacts(store.dsl(), own).write(second);
            assertThat(javacPartition(store, "own"))
                .as("the captured graph's javac partition, after its own next round")
                .isEmpty();
            assertThat(javacPartition(store, "sibling"))
                .as("the sibling graph's javac partition, after another graph's round")
                .isEqualTo(siblingBefore);
        }
    }

    /**
     * The {@code ORACLE} arm's content anchor: the same round reduced two ways, at the oracle's
     * cadence. The {@code EQUALITY} arm's own character applied to a writer instead of a walk,
     * and what catches a writer bug (dropped rows, ordinal collisions, a transaction split) that
     * construction alone would let through. Ordinal grain included: two identical diagnostics at
     * one position are two rows, numbered in round order.
     */
    @Test
    @DisplayName("the javac relation's rows equal the round's published list, ordinal grain included")
    void oracleContentEqualsTheRoundsPublishedList(@TempDir Path tmp) {
        try (var store = GraphitronModelStore.open()) {
            var twin = new CompileDiagnostic("file:///gen/A.java", 12, 7, "ERROR",
                "compiler.err.cant.resolve", "cannot find symbol");
            var round = new CompileRound(false, List.of(
                twin, twin,
                new CompileDiagnostic("(no source)", -1, -1, "WARNING", null, "unchecked call")));
            new CompileFacts(store.dsl(), graph(tmp)).write(round);

            var expected = new LinkedHashSet<String>();
            var ordinals = new LinkedHashMap<String, Integer>();
            for (var d : round.diagnostics()) {
                int ordinal = ordinals.merge(d.file() + "|" + d.line() + "|" + d.column(), 1,
                    Integer::sum) - 1;
                expected.add(String.join("|", d.file(), String.valueOf(d.line()),
                    String.valueOf(d.column()), String.valueOf(ordinal), d.kind(),
                    String.valueOf(d.code()), d.message()));
            }

            var captured = new LinkedHashSet<String>();
            store.dsl().selectFrom(JAVAC_DIAGNOSTIC).fetch().forEach(row -> captured.add(
                String.join("|", row.getFile(), String.valueOf(row.getLineNumber()),
                    String.valueOf(row.getColumnNumber()), String.valueOf(row.getOrdinal()),
                    row.getKind(), String.valueOf(row.getCode()), row.getMessage())));
            assertThat(captured).isEqualTo(expected);
        }
    }

    /**
     * The loaded diagnostics arms' lifecycle anchor, on the same terms as the javac one: each
     * writer replaces its own graph's partition, a capture writes and empties nothing here, and a
     * sibling graph is untouched throughout. One anchor covers all three relations (and the
     * residue's directive child), because they share one writer cadence.
     */
    @Test
    @DisplayName("a write replaces its own graph's loaded diagnostics partitions and no other's, and a capture leaves both")
    void oracleLifecycleClearsTheOwnedDiagnosticsPartitionsOnly(@TempDir Path tmp) throws java.io.IOException {
        Path ownDir = java.nio.file.Files.createDirectories(tmp.resolve("own"));
        Path siblingDir = java.nio.file.Files.createDirectories(tmp.resolve("sibling"));
        try (var store = GraphitronModelStore.open()) {
            var own = new GraphIdentity("own", ownDir);
            var sibling = new GraphIdentity("sibling", siblingDir);
            CapturedStore.writeSource(ownDir, "type Query { ping: String }");
            capture(store, own, CapturedStore.corpusOf(ownDir), List.of(), null);
            CapturedStore.writeSource(siblingDir, "type Query { ping: String }");
            capture(store, sibling, CapturedStore.corpusOf(siblingDir), List.of(), null);
            assertThat(diagnosticsPartition(store, "own")).isEmpty();

            var loc = new graphql.language.SourceLocation(3, 1, ownDir.resolve("s.graphqls").toString());
            var errors = List.of(
                no.sikt.graphitron.model.diagnostics.ValidationError.forField("Film.title",
                    no.sikt.graphitron.model.diagnostics.Rejection.directiveConflict(
                        List.of("service", "routine"), "@service, @routine are mutually exclusive"), loc));
            var warnings = List.<no.sikt.graphitron.model.diagnostics.BuildWarning>of(
                new no.sikt.graphitron.model.diagnostics.BuildWarning.NoRule("advisory", loc));
            new no.sikt.graphitron.model.diagnostics.RejectionFacts(store.dsl(), own).write(errors);
            new no.sikt.graphitron.model.diagnostics.BuildWarningFacts(store.dsl(), own).write(warnings);
            new no.sikt.graphitron.model.diagnostics.RejectionFacts(store.dsl(), sibling).write(errors);
            new no.sikt.graphitron.model.diagnostics.BuildWarningFacts(store.dsl(), sibling).write(warnings);
            var ownBefore = diagnosticsPartition(store, "own");
            var siblingBefore = diagnosticsPartition(store, "sibling");
            assertThat(ownBefore).isNotEmpty();
            assertThat(siblingBefore).isNotEmpty();

            CapturedStore.writeSource(ownDir, "type Query { ping: String }");
            capture(store, own, CapturedStore.corpusOf(ownDir), List.of(), null);
            assertThat(diagnosticsPartition(store, "own"))
                .as("a capture writes no row here and empties none: the last run's diagnostics stand")
                .isEqualTo(ownBefore);

            new no.sikt.graphitron.model.diagnostics.RejectionFacts(store.dsl(), own).write(List.of());
            new no.sikt.graphitron.model.diagnostics.BuildWarningFacts(store.dsl(), own).write(List.of());
            assertThat(diagnosticsPartition(store, "own"))
                .as("the captured graph's partitions, after its own next write")
                .isEmpty();
            assertThat(diagnosticsPartition(store, "sibling"))
                .as("the sibling graph's loaded diagnostics partitions, after another graph's write")
                .isEqualTo(siblingBefore);
        }
    }

    /** The graph's loaded diagnostics rows across all four relations, rendered stably. */
    private static List<String> diagnosticsPartition(GraphitronModelStore store, String graphName) {
        var rows = new ArrayList<String>();
        store.dsl().selectFrom(REJECTION_VALIDATION_ERROR)
            .where(REJECTION_VALIDATION_ERROR.GRAPH_NAME.eq(graphName))
            .orderBy(REJECTION_VALIDATION_ERROR.ORDINAL)
            .forEach(row -> rows.add("rejection|" + row.getOrdinal() + "|" + row.getMessage()));
        store.dsl().selectFrom(REJECTION_VALIDATION_ERROR_DIRECTIVE)
            .where(REJECTION_VALIDATION_ERROR_DIRECTIVE.GRAPH_NAME.eq(graphName))
            .orderBy(REJECTION_VALIDATION_ERROR_DIRECTIVE.ERROR_ORDINAL,
                REJECTION_VALIDATION_ERROR_DIRECTIVE.POSITION)
            .forEach(row -> rows.add("directive|" + row.getErrorOrdinal() + "|" + row.getDirective()));
        store.dsl().selectFrom(LINT_FINDING)
            .where(LINT_FINDING.GRAPH_NAME.eq(graphName))
            .orderBy(LINT_FINDING.ORDINAL)
            .forEach(row -> rows.add("lint|" + row.getOrdinal() + "|" + row.getMessage()));
        store.dsl().selectFrom(BUILD_WARNING_NO_RULE)
            .where(BUILD_WARNING_NO_RULE.GRAPH_NAME.eq(graphName))
            .orderBy(BUILD_WARNING_NO_RULE.ORDINAL)
            .forEach(row -> rows.add("advisory|" + row.getOrdinal() + "|" + row.getMessage()));
        return rows;
    }

    /** The graph's {@code javac_diagnostic} rows, rendered stably for before/after comparison. */
    private static List<String> javacPartition(GraphitronModelStore store, String graphName) {
        return store.dsl().selectFrom(JAVAC_DIAGNOSTIC)
            .where(JAVAC_DIAGNOSTIC.GRAPH_NAME.eq(graphName))
            .orderBy(JAVAC_DIAGNOSTIC.FILE, JAVAC_DIAGNOSTIC.LINE_NUMBER,
                JAVAC_DIAGNOSTIC.COLUMN_NUMBER, JAVAC_DIAGNOSTIC.ORDINAL)
            .fetch()
            .map(Object::toString);
    }

    /** The graph these anchors capture under; one per store, so joins stay within one run's rows. */
    private static GraphIdentity graph(Path tmp) {
        return new GraphIdentity("FactCaptureAgreementTest", tmp);
    }

    /** The generated test catalog, as the cases reading the node rule capture against. */
    private static JooqCatalog catalog() {
        var ctx = testContext();
        return new JooqCatalog(ctx.jooqPackage(), ctx.codegenLoader());
    }
}
