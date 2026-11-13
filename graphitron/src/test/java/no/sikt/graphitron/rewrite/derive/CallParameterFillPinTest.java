package no.sikt.graphitron.rewrite.derive;

import no.sikt.graphitron.model.test.FactGraphs;
import no.sikt.graphitron.model.test.CatalogInput;
import no.sikt.graphitron.rewrite.TestSchemaHelper;
import no.sikt.graphitron.rewrite.model.ConditionFilter;
import no.sikt.graphitron.rewrite.model.FkTargetConditionFilter;
import no.sikt.graphitron.rewrite.model.ParticipantFilterField;
import no.sikt.graphitron.rewrite.model.SqlGeneratingField;
import no.sikt.graphitron.rewrite.model.WhereFilter;
import no.sikt.graphitron.rewrite.model.MappingEntry;
import no.sikt.graphitron.rewrite.model.MethodBackedField;
import no.sikt.graphitron.rewrite.model.MethodRef;
import no.sikt.graphitron.rewrite.model.ParamSource;
import no.sikt.graphitron.rewrite.model.ServiceField;
import no.sikt.graphitron.rewrite.model.ServiceMethodCall;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

import static no.sikt.graphitron.common.configuration.TestConfiguration.testContext;
import static no.sikt.graphitron.model.Tables.CODE_METHOD_PARAMETER;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_FIELD;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_CALL_METHOD;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_CALL_PARAMETER;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_PRODUCER;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_PRODUCER_SERVICE;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * What fills each parameter of a service call, two ways: {@code graphitron_occurrence_call_parameter},
 * derived relationally from the producers, the argMapping landings, the context keys and the
 * classpath, and the classifier's own binding of each parameter, decided by reflection while it
 * builds the field. Over the whole sakila example schema, every {@code @service} field the classifier
 * builds is filled the same way, position by position, under every occurrence the store reaches it at.
 *
 * <p>The same holds for each {@code @condition}, one call per table its occurrence binds, against the
 * classifier's condition filters, sharing the one capture.
 *
 * <p>The comparison is at the fill's kind, with the context key and the session member it names: what
 * an {@code ARGUMENT} lands on is the landing's own pin, and how a value converts is building's.
 */
@PipelineTier
class CallParameterFillPinTest {

    @RegisterExtension
    static final FactGraphs.Reader FACTS = FactGraphs.reader();

    /**
     * Fields filled differently by the classifier and the store, each for a fact the store does not
     * yet state. Shrink-only: a gap closed upstream fails the last assertion until its line goes.
     */
    private static final Map<String, String> UPSTREAM_GAPS = Map.of();

    /**
     * Fields whose conditions the classifier and the store disagree on, each for a reason outside the
     * fill rules. Shrink-only: a gap closed fails the last assertion until its line goes.
     */
    private static final Map<String, String> CONDITION_GAPS = Map.of(
        "FilmInlineBundle.languageFiltered",
        "a field under a plain nesting type has no entry of its own in the classifier's fields, so"
            + " its condition is out of this test's sight; the store fills it",
        "Query.tilgangerAdmin",
        "a root @routine field binds no table: its rows are the routine's derived table, which no"
            + " binding states yet, so no condition call is keyed by one");

    @Test
    void theStoreFillsEachParameterAsTheClassifierBindsIt() throws Exception {
        String sdl = Files.readString(Path.of("..", "graphitron-sakila-example", "src", "main",
            "resources", "graphql", "schema.graphqls"));
        var walked = walked(sdl);
        var walkedConditions = walkedConditions(sdl);
        var read = FACTS.readInline("call-parameter-fill-pin", sdl, CatalogInput.of(testContext()),
            TestSchemaHelper.sakilaExampleClasspath(), graph -> {
                var dsl = graph.handle().dsl();
                var graphName = graph.handle().graphName();
                return new Stored(stored(dsl, graphName), whyNoCall(dsl, graphName),
                    storedConditions(dsl, graphName));
            });
        var stored = read.fills();
        var absent = read.absent();
        assertThat(walked).as("the classifier builds this many @service fields, so the pin is"
            + " not vacuous").hasSizeGreaterThanOrEqualTo(100);
        var disagreements = new TreeMap<String, String>();
        walked.forEach((coordinate, fills) -> {
            var at = stored.get(coordinate);
            if (at == null) {
                disagreements.put(coordinate, "classifier " + fills + ", store has no call: "
                    + absent.getOrDefault(coordinate, "no producer and no resolved method"));
            } else if (at.size() != 1 || !at.contains(fills)) {
                disagreements.put(coordinate, "classifier " + fills + ", store " + at);
            }
        });
        var unexplained = new TreeMap<>(disagreements);
        unexplained.keySet().removeAll(UPSTREAM_GAPS.keySet());
        assertThat(unexplained).as("fields the store fills differently from the classifier,"
            + " with both fillings").isEmpty();
        assertThat(disagreements.keySet()).as("the gaps still standing are exactly the listed"
            + " ones; one going away is a line to delete")
            .containsExactlyInAnyOrderElementsOf(UPSTREAM_GAPS.keySet());

        var storedConditions = read.conditions();
        assertThat(walkedConditions).as("the classifier builds this many fields with a @condition, so the pin"
            + " is not vacuous").hasSizeGreaterThanOrEqualTo(20);
        var conditionDisagreements = new TreeMap<String, String>();
        walkedConditions.forEach((coordinate, calls) -> {
            var at = storedConditions.getOrDefault(coordinate, List.of());
            if (at.isEmpty()) {
                conditionDisagreements.put(coordinate, "classifier " + calls + ", store has no condition call");
            } else if (at.stream().anyMatch(occurrence -> !occurrence.equals(calls))) {
                conditionDisagreements.put(coordinate, "classifier " + calls + ", store " + at);
            }
        });
        storedConditions.forEach((coordinate, at) -> {
            if (!walkedConditions.containsKey(coordinate)) {
                conditionDisagreements.put(coordinate, "store " + at + ", classifier has no condition");
            }
        });
        var conditionsUnexplained = new TreeMap<>(conditionDisagreements);
        conditionsUnexplained.keySet().removeAll(CONDITION_GAPS.keySet());
        assertThat(conditionsUnexplained).as("fields whose conditions the store fills differently from the"
            + " classifier, with both fillings").isEmpty();
        assertThat(conditionDisagreements.keySet()).as("the gaps still standing are exactly the listed"
            + " ones; one going away is a line to delete")
            .containsExactlyInAnyOrderElementsOf(CONDITION_GAPS.keySet());
    }

    /** What the store answers for the pin, read in one transaction over the captured graph. */
    private record Stored(Map<String, TreeSet<List<String>>> fills, Map<String, String> absent,
                          Map<String, List<TreeSet<String>>> conditions) {}

    /**
     * The store's fill per position, per declared field: each occurrence of the field contributes its
     * list, so a field filled differently under two parents shows two lists. A position no fill reaches
     * reads {@code UNFILLED}.
     */
    private static Map<String, TreeSet<List<String>>> stored(DSLContext dsl, String graphName) {
        var o = GRAPHITRON_OCCURRENCE;
        var s = GRAPHITRON_OCCURRENCE_CALL_METHOD;
        var p = CODE_METHOD_PARAMETER;
        var c = GRAPHITRON_OCCURRENCE_CALL_PARAMETER;
        var byOccurrence = new TreeMap<String, List<String>>();
        var elementOf = new TreeMap<String, String>();
        dsl.select(o.ELEMENT_COORDINATE, s.COORDINATE, p.POSITION, c.FILL, c.CONTEXT_KEY,
                c.SESSION_MEMBER)
            .from(s)
            .join(o).on(o.GRAPH_NAME.eq(s.GRAPH_NAME), o.COORDINATE.eq(s.COORDINATE))
            // Outer, so a method with no parameters is a call with an empty list rather than no call.
            .leftJoin(p).on(p.SOURCE_NAME.eq(s.METHOD_SOURCE_NAME), p.CLASS_NAME.eq(s.METHOD_CLASS_NAME),
                p.METHOD_NAME.eq(s.METHOD_NAME), p.DESCRIPTOR.eq(s.METHOD_DESCRIPTOR))
            .leftJoin(c).on(c.GRAPH_NAME.eq(s.GRAPH_NAME), c.COORDINATE.eq(s.COORDINATE), c.CALL.eq(s.CALL),
                c.POSITION.eq(p.POSITION))
            .where(s.GRAPH_NAME.eq(graphName), s.CALL_KIND.eq("SERVICE"))
            .orderBy(s.COORDINATE, p.POSITION)
            .forEach(r -> {
                elementOf.put(r.value2(), r.value1());
                var fills = byOccurrence.computeIfAbsent(r.value2(), k -> new ArrayList<>());
                if (r.value3() != null) {
                    fills.add(r.value4() == null ? "UNFILLED" : named(r.value4(), r.value5(), r.value6()));
                }
            });
        var out = new TreeMap<String, TreeSet<List<String>>>();
        byOccurrence.forEach((occurrence, fills) -> out
            .computeIfAbsent(elementOf.get(occurrence), k -> new TreeSet<>(CallParameterFillPinTest::byText))
            .add(fills));
        return out;
    }

    /**
     * Why the store has no call at a declared field, by the two relations a call joins: what kind
     * of producer each occurrence of the field is, and whether the field's method resolved.
     */
    private static Map<String, String> whyNoCall(DSLContext dsl, String graphName) {
        var o = GRAPHITRON_OCCURRENCE;
        var p = GRAPHITRON_OCCURRENCE_PRODUCER;
        var f = GRAPHITRON_FIELD;
        var s = GRAPHITRON_PRODUCER_SERVICE;
        var out = new TreeMap<String, String>();
        dsl.select(f.COORDINATE, p.KIND, s.METHOD_NAME)
            .from(f)
            .leftJoin(o).on(o.GRAPH_NAME.eq(f.GRAPH_NAME), o.ELEMENT_COORDINATE.eq(f.COORDINATE))
            .leftJoin(p).on(p.GRAPH_NAME.eq(o.GRAPH_NAME), p.COORDINATE.eq(o.COORDINATE))
            .leftJoin(s).on(s.GRAPH_NAME.eq(f.GRAPH_NAME), s.TYPE_NAME.eq(f.TYPE_NAME),
                s.FIELD_NAME.eq(f.FIELD_NAME))
            .where(f.GRAPH_NAME.eq(graphName))
            .forEach(r -> out.merge(r.value1(),
                "producer " + (r.value2() == null ? "none" : r.value2())
                    + ", method " + (r.value3() == null ? "unresolved" : r.value3()),
                (a, b) -> a.equals(b) ? a : a + "; " + b));
        return out;
    }

    /** The classifier's binding per position, for every field it builds with a service call. */
    private static Map<String, List<String>> walked(String sdl) {
        var schema = TestSchemaHelper.buildSchema(sdl);
        var out = new TreeMap<String, List<String>>();
        for (var f : schema.fields().values()) {
            List<String> fills = null;
            if (f instanceof ServiceField sf) {
                var args = switch (sf.serviceMethodCall()) {
                    case ServiceMethodCall.Static st -> st.methodArgs();
                    case ServiceMethodCall.Instance in -> in.methodArgs();
                };
                fills = args.stream().map(CallParameterFillPinTest::fill).toList();
            } else if (f instanceof MethodBackedField mb && mb.method() instanceof MethodRef.Service svc) {
                fills = svc.params().stream().map(CallParameterFillPinTest::fill).toList();
            }
            if (fills != null) {
                out.put(f.parentTypeName() + "." + f.name(), fills);
            }
        }
        return out;
    }

    private static String fill(MappingEntry entry) {
        return switch (entry) {
            case MappingEntry.FromArg ignored -> "ARGUMENT";
            case MappingEntry.FromContext c -> named("CONTEXT_KEY", c.contextKey(), null);
            case MappingEntry.FromDsl ignored -> "DSL_CONTEXT";
            case MappingEntry.FromSessionHandle ignored -> "SESSION";
            case MappingEntry.FromSessionHandleMember m -> named("SESSION_MEMBER", null, m.member());
        };
    }

    private static String fill(MethodRef.Param param) {
        return switch (param) {
            case MethodRef.Param.Sourced ignored -> "SOURCE";
            case MethodRef.Param.Typed t -> switch (t.source()) {
                case ParamSource.Arg ignored -> "ARGUMENT";
                // A context parameter's name is its key.
                case ParamSource.Context ignored -> named("CONTEXT_KEY", t.name(), null);
                case ParamSource.DslContext ignored -> "DSL_CONTEXT";
                case ParamSource.SessionHandle ignored -> "SESSION";
                case ParamSource.SessionHandleMember m -> named("SESSION_MEMBER", null, m.member());
                case ParamSource.Sources ignored -> "SOURCE";
                default -> t.source().getClass().getSimpleName();
            };
        };
    }

    /**
     * The store's conditions per declared field: for each occurrence of the field, the set of its
     * condition calls and its arguments' and input fields', each a method and its fills by position.
     * A position no fill reaches reads {@code UNFILLED}.
     */
    private static Map<String, List<TreeSet<String>>> storedConditions(DSLContext dsl, String graphName) {
        var o = GRAPHITRON_OCCURRENCE;
        var f = GRAPHITRON_OCCURRENCE.as("f");
        var a = GRAPHITRON_OCCURRENCE.as("a");
        var s = GRAPHITRON_OCCURRENCE_CALL_METHOD;
        var p = CODE_METHOD_PARAMETER;
        var c = GRAPHITRON_OCCURRENCE_CALL_PARAMETER;
        // The field occurrence a condition call belongs to: its own, or the field its argument is on.
        var field = org.jooq.impl.DSL.when(o.KIND.eq("FIELD"), o.COORDINATE).otherwise(a.PARENT_COORDINATE);
        var calls = new TreeMap<String, Map<String, List<String>>>();
        var elementOf = new TreeMap<String, String>();
        dsl.select(field, s.COORDINATE, s.CALL, s.METHOD_NAME, p.POSITION, c.FILL, c.CONTEXT_KEY,
                c.SESSION_MEMBER)
            .from(s)
            .join(o).on(o.GRAPH_NAME.eq(s.GRAPH_NAME), o.COORDINATE.eq(s.COORDINATE))
            .leftJoin(a).on(a.GRAPH_NAME.eq(o.GRAPH_NAME), a.COORDINATE.eq(o.ARGUMENT_COORDINATE))
            .leftJoin(p).on(p.SOURCE_NAME.eq(s.METHOD_SOURCE_NAME), p.CLASS_NAME.eq(s.METHOD_CLASS_NAME),
                p.METHOD_NAME.eq(s.METHOD_NAME), p.DESCRIPTOR.eq(s.METHOD_DESCRIPTOR))
            .leftJoin(c).on(c.GRAPH_NAME.eq(s.GRAPH_NAME), c.COORDINATE.eq(s.COORDINATE), c.CALL.eq(s.CALL),
                c.POSITION.eq(p.POSITION))
            .where(s.GRAPH_NAME.eq(graphName), s.CALL_KIND.eq("CONDITION"))
            .orderBy(s.COORDINATE, s.CALL, p.POSITION)
            .forEach(r -> {
                var fills = calls.computeIfAbsent(r.value1(), k -> new TreeMap<>())
                    .computeIfAbsent(r.value2() + " " + r.value3() + " " + r.value4(), k -> new ArrayList<>());
                if (r.value5() != null) {
                    fills.add(r.value6() == null ? "UNFILLED" : named(r.value6(), r.value7(), r.value8()));
                }
            });
        dsl.select(f.COORDINATE, f.ELEMENT_COORDINATE).from(f)
            .where(f.GRAPH_NAME.eq(graphName), f.KIND.eq("FIELD"))
            .forEach(r -> elementOf.put(r.value1(), r.value2()));
        var out = new TreeMap<String, List<TreeSet<String>>>();
        calls.forEach((occurrence, byCall) -> {
            var set = new TreeSet<String>();
            byCall.forEach((call, fills) -> set.add(call.substring(call.lastIndexOf(' ') + 1) + fills));
            out.computeIfAbsent(elementOf.get(occurrence), k -> new ArrayList<>()).add(set);
        });
        return out;
    }

    /** The classifier's conditions per field, each a method and its fills by position. */
    private static Map<String, TreeSet<String>> walkedConditions(String sdl) {
        var schema = TestSchemaHelper.buildSchema(sdl);
        var out = new TreeMap<String, TreeSet<String>>();
        for (var f : schema.fields().values()) {
            var filters = new ArrayList<WhereFilter>();
            if (f instanceof SqlGeneratingField sg) {
                filters.addAll(sg.filters());
            }
            if (f instanceof ParticipantFilterField pf) {
                pf.participantFilters().forEach(p -> filters.addAll(p.filters()));
            }
            var set = new TreeSet<String>();
            for (var filter : filters) {
                ConditionFilter condition = switch (filter) {
                    case ConditionFilter cf -> cf;
                    case FkTargetConditionFilter fk -> fk.delegate();
                    default -> null;
                };
                if (condition != null) {
                    set.add(condition.methodName() + condition.params().stream()
                        .map(CallParameterFillPinTest::conditionFill).toList());
                }
            }
            if (!set.isEmpty()) {
                out.put(f.parentTypeName() + "." + f.name(), set);
            }
        }
        return out;
    }

    private static String conditionFill(MethodRef.Param param) {
        return switch (param) {
            case MethodRef.Param.Sourced ignored -> "SOURCE";
            case MethodRef.Param.Typed t -> switch (t.source()) {
                case ParamSource.Table ignored -> "TABLE";
                case ParamSource.Arg ignored -> "ARGUMENT";
                // A context parameter's name is its key.
                case ParamSource.Context ignored -> named("CONTEXT_KEY", t.name(), null);
                case ParamSource.SessionHandle ignored -> "SESSION";
                case ParamSource.SessionHandleMember m -> named("SESSION_MEMBER", null, m.member());
                default -> t.source().getClass().getSimpleName();
            };
        };
    }

    private static String named(String fill, String contextKey, String sessionMember) {
        return contextKey != null ? fill + " " + contextKey
            : sessionMember != null ? fill + " " + sessionMember : fill;
    }

    private static int byText(List<String> a, List<String> b) {
        return a.toString().compareTo(b.toString());
    }

}
