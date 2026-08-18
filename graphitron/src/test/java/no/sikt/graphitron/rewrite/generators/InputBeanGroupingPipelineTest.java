package no.sikt.graphitron.rewrite.generators;

import no.sikt.graphitron.javapoet.MethodSpec;
import no.sikt.graphitron.javapoet.TypeSpec;
import no.sikt.graphitron.rewrite.TestSchemaHelper;
import no.sikt.graphitron.rewrite.model.CallSiteExtraction;
import no.sikt.graphitron.rewrite.model.GraphitronField;
import no.sikt.graphitron.rewrite.model.GraphitronField.UnclassifiedField;
import no.sikt.graphitron.rewrite.model.MappingEntry;
import no.sikt.graphitron.rewrite.model.ServiceField;
import no.sikt.graphitron.rewrite.model.ValueShape;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.Test;

import java.util.List;

import static no.sikt.graphitron.common.configuration.TestConfiguration.DEFAULT_OUTPUT_PACKAGE;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pipeline tier: an SDL input type may cluster fields under nested input objects for the client's
 * benefit while the backing consumer bean stays flat. A nested input field whose binding key names
 * no member of the bean is a <em>grouping</em> input: its own fields are hoisted onto the enclosing
 * bean and bind by the normal rules, each carrying the access path it was hoisted through.
 *
 * <p>The member-axis twin of {@link JooqRecordServiceParamPipelineTest}, which covers the same
 * flatten on the column axis (a nested group flattening onto one jOOQ record's columns). Both
 * halves of this item live here: the shapes that newly bind, and the shapes that newly reject. The
 * reject half matters on the JavaBean arm in particular, where every one of these built silently
 * before, dropping the group's data on the floor; the record arm's bijection already failed them.
 *
 * <p>No generated-body string assertions except where the emitted descent is the thing under test:
 * the access paths on the resolved {@code ValueShape.FieldBinding}s are the structural pin.
 */
@PipelineTier
class InputBeanGroupingPipelineTest {

    private static final String SERVICE = "no.sikt.graphitron.rewrite.TestServiceStub";

    /** {@code length} / {@code rentalDays} sit under a grouping input; {@code title} stays top-level. */
    private static final String FLATTEN_SDL = """
        input GroupedInput {
            title: String
            duration: DurationInput
        }
        input DurationInput { length: Int, rentalDays: Int }
        type Query {
            runGrouped(input: GroupedInput): String
                @service(service: {className: "%s", method: "runWithGroupedBean"})
        }
        """.formatted(SERVICE);

    /** One grouping input nested inside another: {@code length} is reached through two hops. */
    private static final String DEPTH_TWO_SDL = """
        input DeepGroupedInput {
            title: String
            spec: SpecInput
        }
        input SpecInput { duration: DurationInput }
        input DurationInput { length: Int, rentalDays: Int }
        type Query {
            runGrouped(input: DeepGroupedInput): String
                @service(service: {className: "%s", method: "runWithGroupedBean"})
        }
        """.formatted(SERVICE);

    /** {@code duration} names no component (flattens); {@code period} names one (stays nested). */
    private static final String MATCHING_MEMBER_SDL = """
        input MixedInput {
            title: String
            duration: DurationInput
            period: TestInputNested
        }
        input DurationInput { length: Int }
        input TestInputNested { key: String, value: String }
        type Query {
            runMixed(input: MixedInput): String
                @service(service: {className: "%s", method: "runWithGroupedBeanAndNested"})
        }
        """.formatted(SERVICE);

    private static final String JAVA_BEAN_SDL = """
        input GroupedJavaBeanInput {
            title: String
            duration: DurationInput
        }
        input DurationInput { length: Int }
        type Query {
            runGroupedJavaBean(input: GroupedJavaBeanInput): String
                @service(service: {className: "%s", method: "runWithGroupedJavaBean"})
        }
        """.formatted(SERVICE);

    /** The bean's jOOQ-record member is declared inside a grouping input rather than at the top level. */
    private static final String HOISTED_NODE_ID_SDL = """
        type Film implements Node @table(name: "film") @node { id: ID! title: String }
        input AssignGroupedInput {
            target: TargetInput
        }
        input TargetInput { film: ID! @nodeId(typeName: "Film") }
        type Query {
            assignFilm(in: AssignGroupedInput!): String
                @service(service: {className: "%s", method: "assignFilm"})
        }
        """.formatted(SERVICE);

    // ===== Shapes that flatten =====

    @Test
    void groupedLeaves_carryTwoElementAccessPaths_topLevelLeafKeepsOne() {
        // The whole item in one assertion: title is declared on the input type and keeps a
        // one-element path, while length and rentalDays are declared one level down and carry the
        // group they were hoisted through. Both bind to components of the same flat record, which
        // is the shape that hard-failed the bijection before.
        var bean = recordBean(FLATTEN_SDL, "runGrouped");
        assertThat(bean.javaClass().simpleName()).isEqualTo("TestInputBeanGrouped");
        assertThat(bean.fields())
            .as("the flat record's three components bind, in component order, each with its own path")
            .extracting(fb -> String.join(".", fb.accessPath()) + "->" + fb.javaFieldName())
            .containsExactly("title->title", "duration.length->length", "duration.rentalDays->rentalDays");
    }

    @Test
    void groupedLeaf_mapKeyIsTheLeafFieldName_notTheDottedPath() {
        // mapKey() is what the helper reads from the descended Map, so it must stay the leaf's own
        // SDL name; the group segments are the descent, not part of the key.
        var bean = recordBean(FLATTEN_SDL, "runGrouped");
        assertThat(bean.fields()).extracting(ValueShape.FieldBinding::mapKey)
            .containsExactly("title", "length", "rentalDays");
    }

    @Test
    void nestedGrouping_descendsRecursively_toThreeElementPaths() {
        // A hoisted group descends again: spec names no component, and neither does the duration
        // inside it, so both hops land on the path and the leaves still bind to the same flat record.
        var bean = recordBean(DEPTH_TWO_SDL, "runGrouped");
        assertThat(bean.fields())
            .extracting(fb -> String.join(".", fb.accessPath()))
            .containsExactly("title", "spec.duration.length", "spec.duration.rentalDays");
    }

    @Test
    void nestedFieldMatchingAMember_staysANestedBean_whileItsSiblingFlattens() {
        // The rule's hinge, both outcomes in one input type. Adding a member is how an author opts a
        // group back out of flattening, so `period` (a component) keeps its one-element path and a
        // nested RecordInput shape, while `duration` (no component) flattens its leaf onto the bean.
        var bean = recordBean(MATCHING_MEMBER_SDL, "runMixed");
        assertThat(bean.fields())
            .extracting(fb -> String.join(".", fb.accessPath()))
            .containsExactly("title", "duration.length", "period");
        var periodShape = bean.fields().stream()
            .filter(fb -> fb.javaFieldName().equals("period")).findFirst().orElseThrow().shape();
        assertThat(periodShape)
            .as("the matched nested field still recurses into a nested bean rather than flattening")
            .isInstanceOf(ValueShape.RecordInput.class);
        assertThat(((ValueShape.RecordInput) periodShape).javaClass().simpleName())
            .isEqualTo("TestInputNested");
    }

    @Test
    void javaBeanArm_hoistsToo_ratherThanSkippingTheGroup() {
        // The arm where the defect was silent: an unmatched nested field used to be skipped before
        // it was ever inspected, so `length` stayed null and the build said nothing. It now binds.
        var field = TestSchemaHelper.buildSchema(JAVA_BEAN_SDL).field("Query", "runGroupedJavaBean");
        var bean = (ValueShape.JavaBeanInput) beanArgShape(field, "runGroupedJavaBean");
        assertThat(bean.javaClass().simpleName()).isEqualTo("TestInputJavaBeanGrouped");
        assertThat(bean.fields())
            .extracting(fb -> String.join(".", fb.accessPath()) + "->" + fb.javaFieldName())
            .containsExactly("title->title", "duration.length->length");
    }

    @Test
    void hoistedNodeIdMember_keepsItsDecodeLeaf_underTheGroupPath() {
        // Flattening moves where a value is read from, not what is done to it: a jOOQ-record member
        // hoisted out of a group still resolves to a NodeIdDecodeRecord leaf, not a Direct cast of
        // the wire String (which is the ClassCastException the decode path exists to prevent).
        var bean = recordBean(HOISTED_NODE_ID_SDL, "assignFilm");
        assertThat(bean.javaClass().simpleName()).isEqualTo("TestNodeIdRecordBean");
        var member = bean.fields().get(0);
        assertThat(member.accessPath()).containsExactly("target", "film");
        assertThat(((ValueShape.Scalar) member.shape()).leafTransform())
            .isInstanceOf(CallSiteExtraction.NodeIdDecodeRecord.class);
    }

    // ===== Emitted descent =====

    @Test
    void singularHelper_opensOneMapLocalPerGroup_andReadsEveryHoistedLeafFromIt() {
        // The emitted shape is load-bearing, so it is pinned directly: one descent local per group
        // (not one per sibling leaf), binding the empty map when the group is absent so that every
        // per-field expression stays the expression an unflattened field emits.
        var body = helperBody(FLATTEN_SDL, "createTestInputBeanGrouped");
        assertThat(body)
            .as("one Map local for the one group, defaulting to an empty map rather than null")
            .contains("durationMap = raw.get(\"duration\") instanceof")
            .contains("? durationGroup : java.util.Map.of()");
        assertThat(body)
            .as("both hoisted leaves read from that local; the top-level leaf still reads from raw")
            .contains("durationMap.get(\"length\")")
            .contains("durationMap.get(\"rentalDays\")")
            .contains("raw.get(\"title\")");
        assertThat(body.split("instanceof", -1).length - 1)
            .as("one descent for the group, not one per leaf under it").isEqualTo(1);
    }

    @Test
    void deepHelper_descendsParentBeforeChild() {
        // Declaration order matters: the inner local is initialised from the outer one, so a child
        // prefix emitted before its parent would not compile in the consumer's sources.
        var body = helperBody(DEPTH_TWO_SDL, "createTestInputBeanGrouped");
        assertThat(body.indexOf("specMap = raw.get(\"spec\")"))
            .as("the outer group's local is declared first").isNotNegative()
            .isLessThan(body.indexOf("specDurationMap = specMap.get(\"duration\")"));
        assertThat(body).contains("specDurationMap.get(\"length\")");
    }

    @Test
    void singularNestedBean_isNarrowedByPattern_notByAnUncheckedCast() {
        // Generated sources land in the consumer's build, where an unchecked-cast warning is a hard
        // failure under -Werror and no @SuppressWarnings can be attached to a cast inside an
        // expression. The nested bean is narrowed with an instanceof pattern instead, which also
        // subsumes the null guard: a null or non-Map value simply does not match.
        var body = helperBody(MATCHING_MEMBER_SDL, "createTestInputBeanGroupedWithNested");
        assertThat(body)
            .contains("raw.get(\"period\") instanceof java.util.Map<?, ?> periodRaw")
            .contains("createTestInputNested(periodRaw)")
            .doesNotContain("(java.util.Map<java.lang.String, java.lang.Object>) raw.get(\"period\")");
    }

    @Test
    void unflattenedBean_emitsNoDescent_andReadsEverythingFromRaw() {
        // The no-op pin: a bean with no flattened field must emit exactly what it emitted before
        // grouping existed, so the access path costs nothing at depth 1.
        var body = helperBody(MATCHING_MEMBER_SDL, "createTestInputNested");
        assertThat(body)
            .contains("raw.get(\"key\")")
            .contains("raw.get(\"value\")")
            .doesNotContain("Map.of()");
    }

    // ===== Shapes that reject =====

    @Test
    void groupCarryingFieldDirectiveNamingNoMember_rejects() {
        // @field(name:) is an authored claim that this field binds to a named member. Flattening past
        // a claim that resolves to nothing would turn a typo into silently-different behaviour, which
        // is the failure mode this item removes rather than relocates.
        var sdl = """
            input ClaimInput {
                title: String
                duration: DurationInput @field(name: "noSuchMember")
            }
            input DurationInput { length: Int, rentalDays: Int }
            type Query {
                runGrouped(input: ClaimInput): String
                    @service(service: {className: "%s", method: "runWithGroupedBean"})
            }
            """.formatted(SERVICE);
        assertThat(rejectionFor(sdl, "runGrouped"))
            .contains("@field(name:)")
            .contains("noSuchMember")
            .contains("has no such member");
    }

    @Test
    void groupCarryingNodeIdNamingNoMember_rejects() {
        // The @nodeId half of the same gate: also a binding claim, also refused rather than flattened.
        var sdl = """
            type Film implements Node @table(name: "film") @node { id: ID! title: String }
            input NodeClaimInput {
                title: String
                duration: DurationInput @nodeId(typeName: "Film")
            }
            input DurationInput { length: Int, rentalDays: Int }
            type Query {
                runGrouped(input: NodeClaimInput): String
                    @service(service: {className: "%s", method: "runWithGroupedBean"})
            }
            """.formatted(SERVICE);
        assertThat(rejectionFor(sdl, "runGrouped"))
            .contains("@nodeId")
            .contains("duration")
            .contains("has no such member");
    }

    @Test
    void listShapedGroup_rejects() {
        // A list of groups has no flat member to land on. Deliberately not list-lifted into a
        // List<Integer> member: that reinterprets one object per element as one array per field, so
        // the SDL would stop describing what the Java side receives.
        var sdl = """
            input ListGroupInput {
                title: String
                duration: [DurationInput!]
            }
            input DurationInput { length: Int, rentalDays: Int }
            type Query {
                runGrouped(input: ListGroupInput): String
                    @service(service: {className: "%s", method: "runWithGroupedBean"})
            }
            """.formatted(SERVICE);
        assertThat(rejectionFor(sdl, "runGrouped"))
            .contains("list-shaped")
            .contains("no flat member to land on")
            .contains("Make the field singular");
    }

    @Test
    void cyclicGroup_rejects() {
        // Load-bearing, not cosmetic: buildInputBean's recursion guard is on Java classes, and a
        // flattened group contributes none, so without an SDL-type-name guard this recurses until
        // the stack dies rather than reporting anything.
        var sdl = """
            input CyclicGroupInput {
                title: String
                duration: CyclicGroupInput
            }
            type Query {
                runGrouped(input: CyclicGroupInput): String
                    @service(service: {className: "%s", method: "runWithGroupedBean"})
            }
            """.formatted(SERVICE);
        assertThat(rejectionFor(sdl, "runGrouped"))
            .contains("already expanding")
            .contains("CyclicGroupInput");
    }

    @Test
    void hoistedLeafCollidingWithTopLevelField_rejects() {
        // Hoisting makes a leaf a peer of the type's own fields, so it collides with one by exactly
        // the rule that already governs two top-level fields. On the JavaBean arm this shape builds
        // today with the group silently dropped and the top-level field winning.
        var sdl = """
            input CollidingInput {
                title: String
                length: Int
                duration: DurationInput
            }
            input DurationInput { length: Int, rentalDays: Int }
            type Query {
                runGrouped(input: CollidingInput): String
                    @service(service: {className: "%s", method: "runWithGroupedBean"})
            }
            """.formatted(SERVICE);
        assertThat(rejectionFor(sdl, "runGrouped"))
            .contains("'length' and 'duration.length'")
            .contains("both bind to Java member 'length'");
    }

    @Test
    void twoGroupsHoistingTheSameKey_rejects() {
        // The hoisted-versus-hoisted pairing is the same one rejection, not a variant of it: the
        // access path is carried for the message, never for the identity that decides a collision.
        var sdl = """
            input TwoGroupInput {
                title: String
                duration: DurationInput
                alternate: AlternateInput
            }
            input DurationInput { length: Int, rentalDays: Int }
            input AlternateInput { length: Int }
            type Query {
                runGrouped(input: TwoGroupInput): String
                    @service(service: {className: "%s", method: "runWithGroupedBean"})
            }
            """.formatted(SERVICE);
        assertThat(rejectionFor(sdl, "runGrouped"))
            .contains("'duration.length' and 'alternate.length'")
            .contains("both bind to Java member 'length'");
    }

    @Test
    void hoistedLeafNamingNoComponent_stillFailsDirectionB() {
        // Hoisting does not weaken the record arm's bijection: a hoisted leaf that names no component
        // would have its value dropped on the way to the canonical constructor, so it fails direction
        // B exactly as a top-level stray does, and the message names the path the author wrote.
        var sdl = """
            input StrayInput {
                title: String
                duration: DurationInput
            }
            input DurationInput { length: Int, rentalDays: Int, stray: String }
            type Query {
                runGrouped(input: StrayInput): String
                    @service(service: {className: "%s", method: "runWithGroupedBean"})
            }
            """.formatted(SERVICE);
        assertThat(rejectionFor(sdl, "runGrouped"))
            .contains("duration.stray")
            .contains("names no component of record");
    }

    // ===== JavaBean-arm behaviour change: each reject shape built silently before =====

    @Test
    void javaBeanArm_rejectsTheSameShapes_thoughEachOfThemBuiltBefore() {
        // The second half of the fix, and the one a consumer can notice: on the JavaBean arm every
        // shape below used to build green while discarding the group's data. Asserted against the
        // JavaBean fixture specifically, because the record arm reaches none of these checks without
        // failing its bijection first, so the record-arm cases above prove nothing about this arm.
        String collision = """
            input JbCollisionInput {
                title: String
                length: Int
                duration: DurationInput
            }
            input DurationInput { length: Int }
            type Query {
                runGroupedJavaBean(input: JbCollisionInput): String
                    @service(service: {className: "%s", method: "runWithGroupedJavaBean"})
            }
            """.formatted(SERVICE);
        assertThat(rejectionFor(collision, "runGroupedJavaBean"))
            .contains("both bind to Java member 'length'");

        String cyclic = """
            input JbCyclicInput {
                title: String
                duration: JbCyclicInput
            }
            type Query {
                runGroupedJavaBean(input: JbCyclicInput): String
                    @service(service: {className: "%s", method: "runWithGroupedJavaBean"})
            }
            """.formatted(SERVICE);
        assertThat(rejectionFor(cyclic, "runGroupedJavaBean")).contains("already expanding");

        String listShaped = """
            input JbListInput {
                title: String
                duration: [DurationInput!]
            }
            input DurationInput { length: Int }
            type Query {
                runGroupedJavaBean(input: JbListInput): String
                    @service(service: {className: "%s", method: "runWithGroupedJavaBean"})
            }
            """.formatted(SERVICE);
        assertThat(rejectionFor(listShaped, "runGroupedJavaBean")).contains("list-shaped");

        String claim = """
            input JbClaimInput {
                title: String
                duration: DurationInput @field(name: "noSuchProperty")
            }
            input DurationInput { length: Int }
            type Query {
                runGroupedJavaBean(input: JbClaimInput): String
                    @service(service: {className: "%s", method: "runWithGroupedJavaBean"})
            }
            """.formatted(SERVICE);
        assertThat(rejectionFor(claim, "runGroupedJavaBean")).contains("noSuchProperty");
    }

    @Test
    void javaBeanArm_stillSkipsAnUnmatchedScalarField() {
        // The narrowing is exactly as wide as flattening needs it to be. An unmatched *scalar* field
        // is still skipped in silence, so the arm stays partial by design everywhere the grouping
        // rule does not have to look; diagnosing that residue is a separate job.
        var sdl = """
            input StraySca1arInput {
                title: String
                duration: DurationInput
                unbound: String
            }
            input DurationInput { length: Int }
            type Query {
                runGroupedJavaBean(input: StraySca1arInput): String
                    @service(service: {className: "%s", method: "runWithGroupedJavaBean"})
            }
            """.formatted(SERVICE);
        var field = TestSchemaHelper.buildSchema(sdl).field("Query", "runGroupedJavaBean");
        assertThat(field).isNotInstanceOf(UnclassifiedField.class);
        var bean = (ValueShape.JavaBeanInput) beanArgShape(field, "runGroupedJavaBean");
        assertThat(bean.fields())
            .extracting(fb -> String.join(".", fb.accessPath()))
            .containsExactly("title", "duration.length");
    }

    // ===== Helpers =====

    /** The {@code ValueShape.RecordInput} bean argument of a {@code @service} Query field. */
    private static ValueShape.RecordInput recordBean(String sdl, String queryField) {
        var field = TestSchemaHelper.buildSchema(sdl).field("Query", queryField);
        return (ValueShape.RecordInput) beanArgShape(field, queryField);
    }

    /** The composite (record or JavaBean) {@code ValueShape} of the field's single bean argument. */
    private static ValueShape beanArgShape(GraphitronField field, String queryField) {
        assertThat(field)
            .as("the field must classify; a rejection means the grouping shape was refused")
            .isNotInstanceOf(UnclassifiedField.class);
        return ((ServiceField) field).serviceMethodCall().methodArgs().stream()
            .filter(e -> e instanceof MappingEntry.FromArg fa
                && (fa.shape() instanceof ValueShape.RecordInput
                    || fa.shape() instanceof ValueShape.JavaBeanInput))
            .map(e -> ((MappingEntry.FromArg) e).shape())
            .findFirst()
            .orElseThrow(() -> new AssertionError("no bean arg on " + queryField));
    }

    /** The rejection reason of a field the classifier refused, failing loudly when it did not. */
    private static String rejectionFor(String sdl, String queryField) {
        var field = TestSchemaHelper.buildSchema(sdl).field("Query", queryField);
        assertThat(field)
            .as("this shape must be refused at build time, not bound partially or dropped")
            .isInstanceOf(UnclassifiedField.class);
        return ((UnclassifiedField) field).reason();
    }

    /** The rendered body of one {@code create<Bean>} helper on the generated {@code QueryFetchers}. */
    private static String helperBody(String sdl, String helperName) {
        List<TypeSpec> specs = TypeFetcherGenerator.generate(
            TestSchemaHelper.buildSchema(sdl), DEFAULT_OUTPUT_PACKAGE);
        return specs.stream()
            .filter(t -> t.name().equals("QueryFetchers"))
            .flatMap(t -> t.methodSpecs().stream())
            .filter(m -> m.name().equals(helperName))
            .map(MethodSpec::code)
            .map(Object::toString)
            .findFirst()
            .orElseThrow(() -> new AssertionError("helper not found: " + helperName));
    }
}
