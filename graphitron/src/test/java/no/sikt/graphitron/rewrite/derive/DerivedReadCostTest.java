package no.sikt.graphitron.rewrite.derive;

import no.sikt.graphitron.common.configuration.TestConfiguration;
import no.sikt.graphitron.model.boot.ReadBudget;
import no.sikt.graphitron.model.boot.StoreAnswer;
import no.sikt.graphitron.model.derive.MaterializeDependencies;
import no.sikt.graphitron.model.derive.Materializations;
import no.sikt.graphitron.model.test.RunawayRelation;
import no.sikt.graphitron.model.test.UnregisteredRelation;
import no.sikt.graphitron.rewrite.CapturedStore;
import no.sikt.graphitron.rewrite.JooqCatalog;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A materialization registration may not make some <em>other</em> relation's read more expensive.
 *
 * <p>One directional claim with no number in it: for every registration in {@code meta_materialize}
 * and every relation whose derivation reaches that registration's target, reading the registered
 * shape must not visit more rows than reading the unregistered one. A registration is a shared
 * investment, bought for one reader's sake and paid for by every relation that names the target, so
 * making a different reader worse is a regression whether or not anybody has named a budget for that
 * reader. Nothing else in the tree makes this claim: {@code MaterializeRegistryGateTest} closes the
 * register against the schema and asks nothing about cost, and the {@code scanCount} ceilings in
 * {@code graphitron-lsp} are held over reader surfaces rather than over relations.
 *
 * <p>No ceiling, and that is a correction rather than an omission. A ceiling of the kind
 * {@code SurfaceScanCountTest} holds works there because the registration <em>helped</em>, so the
 * number sits between a lower registered figure and a higher unregistered one and reinstating the
 * defect raises the figure past it. Where a registration hurts, the ordering is inverted: a ceiling
 * above the current figure can never be failed by that mutation, and one between the two figures
 * fails on the shipping tree. The two rules are jointly unsatisfiable exactly when the regression is
 * real, so the discriminating assertion is the direction itself.
 *
 * <p><b>Instrument.</b> The {@code scanCount} H2 annotates each {@code EXPLAIN ANALYZE} plan node
 * with, summed over the plan. A count of rows visited is the same number on a fast machine and a
 * loaded one, which is what lets a tier that must not fail for being slow still hold a cost claim.
 * No duration is asserted anywhere here. Scan counts and wall clocks are different claims, so no
 * figure measured by a timing probe transfers into this test.
 *
 * <p><b>Both axes come off the booted store.</b> Registrations from
 * {@link Materializations#registrations}, readers from
 * {@link MaterializeDependencies#registrationsReachedByView}. A hand-kept list of either would rot
 * as views are added, and the reachability walk is also what keeps the matrix small: a registration
 * can only change what a relation costs if that relation's derivation names its target, so most of
 * the {@value #READERS_IN_SCHEMA} views times {@code meta_materialize}'s registrations do not
 * exist as cells.
 *
 * <p><b>Two pinned sets, both asserted by equality rather than as ceilings or allowlists.</b>
 * Equality is what gives the ratchet: adding a pair fails the build, and so does removing one, so the
 * day a lever lands the assertion fails until the row goes rather than the row surviving as a stale
 * exemption nobody is forced to revisit. {@code MaterializeRegistryGateTest}'s {@code HAND_WRITTEN}
 * set is the precedent for a pinned roster of deliberate exceptions in this family.
 *
 * <p>Joining {@code ExemptionRegistry}, this module's shipped exemption mechanism, is the right move
 * for the second and third mechanism that needs one and not for the first: its {@code Obligation} is
 * typed on {@code Class<?>} keys throughout and a pair of relation names is not a class, so joining
 * means generifying the row's key type, and its {@code Exemption} arms are a coverage-triage taxonomy
 * whose own javadoc argues against arms no population confirms, so an accepted-cost-regression arm
 * would have to be added. Two changes to a shared mechanism for one test is the wrong trade. Its
 * discovery guard does not force the issue either, firing on a static {@code Map<..., Exemption>}
 * where these are sets of strings. Stated here so the next author of such a set knows where the line
 * is rather than rediscovering it.
 */
@PipelineTier
class DerivedReadCostTest {

    /**
     * Repetitions of the fixture's node cluster. Chosen for a stated reason rather than by taste, and
     * now for two of them, because what this size buys is scale-dependent on both of the gate's
     * pinned sets.
     *
     * <p>{@link #KNOWN_NON_MONOTONIC} is scale-dependent, and not monotonically: it is empty at one
     * unit, where the gate would see nothing at all, three pairs at four units, four at eight, and
     * nine at twelve, where several borderline cells' plans flip against the statistics that size
     * implies. Every twelve-only pair was answered as an index question and declined on
     * measurement, per the set's own javadoc, so what twelve adds is visibility rather than noise.
     * Twelve is kept because it is the size that ships and the fixture may grow and may not shrink;
     * the set is pinned at the size the gate actually runs, and the two smaller sizes are recorded
     * here so the next re-pin knows the boundary moved before and can move again. Those three
     * figures were re-taken when the fixture grew its input surface, because a size boundary is a
     * measurement like any other and does not survive the fixture it was taken on. They are the
     * boundary and not a running count of the set, which has grown past nine since as relations
     * were added and which the set below states for itself.
     *
     * <p>The fixture may grow and may not shrink. A smaller one is the single change that would make
     * this gate pass while seeing nothing, the registrations existing precisely because a rule is
     * re-evaluated many times over a schema of real size.
     */
    private static final int UNITS = 12;

    /** Views in the fact schema, of which {@value #READERS_WITH_CELLS} reach a registration. */
    private static final int READERS_IN_SCHEMA = 115;

    /** Views whose derivation reaches at least one registration's target. */
    private static final int READERS_WITH_CELLS = 68;

    /**
     * The cells the domain holds: one per (registration, reaching relation) pair. Stated so the matrix
     * cannot grow silently as views are added; a new view that puts new cells in the domain fails this
     * figure until somebody has looked at what it costs.
     *
     * <p>It moves down as well as up, and a registration is what moves it both ways at once. The
     * reachability walk records a registration when it meets that registration's target and stops
     * there rather than descending, so registering a relation cuts every reader's reach at it: each
     * reader that reached a registration only through the newly registered relation loses that cell,
     * while the new registration and its {@code _live} view add cells of their own. Registering
     * {@code intent_node_id_instruction} was the first change to make the figure fall, by five on
     * net, and a delta rather than a pair of absolutes because every new view moves the baseline.
     * So a drop here is not the matrix quietly seeing less; it is cost moving off a reader and onto
     * a refresh, and the refresh is a view in this domain and priced like any other.
     *
     * <p>Registering {@code intent_mutation_write_destination} moved it by one, which is the smallest
     * a registration can move it and worth the sentence because the arithmetic is not obvious. The
     * relation was already a view in this domain with cells of its own; registering it renamed those
     * cells onto the {@code _live} view rather than removing them, and the reader the registration was
     * for, {@code intent_mutation_write_agreement}, reaches exactly one registration because the walk
     * stops at the target it reads. A registration whose rule is read only through the relation being
     * registered is the case that moves this figure least.
     *
     * <p>Registering {@code intent_node_id_decode_column} took it from 178 to 174, a fall of four on
     * net, and it is the drop-is-not-blindness case again: that relation sits under three readers and
     * over the whole node-id decode family, so every reader that reached the hop-column, instruction
     * and reference-step registrations through it now stops at its target, while the registration and
     * its {@code _live} view put cells of their own back. Neither {@link #READERS_IN_SCHEMA} nor
     * {@link #READERS_WITH_CELLS} moved, which is the arithmetic worth stating rather than the four:
     * a registration replaces one view in this domain with another, the rule keeping its cells under
     * the {@code _live} name.
     *
     * <p>{@code intent_condition_param_decode} took it from 168 to 171, the plain case: a new view
     * that reaches three registrations and displaces none, so the figure moves by exactly the cells
     * it adds. Both reader figures moved by one beside it, which is what a new view looks like when
     * it registers nothing.
     */
    private static final int CELLS = 171;

    /**
     * The multiple of the registered side's own wall clock allowed to the unregistered side before the
     * cell is recorded as unmeasurable rather than compared. Relative rather than absolute for the
     * objection {@link RunawayRelation}'s javadoc raises against a fixed threshold, that one small
     * enough to be reliable on one machine is a flake on another: both sides of a cell are timed in
     * the same run on the same machine, so a loaded machine slows both and the ratio holds. Large
     * because the gap the register documents is seconds against never, not one shape against a
     * slightly slower one.
     */
    private static final long BUDGET_MULTIPLE = 50;

    /**
     * Floor under {@link #BUDGET_MULTIPLE}, in milliseconds, for the cells whose registered side is
     * a millisecond or two: fifty times nearly nothing is nearly nothing, and
     * {@link ReadBudget.Bounded} refuses a non-positive figure outright.
     *
     * <p>Generous on purpose, and the direction is what makes that safe: a larger floor compares
     * more cells and can only make this gate stricter, never more permissive, because a cell inside
     * its budget is asserted on and a cell outside it is merely recorded. The figure is set from the
     * slowest unregistered side that finishes, which is under seven seconds on this fixture, so that
     * every cell is compared and {@link #KNOWN_EXHAUSTED} stays empty on a machine of any speed.
     *
     * <p>It was two seconds, and two seconds was too tight to be a fact about the code. Two cells'
     * unregistered sides land within a factor of two of that figure, so which of them appeared in
     * {@link #KNOWN_EXHAUSTED} varied from run to run on one machine, and an equality-pinned set
     * whose contents depend on load is a flake wearing a ratchet's clothes. The distinction
     * {@link #BUDGET_MULTIPLE} exists to draw is seconds against never; a three-second read against
     * a three-second budget is not that distinction.
     */
    private static final long BUDGET_FLOOR_MILLIS = 30_000;

    /**
     * The budget {@link #aCellThatCannotAnswerIsRecordedRatherThanFailed} gives its own cell, which
     * is small where {@link #BUDGET_FLOOR_MILLIS} is generous and for the reason that makes both
     * right. That cell's relation cannot terminate by construction, so no budget lets it finish and
     * the only thing a larger one buys is waiting; every other cell's relation does terminate, and
     * there the budget must be clear of how long that takes.
     */
    private static final long RUNAWAY_BUDGET_MILLIS = 2_000;

    /**
     * The pairs where the registered shape costs more, {@code registration|reader}, each one a finding
     * rather than a tolerance.
     *
     * <p>Three mechanisms, each answered by measurement, and the set holds nothing else. The first
     * two are the pruning
     * an inlined view body offered and a table cannot: the argmapping parameter-type reader's
     * site-literal arms pruned the pair view's union to one arm apiece and now visit the whole table
     * per arm (815 scans registered against 357), and the errors-field member view drives from the
     * whole relation, whose inlined predicates let the fused plan skip rows the plain table join
     * visits (916 against 761). Each was answered as an
     * index question first: every index shape tried on either target moved no reader at all or made a
     * dear one worse, so the scans are the readers' own cost of standing on a table, sub-millisecond
     * against the seconds each registration buys.
     *
     * <p>The segment-binding reader stood beside the parameter-type one on the same mechanism, at
     * 433 scans against 391, and left when the fixture grew an input surface: the extra arguments
     * change what the planner knows about the pair relation and the arm stops being the cheaper
     * shape. Recorded rather than simply deleted, because the row leaving on a fixture change is a
     * different fact from a row leaving because a lever landed, and the difference is what the next
     * author needs.
     *
     * <p>The last four share one mechanism and one named lever. Registering the carrier relation
     * changed the planner's join order in the hop and the seat: both now reach {@code graphql_field}
     * through a join on its {@code named_type} column, which no key serves (the primary key leads
     * with the field's own coordinate), so one plan node seeks by graph alone and visits the whole
     * field census per driving row. The hop reads 19619 scans registered against 6820 with the
     * carrier unregistered and 1570 with the spelled table unregistered; the seat 27531 against 26255
     * and 11043. The wall clocks are one and ten milliseconds respectively, against the tens of
     * seconds per generation the carrier registration buys on a real schema. Every lever inside the
     * registration's own scope was measured and moved nothing: index shapes on the carrier target
     * (identical to the scan), a FROM-order restructure of the hop (H2 reorders base-table join
     * graphs freely), and driving the hop from the payload-producer relation instead of the reverse
     * named-type probe (equal answers, 17003 scans). The lever that works is a declared index on
     * {@code graphql_field}'s named-type coordinate, measured at 2137 / 10049 scans for hop and seat
     * and clearing three of these four rows, but an authored index on a captured base table is a
     * schema-wide discipline question (its reader axis is the whole derived stratum, its cost is on
     * every capture's write path, and the index-comment gate does not reach it), so it is filed on
     * the roadmap with these measurements rather than shipped on four readers' evidence. These rows
     * are pinned rather than tolerated because a tolerance would be a number, and a number here is
     * the one thing this gate is built without; the day that index lands, the equality assertion
     * deletes them.
     *
     * <p>The last four are the instrument's own floor rather than work, and they are worth reading
     * before adding anything that looks like them. The three input-field resolution relations each
     * name the reference-step hop relation, and against the registered target they visit exactly
     * four rows more than against the source view: 189 against 185, 345 against 341, 1110 against
     * 1106. H2 charges a table visit at least one scan per naming where a view whose evaluation
     * short-circuits is charged none, and four namings is what these three bodies make between the
     * walk's anchor and its recursive term. The wall clocks run the other way and decisively, two
     * milliseconds against thirteen, four against fourteen and six against twenty-six, which is the
     * shape that says this is the counter's floor and not a cost. No index question arises: the
     * target already carries one, and a difference of four scans is not an index's to move.
     *
     * <p>The fourth is the same floor counted three times over: the input-field role relation, 4845
     * against 4833, whose body names the column-match relation in three of its arms, so the walk's
     * four namings are made three times. Twelve scans on 4845 is a quarter of one per cent, and here
     * the clocks are a wash rather than decisively better, sixty milliseconds against fifty-nine,
     * which is what a difference that size looks like on a clock. Three namings looks like something
     * to fold into one, and the one-pass shape that would do it was written and measured and is not
     * an improvement: see that relation's own comment, which carries the figures and the reason.
     *
     * <p>The last three are where the counter and the clock disagree outright, and they are the
     * clearest case in this set for reading the instrument as a row count rather than as a cost. The
     * scope relation gained a rung that reads the carrier relation, so the argument-grain fan-out
     * above it and the write-payload relation above that inherit the cell. Registered, the three
     * visit 40608, 40947 and 40735 rows; unregistered, 24620, 24959 and 24747, so by this gate's
     * metric all three are regressions and by a factor of nearly two. The wall clocks are 42, 41 and
     * 83 milliseconds registered against 73, 73 and 160 unregistered, three runs apiece on the
     * twelve-unit fixture with the spread inside two milliseconds: the shape visiting two thirds
     * more rows is the one that takes half as long, on every relation and on every run. Nothing here
     * is an index question, the carrier target carrying one already and the join reaching it on its
     * own graph and type. The rung's own price was measured the same way, by removing the arm: the
     * scope relation is 21653 scans and 33 milliseconds without it against 40608 and 42 with it, so
     * the question the store could not answer at all before costs about nine milliseconds, paid once
     * per refresh of the argument scope rather than per read.
     *
     * <p>Three more cells joined those three as the write-payload family grew, and they are the same
     * disagreement further down rather than new findings. The refusal relation is 59099 rows in 80
     * to 87 milliseconds registered against 43111 in 149 to 157 unregistered; the payload column
     * relation 217475 in 3828 to 4095 against 153523 in 8137 to 8231; the matched key 417491 in 4004
     * to 4396 against 289587 in 8420 to 8736. Three runs apiece. Everything built over the payload
     * rung inherits the cell by construction, so the question to ask of the next such reader is
     * whether its clock agrees with the ones above rather than whether its counter does.
     *
     * <p>This gate found a defect rather than pricing a cost while those figures were being taken,
     * and it is worth the space because three levers were measured against it and all three refused.
     * The payload column rule cost four seconds a read where the refusal relation it is built on
     * costs eighty milliseconds, and the matched key over it inherited that and added half a second.
     * The occurrence cut is not the cause: the admitted set costs 1961 milliseconds with the
     * anti-join and 2002 without. An index on the binding target cut rows visited from 217 thousand
     * to 42 thousand and moved the clock the wrong way, 3900 against 5071, which is this file''s own
     * caveat arriving a third time. And driving the two column arms from their own views rather than
     * from the admitted set, which is the lever that takes the carrier-role join from 2005
     * milliseconds to 74 in isolation, made the whole rule an order of magnitude worse: an inlined
     * common table expression is re-evaluated per driving row of whatever ends up outside it, which
     * is the same finding {@code intent_input_field_filter_role}''s registration already records at
     * its own site. What the plan showed is that the rows go where no rewrite reaches them: 631
     * nodes, the largest being the binding join inside the scope family, re-expanded because H2
     * inlines a view wherever it is named and eliminates no common subexpression.
     *
     * <p>So the rule was registered, which is what that shape is for, and the read cost went: the
     * target reads in two scans and no measurable time, and the matched key over it fell from 417491
     * rows in 4004 to 4396 milliseconds to 200018 in 244 to 272. That moved the cost onto the
     * refresh and was not the end of it. A refresh runs on every capture, including the reactor''s
     * own, and on the sakila example schema the capture then did not finish at all: twenty-three
     * minutes of CPU with no output, where this fixture had said four seconds. The remaining
     * expansion was the same shape one relation down, the rule probing
     * {@code intent_mutation_payload_refusal} once per candidate occurrence and so re-evaluating a
     * view that names the write payload and through it the whole scope family. Registering that
     * relation too, with an index on the coordinate the probe writes, turns each probe into a seek
     * and the sakila capture finishes in under three minutes.
     *
     * <p>Worth keeping as the shape rather than as two figures. A per-row probe into a derived
     * relation is the cost here, every time; whether it shows up as a slow read or a slow refresh
     * depends only on which side of a registration the probe ends up on, and a synthetic fixture
     * twelve clusters wide understates it by as much as it takes to turn four seconds into no
     * termination at all.
     *
     * <p>The write-partition relations that landed next found the general form of that shape, and it
     * is worth stating separately because it is not about probes and not about this fixture. In H2 a
     * derived relation on the inner side of a join is re-evaluated once per driving row, whatever the
     * join is spelled as. Four relations in that family each had one and each cost between twenty and
     * fifty times its own children because of it: the matched key joined its ranked candidate set back
     * to the surface it was derived from, the key membership joined the matched key per payload column,
     * the write refusal joined a derived written-column set per matched-key row, and the write
     * destination anti-joined the refusal per membership row. None of them is a correlated {@code
     * EXISTS}; three are ordinary {@code LEFT JOIN}s and one is an inner join. Measured on a store
     * captured from the example schema, in milliseconds: 596 to 22, 1488 to 33, 1364 to 1.3, and 5275
     * to 52. The fixes are structural rather than tuned. Fold a self-join into one ranked pass, drive
     * from the smaller derived side instead of joining it in, replace a derived-to-derived join with a
     * window over one pass, replace an anti-join with a union of the two sides and a window, and look
     * a value up in a table rather than in a view.
     *
     * <p>That family also settled the order between a rewrite and a registration, which this file had
     * not previously had a case for. The key-membership registration was proposed first, before any of
     * those rewrites, and priced then at 326 seconds of refresh per capture: the rule being registered
     * was itself re-evaluating the matched key per payload column, so the registration would have
     * bought one relation's read by making every capture five times slower. The same registration
     * after the rewrites costs 36 milliseconds. A registration prices the rule as it stands, so a rule
     * with a re-evaluation inside it is rewritten before it is priced, and a refresh figure taken
     * before that is a measurement of the defect rather than of the registration.
     *
     * <p>The three relations that partition a matched key landed next, and the first reading of what
     * they cost here was wrong in a way worth keeping beside the correct one.
     * {@code intent_mutation_write_destination} names
     * {@code intent_mutation_payload_key_membership} three times and then names
     * {@code intent_mutation_write_refusal}, which names it twice more, so one read of the
     * destination expands that rule five times over and each expansion reaches the matched key and
     * through it the whole write-payload family. That is breadth, the ordinary registration case, it
     * is really there, and it is what an eleven-minute run was first attributed to. It was not the
     * binding constraint. The registration taken on that reading priced at the 326 seconds the
     * paragraph above records; the rewrites went in first, the same registration then cost 36
     * milliseconds, and this test went from not finishing inside eleven minutes to 74 seconds.
     *
     * <p>So the two shapes are worth telling apart, and a third thing with them. The probe two
     * paragraphs up is the one this fixture understates by orders of magnitude, and it is the one that
     * turns into a build that does not finish. Breadth is the ordinary registration case every row of
     * {@code meta_materialize} argues, and for it this test's own runtime is a serviceable instrument.
     * It is not an instrument that can tell the two apart, which is the third thing: what separated
     * them was a store captured from a real schema, H2's own query statistics over interleaved sweeps
     * with result reuse off, and bisecting each rule one common table expression at a time.
     *
     * <p>The fifth instance of the inner-side defect arrived with
     * {@code intent_mutation_write_agreement}, which reduces the destination against itself, and it
     * is the first in this family to be met in the right order. One evaluation of that rule was 75741
     * milliseconds with the destination a view and its own derived pin on the inner side of the
     * outermost join; reversing that join, so the small derived side drives and the destination is
     * probed, took it to 12983, registering the destination took it to 5.4, and an index on the write
     * coordinate and column took it to 1.8, against a refresh of 56 milliseconds per graph. Pricing
     * the registration before reversing the join would have measured the defect and called it the
     * cost of a view, which is the mistake the paragraph above records being made once already.
     *
     * <p>The next measurement in that family found the inner-side defect's mirror, and it is the
     * first here to argue against a registration rather than for one.
     * {@code intent_input_occurrence_descent_order} states the flattener's descent as a rank per
     * occurrence, and the two relations that had been comparing occurrences pairwise now probe it by
     * path instead. One evaluation of its rule whole is 8.6 milliseconds, which by the reasoning of
     * every paragraph above should make it ruinous on the inner side of a join. It is not: H2 pushes
     * a probe's equality down through the view it inlines, so what each probe evaluates is a slice of
     * the rule rather than the whole of it, and the destination refresh is 38.7 milliseconds with
     * this relation a view against 35.8 with it snapshotted into a keyed table. Snapshotted into a
     * table with no key on the probe coordinate it is 1846. So a table is fifty times worse than the
     * view it would replace unless it is keyed, because being evaluated restricted is the one thing
     * a table cannot do and an inlined view can. Read together with the three pairs below, which are
     * the same caveat met after a registration rather than before one: an unkeyed target is the
     * hazard either way, and a rule cheap to evaluate restricted is one a registration has least to
     * offer. On these figures registering it would buy three milliseconds of refresh and eight tenths
     * of a millisecond of read in exchange for an evaluation of the rule per capture, so it is a view.
     *
     * <p>Three larger pairs stood here until the targets were indexed, and how they left is worth
     * knowing before adding more. They were not the registrations' fault and no reader had to be
     * restructured: a materialized target was the only kind of table in this schema with no key on it,
     * so a registered view's join against one was a full scan of it, per driving row where the reader
     * correlates and per iteration where it recurses. Indexes on the targets and current statistics
     * after each refill answered all three, and the registrations they were charged to are unchanged.
     * So a new large pair here is a question about the target's index before it is a question about
     * the registration.
     *
     * <p>The pair that question was asked of and did not fully answer is
     * {@code intent_node_id_instruction|intent_condition_param_decode}, and the figures are worth
     * keeping because they show what an index on a materialized target can and cannot buy. That
     * reader is the first to probe the instruction table rather than drive from it, seeking one row
     * per captured {@code @condition} by site and coordinate, and unindexed it cost 9787 scans
     * against 2923 over the unregistered rule. The index on the coordinate it holds took it to 3451,
     * closing 6336 of the 6864-scan gap; widening that index to the argument name the ARGUMENT arm
     * also joins bought nothing, the seek already being selective. What the remaining 528 scans are
     * is the part no index reaches: the rule is a union whose arms are keyed by site, so a probe
     * fixing the site to a constant lets H2 evaluate one arm and skip the other outright, and the
     * table those arms were refilled into holds both sites' rows in one heap where the constant is a
     * filter rather than a pruned branch. That is the registration's own trade rather than a defect
     * in the reader or a shape somebody chose, which is why the pair stays here after the lever
     * landed instead of the lever being reverted: 528 scans is what the reader pays for a refresh
     * that evaluates the rule once per capture instead of once per read.
     *
     * <p>A trio left a second way, which is worth knowing because nothing was measured to send it.
     * Three readers of {@code intent_node_id_instruction} stood here charged to
     * {@code intent_argument_scope_table}: the encode, the decode slot, and the decode defect above
     * it. All three were small and flat, the instrument's own floor rather than work, H2 charging a
     * table visit at least one scan per naming where a view whose evaluation short-circuits is
     * charged none. Registering {@code intent_node_id_instruction} removed them by removing their
     * cells: none of the three names the scope table itself, all three reached that registration
     * through the instruction rule, and the walk now stops at the instruction's own target instead of
     * descending into what the rule reads. The cost did not evaporate, it moved: the rule still reads
     * the scope table, once per capture, in the refresh of
     * {@code intent_node_id_instruction_live}, which is a view in this domain and holds its own cell
     * against that registration monotonically. Read that as the shape to check the next time a
     * registration deletes rows here: a pair that leaves because its reader got cheaper and a pair
     * that leaves because its reader stopped reaching are different facts, and only the first is
     * about the registration it was charged to.
     */
    private static final Set<String> KNOWN_NON_MONOTONIC = Set.of(
        // The pruning an inlined body offered and a table cannot; measured index-free above.
        "intent_argmapping_pair|intent_argmapping_bound_parameter_type",
        // intent_errors_field|intent_errors_field_member stood here on the same attribution and
        // left when the relation underneath both of them stopped being reconstructed. What it was
        // really charged to was intent_poly_member: a two-arm union whose interface arm ranked its
        // position with a window over the whole partition, which an outer predicate cannot prune,
        // so the registered shape visited every implementor of every interface however narrow the
        // question. Capture writes that order now and the union is one captured relation, so the
        // pair is monotonic without anything about the registration changing. That is a third shape
        // to check when a row leaves this set, beside the two the paragraph above names: a pair can
        // stop being a regression because something neither of its relations mentions stopped being
        // a reconstruction.
        // The unindexed named-type join; the lever is filed on the roadmap, measured above.
        // Two of these four left when the type binding gained an index on the key its readers
        // hold, along with the scope table's own cell below: the carrier's two readers stopped
        // being non-monotonic outright, so the pairs go rather than being kept as history.
        "intent_spelled_table|intent_carrier_routine_hop",
        "intent_spelled_table|intent_mutation_routine_seat",
        // The instrument's own floor, four scans apiece; measured above.
        "intent_field_reference_step_hop|intent_input_field_reference_step_target",
        "intent_field_reference_step_hop|intent_input_field_column_scope",
        "intent_field_reference_step_hop|intent_input_field_column_match",
        "intent_field_reference_step_hop|intent_input_field_filter_role_live",
        // Named for the rule rather than the relation since the carrier was registered: the
        // rule keeps its cells under the _live name and the canonical name is a table the walk
        // stops at.
        "intent_field_reference_step_hop|intent_input_field_carrier_role_live",
        // The same floor reached through the input-field reference walk, which the decode's hop
        // child took up when the input-field path stopped being unwalkable.
        "intent_field_reference_step_hop|intent_node_id_decode_hop",
        "intent_field_reference_step_hop|intent_node_id_decode_hop_column_live",
        // The same floor again, reached because the write-payload family reads the input-field
        // one: twelve scans out of fifty-nine thousand on the refusal, thirty-six out of two
        // hundred thousand on the two above it; measured above. The refusal stood beside the
        // column relation here and left when the carrier was registered, and it is the
        // stopped-reaching kind of departure rather than the got-cheaper kind: that view
        // reached this rung only through the carrier rule, so the walk now stops at the
        // carrier target and the cell does not exist to be non-monotonic in. The column
        // relation names the reference walk on a second path of its own and keeps its cell.
        "intent_field_reference_step_hop|intent_mutation_payload_column_live",
        // A registered target's rule prunes by site where its table cannot, and an index closes
        // most but not all of the gap; measured above.
        "intent_node_id_instruction|intent_condition_param_decode",
        // Three readers reached through the navigation relation stood here and have gone, and how
        // they went is the second kind of departure this set records: a lever landed, rather than
        // the fixture moving under them. They were the counter-against-clock case, stating the
        // navigated type as a relation rather than as an inline expression having put them here
        // (H2 pushes an expression down into the type binding's probe and cannot push a projected
        // column, so under that plan the registered binding was scanned where the view it replaced
        // was evaluated restricted), and the clock said so: on a store captured from the sakila
        // example schema the carrier role was 14 ms against 14, the payload column 157 against 154
        // and the payload refusal 8 against 9. An index on the binding closed it for the two scope
        // relations above these readers and not for these. Registering
        // intent_node_id_decode_column is what closed it here, and it did so by removing the rows
        // rather than by moving the plan: all three readers reach the binding through that relation,
        // which they now read as a table, so the derivation that was scanning the binding per
        // driving row is evaluated once per refresh instead. The registration removed these three
        // and added no pair of its own.
        // The same rung, reached by the one relation in the write family that still expands the
        // scope family: the write payload's own rule, which is now a registration's source view
        // and so is reached here rather than by everything above it. Six pairs stood beside this
        // one and left together when the partition relations were rewritten and the payload was
        // registered, which is the shape worth reading off this set rather than the count. A pair
        // leaves here for two different reasons and only one of them is about a registration: a
        // reader that got cheaper, and a reader that stopped reaching the rung at all. These six
        // are the second kind. The refusal, the column relation, the matched key, the write
        // refusal and the write destination each used to expand the scope family on their way to
        // an answer, and each now reads a table for the same fact, so the rung is no longer on
        // their path and they hold no cell against it to be non-monotonic in.
        // That pair has gone, and it is the got-cheaper kind. Registering the carrier rule took
        // the payload column statement from about 85 milliseconds to about 25 on a store
        // captured from the sakila example schema, and the plan that costs less also visits
        // fewer rows through the write payload than the unregistered shape does, so the
        // direction this gate asserts is satisfied without any row here. Recorded rather than
        // silently deleted for this set's own convention: a lever landed.
        // The two reference-step walks over the field-site hop, which joined this set when that
        // hop gained its condition arm, and the clearest case in it for reading the counter as a
        // row count. Both are 188 scans dearer registered (795 against 607 on the walk, 3272
        // against 3084 on the scope rule) and both are decisively faster: 2 milliseconds against
        // 18, and 13 against 34. The rows are the unregistered side's to lose rather than the
        // registered side's to gain, which is what the new arm changed: inlined, its route join
        // short-circuits to nothing on this fixture and H2 charges it no scans, where the same
        // arm against a table is charged a visit per naming and the walk names the relation twice.
        // No index question, the target carrying one already.
        //
        // The scope rule's own two figures moved when its named-type arm stopped spelling the
        // navigation over the synthesis record and read intent_field_navigated_type instead. The
        // difference of 188 scans did not, and that is the reading to take from the re-measurement:
        // the gap is the walk's namings against the registered target, which that arm never
        // touched, so the pair survives on the mechanism it was always charged to rather than on
        // a coincidence of totals.
        "intent_field_reference_step_hop|intent_field_reference_step_target",
        "intent_field_reference_step_hop|intent_field_column_scope_live",
        // The same arm reaching the argument-site walk, where it is the argument scope table's
        // registration that pays. Three cells, and here the clock agrees with the counter rather
        // than contradicting it: the walk 187 scans and 29 milliseconds registered against 9 and
        // 16, the decode hop 1294 and 34 against 1122 and 26, its column child 1387 and 35
        // against 1215 and 25. Absolute differences of milliseconds on a twelve-unit fixture, and
        // the registration they are charged to is the one its own registry reason prices at
        // seventy milliseconds per naming across five namings, so the trade stands; what moved is
        // the plan the argument hop's fourth arm produces, not what any relation computes.
        //
        // Worth reading with the fixture in hand before treating any of these five as work. This
        // gate's store is captured with no classpath census, so no condition method's signature
        // resolves in it and the arm these five cells appeared with holds no rows at any unit
        // count. What they price is therefore a plan and not a population, which is the state the
        // input-surface note above records answering the other way: there the fixture could hold
        // the rows and grew to, and three cells went monotonic. Populating this arm needs the
        // census on this fixture's capture, which changes what every jvm-reading relation in the
        // domain measures, so it is a question about this gate rather than about this arm.
        "intent_argument_scope_table|intent_argument_reference_step_target",
        "intent_argument_scope_table|intent_node_id_decode_hop",
        "intent_argument_scope_table|intent_node_id_decode_hop_column_live");

    /**
     * The cells whose unregistered side did not answer inside its budget, and so were recorded rather
     * than compared. Empty, and kept empty deliberately rather than observed to be: every cell's
     * unregistered side terminates on this fixture, and {@link #BUDGET_FLOOR_MILLIS} is set clear of
     * the slowest of them so that which cells appear here is a fact about the code and not about how
     * loaded the machine was. A row appearing here is therefore a relation that stopped terminating,
     * which is worth a look rather than a number to raise.
     *
     * <p>{@link #aCellThatCannotAnswerIsRecordedRatherThanFailed} is what shows the arm working, over
     * a relation made non-terminating by construction rather than by being slow, because a cell that
     * is merely slow could stop being so on a faster machine.
     */
    // Empty, and it was not always. The membership fold read with the type binding unregistered
    // used to exhaust its budget: the fold reaches that binding through the field-grain scope
    // table, and with the binding a view the whole chain was re-evaluated per contributing
    // coordinate. It answers now, because intent_field_navigated_type stopped being a resolution
    // the chain re-derives and became a column read off a captured fact, which is one shared
    // subtree the engine no longer expands once per path to it. A cell leaving this set is the
    // direction the set is expected to move; one arriving is a reader that got dearer.
    private static final Set<String> KNOWN_EXHAUSTED = Set.of();

    @TempDir
    static Path tmp;

    private static Map<String, Set<String>> reached;
    private static List<Materializations.Registration> registrations;
    private static final Map<String, Long> registeredScans = new TreeMap<>();
    private static final Map<String, Long> registeredMillis = new TreeMap<>();
    private static final Set<String> observedNonMonotonic = new TreeSet<>();
    private static final Set<String> observedExhausted = new TreeSet<>();
    private static int observedCells;

    /**
     * Prices the whole matrix once for the class: the baseline store gives every reader's registered
     * shape, then one store per registration gives the unregistered shape of the readers that reach
     * it. One store per registration because the swap spends the store it is installed into, and the
     * measurement is per registration rather than per cell, so this is eight captures and not a
     * hundred and two.
     */
    @BeforeAll
    static void priceTheMatrix() {
        var ctx = TestConfiguration.testContext();
        var jooq = new JooqCatalog(ctx.jooqPackage(), ctx.codegenLoader());
        String sdl = scaledSdl(UNITS);

        try (var store = CapturedStore.ofCatalog(tmp.resolve("registered"), sdl, jooq)) {
            reached = MaterializeDependencies.registrationsReachedByView(store.dsl());
            registrations = Materializations.registrations(store.dsl());
            for (var cell : reached.entrySet()) {
                if (!cell.getValue().isEmpty()) {
                    var timed = scans(store, cell.getKey(), new ReadBudget.Unbounded());
                    registeredScans.put(cell.getKey(), timed.scans());
                    registeredMillis.put(cell.getKey(), timed.millis());
                }
            }
        }

        for (var registration : registrations) {
            List<String> readers = readersOf(registration);
            try (var store = CapturedStore.ofCatalog(
                    tmp.resolve("unregistered-" + registration.targetTableName()), sdl, jooq)) {
                UnregisteredRelation.install(store.dsl(), registration);
                for (String reader : readers) {
                    observedCells++;
                    String cell = registration.targetTableName() + "|" + reader;
                    var timed = scans(store, reader, budgetFor(reader));
                    if (timed.exhausted()) {
                        observedExhausted.add(cell);
                    } else if (timed.scans() < registeredScans.get(reader)) {
                        observedNonMonotonic.add(cell);
                    }
                }
            }
        }
    }

    // ===== The four assertions =====

    /**
     * The domain is the size this test says it is, on both axes and in cells. The reachability walk
     * is what makes the third figure much smaller than the product of the first two, and pinning it
     * is what stops a new view adding cells nobody priced.
     */
    @Test
    void theDomainIsTheSizeThisTestStates() {
        assertThat(reached).as("views in the fact schema").hasSize(READERS_IN_SCHEMA);
        assertThat(reached.values().stream().filter(regs -> !regs.isEmpty()).count())
            .as("views whose derivation reaches a registration").isEqualTo(READERS_WITH_CELLS);
        assertThat(observedCells)
            .as("cells priced: one per registration and reaching relation. A new view that adds"
                + " cells has to be priced rather than absorbed")
            .isEqualTo(CELLS);
    }

    /**
     * The claim itself. Every cell the pinned set does not name must be monotonic, and the pinned set
     * must be exactly what was observed, so a pair that stops being a regression fails this test
     * until its row goes.
     */
    @Test
    void aRegistrationDoesNotCostAnotherRelationMoreThanItSaves() {
        assertThat(observedNonMonotonic)
            .as("relations whose registered shape visits more rows than its unregistered one."
                + " Equality both ways: a new pair is a regression to answer, and a pair that has"
                + " stopped being one is a row to delete")
            .containsExactlyInAnyOrderElementsOf(KNOWN_NON_MONOTONIC);
    }

    /**
     * The cells recorded as unmeasurable are exactly the ones written down. Same ratchet as the pinned
     * pairs, and for the same reason: a cell that quietly stops being measured is a gate quietly
     * getting weaker.
     */
    @Test
    void theCellsThatCouldNotBeComparedAreExactlyTheOnesRecorded() {
        assertThat(observedExhausted)
            .as("cells whose unregistered side did not answer inside its own relative budget")
            .containsExactlyInAnyOrderElementsOf(KNOWN_EXHAUSTED);
    }

    /**
     * The pass-on-exhaustion arm, shown firing rather than asserted in prose. A relation the decode
     * family reads is made non-terminating by {@link RunawayRelation}, whose swap is structural rather
     * than slow, and the cell is then recorded as unmeasurable instead of failing the gate.
     *
     * <p>Passing is the only reading of a non-terminating unregistered side that is not a lie:
     * non-termination is the strongest possible form of "materializing did not make this worse". What
     * must not happen is passing <em>silently</em>, which is what the pinned set above is for.
     *
     * <p>And the cut is safe in the one direction that matters. The gate fails when the registered
     * shape costs more, so a regression is a cell whose <em>unregistered</em> side is the cheap one,
     * and a cheap side finishes. A budget on the unregistered side can therefore only ever discard
     * cells whose unregistered side was slow, and a slow unregistered side is evidence for the
     * registration rather than against it. The arm cannot swallow the defect the gate hunts, because
     * that defect arrives fast.
     */
    @Test
    void aCellThatCannotAnswerIsRecordedRatherThanFailed() {
        var ctx = TestConfiguration.testContext();
        var jooq = new JooqCatalog(ctx.jooqPackage(), ctx.codegenLoader());
        var registration = registrations.stream()
            .filter(r -> r.targetTableName().equals("intent_resolved_type_binding"))
            .findFirst().orElseThrow();

        try (var store = CapturedStore.ofCatalog(
                tmp.resolve("runaway"), scaledSdl(1), jooq)) {
            UnregisteredRelation.install(store.dsl(), registration);
            RunawayRelation.install(store.dsl(), "intent_bound_table");
            var timed = scans(store, "intent_resolved_type_binding",
                new ReadBudget.Bounded(RUNAWAY_BUDGET_MILLIS));
            assertThat(timed.exhausted())
                .as("a cell whose unregistered side cannot terminate is recorded, not compared")
                .isTrue();
        }
    }

    // ===== Helpers =====

    private static List<String> readersOf(Materializations.Registration registration) {
        return reached.entrySet().stream()
            .filter(cell -> cell.getValue().contains(registration.sourceViewName()))
            .map(Map.Entry::getKey)
            .toList();
    }

    /**
     * The budget for one cell's unregistered side: a multiple of what the registered side of that
     * same cell took, floored. Never an assertion; it decides only whether the cell is compared or
     * recorded, so slowness can make this gate more permissive and can never make it red.
     */
    private static ReadBudget budgetFor(String reader) {
        return new ReadBudget.Bounded(Math.max(BUDGET_FLOOR_MILLIS,
            BUDGET_MULTIPLE * registeredMillis.getOrDefault(reader, 0L)));
    }

    private record Timed(long scans, long millis, boolean exhausted) {}

    /** H2 annotates each plan node with the rows it visited. This is the whole instrument. */
    private static final Pattern SCAN_COUNT = Pattern.compile("scanCount: (\\d+)");

    /**
     * What one relation's whole evaluation visits, read through a reader minted after any swap. The
     * mint matters: {@link UnregisteredRelation} states why a session that already read these views
     * would not see the swap at all.
     */
    private static Timed scans(CapturedStore store, String relation, ReadBudget budget) {
        try (var reader = store.reader(budget)) {
            long started = System.nanoTime();
            StoreAnswer<String> answer = reader.read(dsl -> dsl
                .fetch("EXPLAIN ANALYZE SELECT * FROM " + relation)
                .get(0).get(0, String.class));
            long millis = (System.nanoTime() - started) / 1_000_000;
            if (!(answer instanceof StoreAnswer.Answered<String> plan)) {
                return new Timed(-1, millis, true);
            }
            long total = 0;
            Matcher counts = SCAN_COUNT.matcher(plan.value());
            while (counts.find()) {
                total += Long.parseLong(counts.group(1));
            }
            return new Timed(total, millis, false);
        }
    }

    /**
     * The fixture, which {@link MaterializedRegistryFixture} owns and the plan-pair gate shares. It
     * lives there rather than here because the property it carries, that every registered target
     * holds rows in proportion to schema size, is what both gates' figures rest on, and a second
     * copy would be the one that quietly stopped carrying it.
     */
    private static String scaledSdl(int units) {
        return MaterializedRegistryFixture.scaledSdl(units);
    }
}
