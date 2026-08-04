package no.sikt.graphitron.rewrite.classifieddsl;

import graphql.language.Argument;
import graphql.language.ArrayValue;
import graphql.language.Directive;
import graphql.language.EnumTypeDefinition;
import graphql.language.EnumValue;
import graphql.language.EnumValueDefinition;
import graphql.language.FieldDefinition;
import graphql.language.InterfaceTypeDefinition;
import graphql.language.ObjectTypeDefinition;
import graphql.language.TypeDefinition;
import graphql.language.Value;
import graphql.schema.idl.SchemaParser;
import graphql.schema.idl.TypeDefinitionRegistry;
import no.sikt.graphitron.common.configuration.TestConfiguration;
import no.sikt.graphitron.rewrite.GraphitronSchema;
import no.sikt.graphitron.rewrite.GraphitronSchemaBuilder;
import no.sikt.graphitron.rewrite.TestSchemaHelper;
import no.sikt.graphitron.rewrite.generators.GeneratorCoverageTest;
import no.sikt.graphitron.rewrite.model.GraphitronField;
import no.sikt.graphitron.rewrite.model.GraphitronType;
import no.sikt.graphitron.rewrite.model.Operation;
import no.sikt.graphitron.rewrite.model.OperationMember;
import no.sikt.graphitron.rewrite.model.OutputField;
import no.sikt.graphitron.rewrite.model.Source;
import no.sikt.graphitron.rewrite.model.SourceShape;
import no.sikt.graphitron.rewrite.model.Target;
import no.sikt.graphitron.rewrite.model.TargetShape;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Drives the spec-by-example corpus: parses an annotated fixture schema, runs <em>today's</em>
 * classifier, and for each {@code @classified} / {@code @classifiedType} coordinate compares the
 * directive's declared verdict against what the classifier produces (read off the field model's
 * {@code source()} / {@code operation()} / {@code target()} accessors for fields, off the sealed leaf's
 * simple name for types).
 *
 * <p>The fixture's test-only directives ({@link ClassifiedDsl#PRELUDE}) are prepended before the
 * classifier runs; the classifier ignores them, and this harness reads them straight off the parsed
 * AST. The SDL is the example, the directive is the assertion.
 */
public final class ClassifiedHarness {

    private ClassifiedHarness() {}

    /**
     * One {@code @classified} output-field coordinate: its declared tuple vs. the adapter's, plus the
     * sealed {@code OutputField} leaf the classifier landed on (the corpus's contribution to leaf
     * coverage, see {@link ClassifiedCorpus#coveredLeaves()}).
     */
    public record FieldCase(String parentType, String fieldName, DimensionTuple expected,
                            DimensionTuple actual, Class<? extends GraphitronField> leaf) {}

    /**
     * One {@code @classifiedType} coordinate: its declared verdict vs. the classified leaf's simple
     * name, plus the {@code GraphitronType} leaf itself ({@code null} if the type did not classify).
     */
    public record TypeCase(String typeName, String expectedVerdict, String actualVerdict,
                           Class<? extends GraphitronType> leaf) {}

    /**
     * One declared or produced synthesis mint: a type name paired with the synthesised
     * {@code GraphitronType} arm it is minted as (the {@code SynthesisedType} enum constant,
     * which is the arm's simple name).
     */
    public record Mint(String name, String arm) {}

    /**
     * One {@code @synthesises} coordinate: the mints the carrier declares vs. the mints the
     * connection-synthesis relation produced for the coordinate. A produced mint appears only
     * when the relation's row names it AND the classified registry's actual entry is an instance
     * of the arm the row declares, so coverage can only ever be claimed by declaration-and-
     * production agreement, never by the producer's output alone.
     */
    public record SynthesisCase(String parentType, String fieldName,
                                Set<Mint> declared, Set<Mint> produced) {}

    /**
     * One {@code @commits} declaration as authored: the coordinate and its declared launcher
     * arm tokens. The produced side joins in through {@link #commitCases}, which reads the
     * example's launcher production outcome.
     */
    public record CommitDeclaration(String parentType, String fieldName,
                                    String source, String result) {}

    /**
     * One {@code @commits} coordinate joined against the produced launcher relation: the
     * declared {@code LaunchSource} / {@code ResultShape} arm tokens vs. the produced row's arm
     * simple names at the same coordinate ({@code null} produced side when the relation has no
     * row there, or when the example's production failed).
     */
    public record CommitCase(String parentType, String fieldName,
                             String declaredSource, String declaredResult,
                             String producedSource, String producedResult) {}

    /** The full outcome of classifying one fixture: every annotated coordinate, plus the schema. */
    public record Result(List<FieldCase> fields, List<TypeCase> types,
                         List<SynthesisCase> synthesises, List<CommitDeclaration> commits,
                         GraphitronSchema schema) {}

    /**
     * Classifies {@code fixtureSdl} (the {@link ClassifiedDsl#PRELUDE} prepended automatically) and
     * resolves every {@code @classified} / {@code @classifiedType} coordinate it carries.
     */
    public static Result classify(String fixtureSdl) {
        String full = ClassifiedDsl.PRELUDE + "\n" + fixtureSdl;
        TypeDefinitionRegistry registry = TestSchemaHelper.parseRegistryWithPrelude(full);
        GraphitronSchema schema = GraphitronSchemaBuilder.build(registry, TestConfiguration.testContext());

        var fields = new ArrayList<FieldCase>();
        var types = new ArrayList<TypeCase>();
        var synthesises = new ArrayList<SynthesisCase>();
        var commits = new ArrayList<CommitDeclaration>();

        for (TypeDefinition<?> def : registry.types().values()) {
            List<FieldDefinition> fieldDefs = switch (def) {
                case ObjectTypeDefinition o -> o.getFieldDefinitions();
                case InterfaceTypeDefinition i -> i.getFieldDefinitions();
                default -> List.of();
            };
            for (var fd : fieldDefs) {
                Directive d = directive(fd.getDirectives(), ClassifiedDsl.CLASSIFIED);
                if (d != null) {
                    fields.add(fieldCase(schema, def.getName(), fd.getName(), d));
                }
                Directive ds = directive(fd.getDirectives(), ClassifiedDsl.SYNTHESISES);
                if (ds != null) {
                    synthesises.add(synthesisCase(schema, def.getName(), fd.getName(), ds));
                }
                Directive dc = directive(fd.getDirectives(), ClassifiedDsl.COMMITS);
                if (dc != null) {
                    commits.add(new CommitDeclaration(def.getName(), fd.getName(),
                        enumArg(dc, "source"), enumArg(dc, "result")));
                }
            }
            Directive dt = directive(def.getDirectives(), ClassifiedDsl.CLASSIFIED_TYPE);
            if (dt != null) {
                types.add(typeCase(schema, def.getName(), dt));
            }
        }
        // Scalars live in their own registry map (graphql-java keeps them out of types()), so a
        // @classifiedType on a `scalar` definition is picked up here rather than in the loop above.
        for (var scalarDef : registry.scalars().values()) {
            Directive dt = directive(scalarDef.getDirectives(), ClassifiedDsl.CLASSIFIED_TYPE);
            if (dt != null) {
                types.add(typeCase(schema, scalarDef.getName(), dt));
            }
        }
        return new Result(fields, types, synthesises, commits, schema);
    }

    // ----- launcher production: the corpus's canonical run, and the @commits join -----

    /**
     * The outcome of producing the launcher relation for one corpus example under the canonical
     * run configuration: the relation, or the loud production failure's reason. Typed so the
     * per-example sweep never throws through its loop and a roster test can bind the failing
     * id set by equality.
     */
    public sealed interface LauncherProduction {

        /** Production succeeded; the relation carries the example's rows. */
        record Produced(no.sikt.graphitron.plan.LauncherRelation relation) implements LauncherProduction {}

        /** Production failed loudly on a recorded validator-mirror-gap invariant. */
        record Failed(String reason) implements LauncherProduction {}
    }

    private static Map<String, LauncherProduction> launcherProductions;

    /**
     * The launcher relation of every corpus example, produced once per JVM under the one
     * canonical run configuration the corpus fixes: the
     * {@code TestConfiguration.testContext()} schema build and
     * {@code TestConfiguration.DEFAULT_OUTPUT_PACKAGE}, with no federation link, no oneOf
     * strictness, no session state and no tenant configuration. Run-grain facts (the relation's
     * {@code carrierDsl}, the federation and oneOf gates) are deliberately outside the
     * coordinate directive's reach; a fixture needing a different configuration is a
     * pipeline-tier test, not a corpus example. Only the production guards'
     * {@link IllegalStateException}s are caught (never assertion errors), so a classifier or
     * schema-assembly failure still fails the sweep loudly.
     */
    public static synchronized Map<String, LauncherProduction> launcherProductions() {
        if (launcherProductions == null) {
            var map = new LinkedHashMap<String, LauncherProduction>();
            for (var example : ClassifiedCorpus.examples()) {
                var schema = classify(example.sdl()).schema();
                LauncherProduction outcome;
                try {
                    var conditions = no.sikt.graphitron.plan.ConditionCommands.produce(
                        schema, TestConfiguration.DEFAULT_OUTPUT_PACKAGE);
                    outcome = new LauncherProduction.Produced(
                        no.sikt.graphitron.plan.LauncherCommands.produce(
                            schema, conditions, TestConfiguration.DEFAULT_OUTPUT_PACKAGE));
                } catch (IllegalStateException e) {
                    outcome = new LauncherProduction.Failed(e.getMessage());
                }
                map.put(example.id(), outcome);
            }
            launcherProductions = Collections.unmodifiableMap(map);
        }
        return launcherProductions;
    }

    /**
     * Joins one example's {@code @commits} declarations against its launcher production
     * outcome: the produced side is the relation row's arm simple names at the declared
     * coordinate, or {@code null} when no row exists there (including the whole-example
     * {@link LauncherProduction.Failed} case, where no relation exists at all).
     */
    public static List<CommitCase> commitCases(Result result, LauncherProduction production) {
        var relation = production instanceof LauncherProduction.Produced p ? p.relation() : null;
        var cases = new ArrayList<CommitCase>();
        for (var declaration : result.commits()) {
            var row = relation == null
                ? java.util.Optional.<no.sikt.graphitron.command.LauncherCommand>empty()
                : relation.rowFor(declaration.parentType(), declaration.fieldName());
            cases.add(new CommitCase(declaration.parentType(), declaration.fieldName(),
                declaration.source(), declaration.result(),
                row.map(r -> r.source().getClass().getSimpleName()).orElse(null),
                row.map(r -> r.result().getClass().getSimpleName()).orElse(null)));
        }
        return cases;
    }

    /**
     * Resolves one {@code @synthesises} coordinate: the declared mints parsed off the directive's
     * {@code mints:} list, and the produced mints derived from the connection-synthesis
     * relation's row at the coordinate ({@code GraphitronSchema.connectionSynthesis().mintedAt}),
     * filtered to entries whose classified registry arm matches the row's declaration.
     */
    private static SynthesisCase synthesisCase(GraphitronSchema schema, String parentType,
            String fieldName, Directive d) {
        var declared = new LinkedHashSet<Mint>();
        var mintsValue = (graphql.language.ArrayValue) argValue(d, "mints");
        for (Value<?> v : mintsValue.getValues()) {
            var ov = (graphql.language.ObjectValue) v;
            String name = null;
            String arm = null;
            for (var of : ov.getObjectFields()) {
                if (of.getName().equals("name")) {
                    name = ((graphql.language.StringValue) of.getValue()).getValue();
                } else if (of.getName().equals("as")) {
                    arm = ((EnumValue) of.getValue()).getName();
                }
            }
            if (name == null || arm == null) {
                throw new AssertionError("@synthesises: each mint needs name: and as:");
            }
            declared.add(new Mint(name, arm));
        }
        var produced = new LinkedHashSet<Mint>();
        for (var minted : schema.connectionSynthesis().mintedAt(parentType, fieldName)) {
            if (minted.declaredArm().isInstance(schema.type(minted.name()))) {
                produced.add(new Mint(minted.name(), minted.declaredArm().getSimpleName()));
            }
        }
        return new SynthesisCase(parentType, fieldName, declared, produced);
    }

    private static FieldCase fieldCase(GraphitronSchema schema, String parentType, String fieldName, Directive d) {
        GraphitronField field = schema.field(parentType, fieldName);
        if (!(field instanceof OutputField out)) {
            throw new AssertionError(
                "@classified coordinate " + parentType + "." + fieldName + " did not classify to an "
                + "OutputField (got " + (field == null ? "null" : field.getClass().getSimpleName())
                + "); the corpus asserts successful classification only.");
        }
        DimensionTuple expected = new DimensionTuple(sourceArg(d), operationArg(d), operationsArg(d), targetArg(d));
        // The arrival arm is the parent-type ancestor-product fold and the member rows are the
        // minted relation's, both read through the schema seams every consumer reads (a leaf
        // cannot compute its own arm, and the corpus asserts the production, not the leaf-derived
        // comparison side). target stays leaf-derived.
        DimensionTuple actual = DimensionTuple.of(out, schema.sourceOf(parentType, fieldName),
            schema.operationMembersOf(parentType, fieldName));
        return new FieldCase(parentType, fieldName, expected, actual, out.getClass());
    }

    private static TypeCase typeCase(GraphitronSchema schema, String typeName, Directive d) {
        GraphitronType type = schema.type(typeName);
        String actual = type == null ? "<absent>" : type.getClass().getSimpleName();
        return new TypeCase(typeName, enumArg(d, "as"), actual, type == null ? null : type.getClass());
    }

    /**
 * The {@code source:} arrival wrapper, reconstructed in full from the directive: the flat
     * {@code SourceWrapper} enum names the wrapper arm ({@link Source.Root.Query} / {@link Source.Root.Mutation}
     * / {@link Source.OnlyChild} / {@link Source.Child}), and the nested arms take their {@link SourceShape}
     * from {@code sourceShape:}. {@link Source} carries no heavy payload, so the whole value is asserted by
     * structural equality against {@link OutputField#source()}.
     */
    private static Source sourceArg(Directive d) {
        String w = enumArg(d, "source");
        return switch (w) {
            case "Query" -> new Source.Root.Query();
            case "Mutation" -> new Source.Root.Mutation();
            case "OnlyChild" -> new Source.OnlyChild(sourceShapeArg(d));
            case "Child" -> new Source.Child(sourceShapeArg(d));
            default -> throw new AssertionError("@classified: unknown source wrapper '" + w + "'");
        };
    }

    /**
 * The nested-arm source-shape. Defaults to {@link SourceShape#Table} (the common
     * catalog-backed case) when the arg is absent, so only the record-source rows declare
     * {@code sourceShape: Record} explicitly. A row that should be {@code Record} but omits the arg
     * fails loudly: the expected {@code Child(Table)} mismatches the actual {@code Child(Record)}.
     */
    private static SourceShape sourceShapeArg(Directive d) {
        Argument a = d.getArgument("sourceShape");
        return a == null ? SourceShape.Table : SourceShape.valueOf(((EnumValue) a.getValue()).getName());
    }

    /**
 * The {@code operation:} verb, as the {@link Operation} arm type token. The arm carries a
     * payload the directive cannot express, so the corpus asserts arm identity only; the token is
     * resolved from the seal's leaf set by simple name, so a directive value that names no arm fails.
     */
    private static Class<? extends Operation> operationArg(Directive d) {
        String name = enumArg(d, "operation");
        Class<? extends Operation> arm = OPERATION_ARMS.get(name);
        if (arm == null) {
            throw new AssertionError("@classified: unknown operation '" + name + "'");
        }
        return arm;
    }

    /**
 * The {@code operations:} member rows, as {@link OperationMember} arm type tokens sorted by
     * simple name (the same canonical order {@code DimensionTuple.memberArmsOf} imposes on the
     * produced side, so the SDL list order never matters). A multiset: a token may repeat, one
     * entry per member row. Each token is resolved from the seal's recursive leaf set by simple
     * name, so a directive value that names no arm fails.
     */
    private static List<Class<? extends OperationMember>> operationsArg(Directive d) {
        var tokens = enumListArg(d, "operations");
        var arms = new ArrayList<Class<? extends OperationMember>>();
        for (String name : tokens) {
            Class<? extends OperationMember> arm = MEMBER_ARMS.get(name);
            if (arm == null) {
                throw new AssertionError("@classified: unknown member arm '" + name + "'");
            }
            arms.add(arm);
        }
        arms.sort(Comparator.comparing(Class::getSimpleName));
        return List.copyOf(arms);
    }

    /**
     * The {@link OperationMember.Kind} column of a declared member arm, derived from the seal's
     * own structure (the nested {@code Write} and {@code Condition} sub-seals name their kinds;
     * every other leaf's simple name is its kind constant in upper snake case) rather than a
     * second hand-maintained switch that could drift from {@link OperationMember#kind()}.
     */
    public static OperationMember.Kind kindOf(Class<? extends OperationMember> arm) {
        if (OperationMember.Write.class.isAssignableFrom(arm)) {
            return OperationMember.Kind.WRITE;
        }
        if (OperationMember.Condition.class.isAssignableFrom(arm)) {
            return OperationMember.Kind.CONDITION;
        }
        return OperationMember.Kind.valueOf(
            arm.getSimpleName().replaceAll("([a-z])([A-Z])", "$1_$2").toUpperCase(java.util.Locale.ROOT));
    }

    /**
 * The {@code target:} / {@code targetShape:} projection coordinate: the {@link Target} wrapper
     * arm token and the outer {@link TargetShape} arm token, both resolved from their seals' leaf sets by
     * simple name.
     */
    private static DimensionTuple.TargetVerdict targetArg(Directive d) {
        Class<? extends Target> wrapper = TARGET_WRAPPERS.get(enumArg(d, "target"));
        if (wrapper == null) {
            throw new AssertionError("@classified: unknown target wrapper '" + enumArg(d, "target") + "'");
        }
        Class<? extends TargetShape> shape = TARGET_SHAPES.get(enumArg(d, "targetShape"));
        if (shape == null) {
            throw new AssertionError("@classified: unknown targetShape '" + enumArg(d, "targetShape") + "'");
        }
        return new DimensionTuple.TargetVerdict(wrapper, shape);
    }

    /**
     * The arm type tokens of a sealed hierarchy, keyed by simple name (the SDL enum constant). Built from
     * the seal's recursive leaf set via {@code sealedLeaves}, so the SDL-vs-Java mirror is what pins the
     * name set; {@code toMap} additionally fails fast on a duplicate simple name within the seal.
     */
    @SuppressWarnings("unchecked")
    private static <T> Map<String, Class<? extends T>> armsByName(Class<T> seal) {
        return GeneratorCoverageTest.sealedLeaves(seal).stream()
            .collect(Collectors.toMap(Class::getSimpleName, c -> (Class<? extends T>) c));
    }

    private static final Map<String, Class<? extends Operation>> OPERATION_ARMS = armsByName(Operation.class);
    private static final Map<String, Class<? extends OperationMember>> MEMBER_ARMS = armsByName(OperationMember.class);
    private static final Map<String, Class<? extends Target>> TARGET_WRAPPERS = armsByName(Target.class);
    private static final Map<String, Class<? extends TargetShape>> TARGET_SHAPES = armsByName(TargetShape.class);

    private static String enumArg(Directive d, String argName) {
        return ((EnumValue) argValue(d, argName)).getName();
    }

    /** A required list-of-enum argument's value names, in SDL order. */
    private static List<String> enumListArg(Directive d, String argName) {
        var value = argValue(d, argName);
        if (!(value instanceof ArrayValue array)) {
            throw new AssertionError("@" + d.getName() + " argument '" + argName
                + "' must be a list of enum values (got " + value.getClass().getSimpleName() + ")");
        }
        return array.getValues().stream().map(v -> ((EnumValue) v).getName()).toList();
    }

    private static Value<?> argValue(Directive d, String argName) {
        Argument a = d.getArgument(argName);
        if (a == null) {
            throw new AssertionError("@" + d.getName() + " is missing required argument '" + argName + "'");
        }
        return a.getValue();
    }

    private static Directive directive(List<Directive> directives, String name) {
        return directives.stream().filter(x -> x.getName().equals(name)).findFirst().orElse(null);
    }

    /**
     * Applies {@code action} to {@code field} and, recursively, to every field riding one of its
     * carried lists: a {@code NestingField}'s {@code nestedFields()} and a pivot leaf's
     * {@code PivotSpec.slots()}. Those fields have no {@code schema.fields()} coordinate of
     * their own (a pivot slot never appears there; a nesting target's children live only on the
     * embedding leaf), so a coverage walk that stops at top-level coordinates never observes
     * them. The coordinate relation is untouched: the SDL coordinate is still the consuming
     * leaf; this widens the reader, not the {@code @classified} contract.
     */
    public static void forEachWithRiddenFields(
            GraphitronField field, java.util.function.Consumer<GraphitronField> action) {
        action.accept(field);
        switch (field) {
            case no.sikt.graphitron.rewrite.model.ChildField.NestingField n ->
                n.nestedFields().forEach(f -> forEachWithRiddenFields(f, action));
            case no.sikt.graphitron.rewrite.model.ChildField.PivotField p ->
                p.spec().slots().forEach(s -> forEachWithRiddenFields(s, action));
            case no.sikt.graphitron.rewrite.model.ChildField.BatchedPivotField p ->
                p.spec().slots().forEach(s -> forEachWithRiddenFields(s, action));
            default -> { }
        }
    }

    // ----- meta-test support: the SDL-vs-Java enum mirrors -----

    /** The {@code TypeVerdict} enum constants as declared in {@link ClassifiedDsl#PRELUDE}. */
    public static Set<String> typeVerdictEnumConstants() {
        return preludeEnumConstants("TypeVerdict");
    }

    /** The {@code SourceWrapper} enum constants as declared in {@link ClassifiedDsl#PRELUDE}. */
    public static Set<String> sourceWrapperEnumConstants() {
        return preludeEnumConstants("SourceWrapper");
    }

    /**
     * The simple names of the sealed {@link Source} leaf arms (the live arrival-wrapper set). Uses the
     * recursive leaf walker so {@link Source.Root} flattens to its {@code Query} / {@code Mutation} leaves,
     * matching the flat {@code SourceWrapper} SDL enum.
     */
    public static List<String> sourceWrapperArmSimpleNames() {
        return sealedLeafSimpleNames(Source.class);
    }

    /** The {@code Operation} enum constants as declared in {@link ClassifiedDsl#PRELUDE}. */
    public static Set<String> operationEnumConstants() {
        return preludeEnumConstants("Operation");
    }

    /**
     * The simple names of the sealed {@link Operation} arms (the live verb set). The {@code Operation}
     * arms are all direct records, so the recursive walker stops at them; the per-arm transitional holders
     * (e.g. {@code ServiceCallCarrier}) are payload components, not permitted subclasses, so they never enter
     * this set.
     */
    public static List<String> operationArmSimpleNames() {
        return sealedLeafSimpleNames(Operation.class);
    }

    /** The {@code Member} enum constants as declared in {@link ClassifiedDsl#PRELUDE}. */
    public static Set<String> memberEnumConstants() {
        return preludeEnumConstants("Member");
    }

    /**
     * The simple names of the sealed {@link OperationMember} leaves (the live member-arm
     * vocabulary the {@code operations:} tokens resolve against). The recursive walker descends
     * the nested {@code Write} and {@code Condition} sub-seals to their leaves, so the verb and
     * condition arms appear individually.
     */
    public static List<String> memberArmSimpleNames() {
        return sealedLeafSimpleNames(OperationMember.class);
    }

    /** The {@code TargetWrapper} enum constants as declared in {@link ClassifiedDsl#PRELUDE}. */
    public static Set<String> targetWrapperEnumConstants() {
        return preludeEnumConstants("TargetWrapper");
    }

    /** The simple names of the sealed {@link Target} wrapper arms ({@code Single} / {@code List}). */
    public static List<String> targetWrapperArmSimpleNames() {
        return sealedLeafSimpleNames(Target.class);
    }

    /** The {@code TargetShape} enum constants as declared in {@link ClassifiedDsl#PRELUDE}. */
    public static Set<String> targetShapeEnumConstants() {
        return preludeEnumConstants("TargetShape");
    }

    /** The simple names of the sealed {@link TargetShape} arms (the live projection-shape set). */
    public static List<String> targetShapeArmSimpleNames() {
        return sealedLeafSimpleNames(TargetShape.class);
    }

    /** The {@code SourceShape} enum constants as declared in {@link ClassifiedDsl#PRELUDE}. */
    public static Set<String> sourceShapeEnumConstants() {
        return preludeEnumConstants("SourceShape");
    }

    /** The {@code SynthesisedType} enum constants as declared in {@link ClassifiedDsl#PRELUDE}. */
    public static Set<String> synthesisedTypeEnumConstants() {
        return preludeEnumConstants("SynthesisedType");
    }

    /** The {@code LauncherSource} enum constants as declared in {@link ClassifiedDsl#PRELUDE}. */
    public static Set<String> launcherSourceEnumConstants() {
        return preludeEnumConstants("LauncherSource");
    }

    /**
     * The simple names of the concrete sealed {@link no.sikt.graphitron.command.LaunchSource}
     * arms (the live launcher-source set the {@code LauncherSource} SDL enum must mirror). The
     * recursive walker flattens the {@code Correlated} / {@code Reentry} capability seals to
     * their concrete arms.
     */
    public static List<String> launchSourceArmSimpleNames() {
        return sealedLeafSimpleNames(no.sikt.graphitron.command.LaunchSource.class);
    }

    /** The {@code LauncherResult} enum constants as declared in {@link ClassifiedDsl#PRELUDE}. */
    public static Set<String> launcherResultEnumConstants() {
        return preludeEnumConstants("LauncherResult");
    }

    /**
     * The simple names of the sealed {@link no.sikt.graphitron.command.ResultShape} arms (the
     * live result-shape set the {@code LauncherResult} SDL enum must mirror).
     */
    public static List<String> resultShapeArmSimpleNames() {
        return sealedLeafSimpleNames(no.sikt.graphitron.command.ResultShape.class);
    }

    /**
     * The simple names of every concrete sealed leaf of {@code seal}, in discovery order and
     * <em>preserving duplicates</em>, so a mirror that compares by simple name can assert the set has no
     * duplicate names before relying on the name-based comparison (the discipline
     * {@link #graphitronTypeNonFailureLeafSimpleNames()} already applies for {@code GraphitronType}).
     */
    private static List<String> sealedLeafSimpleNames(Class<?> seal) {
        return GeneratorCoverageTest.sealedLeaves(seal).stream()
            .map(Class::getSimpleName)
            .toList();
    }

    /** The constant names of an enum declared in {@link ClassifiedDsl#PRELUDE}, in declaration order. */
    private static Set<String> preludeEnumConstants(String enumName) {
        TypeDefinitionRegistry registry = new SchemaParser().parse(ClassifiedDsl.PRELUDE);
        EnumTypeDefinition def = registry.getTypeOrNull(enumName, EnumTypeDefinition.class);
        if (def == null) {
            throw new AssertionError(enumName + " enum missing from the DSL prelude");
        }
        var names = new LinkedHashSet<String>();
        for (EnumValueDefinition v : def.getEnumValueDefinitions()) {
            names.add(v.getName());
        }
        return names;
    }

    /**
     * The simple names of every concrete {@code GraphitronType} sealed leaf except the failure leaf
     * {@code UnclassifiedType}, in discovery order and <em>preserving duplicates</em>. The mirror
     * compares {@code TypeVerdict} against these by simple name, so two leaves sharing a simple name
     * would silently collapse in the set form below; {@code ClassifiedDslTest} asserts this list has
     * no duplicates to keep the name-based comparison sound.
     */
    public static List<String> graphitronTypeNonFailureLeafSimpleNames() {
        return GeneratorCoverageTest.sealedLeaves(GraphitronType.class).stream()
            .map(Class::getSimpleName)
            .filter(n -> !n.equals("UnclassifiedType"))
            .toList();
    }

    /**
     * The simple names of every concrete {@code GraphitronType} sealed leaf except the failure leaf
     * {@code UnclassifiedType}. This is the set {@code TypeVerdict} must mirror.
     */
    public static Set<String> graphitronTypeNonFailureLeafNames() {
        return new LinkedHashSet<>(graphitronTypeNonFailureLeafSimpleNames());
    }
}
