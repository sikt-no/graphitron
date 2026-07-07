package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.javapoet.TypeSpec;
import no.sikt.graphitron.rewrite.generators.schema.EnumTypeGenerator;
import no.sikt.graphitron.rewrite.generators.schema.GraphitronSchemaClassGenerator;
import no.sikt.graphitron.rewrite.generators.schema.InputTypeGenerator;
import no.sikt.graphitron.rewrite.generators.schema.ObjectTypeGenerator;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R254 regression: keeps every generated schema-side {@code .java} file's per-statement
 * chain depth bounded so {@code javac}'s chained-call attribution recursion stays well clear
 * of any practical {@code -Xss} budget. The bug surfaced as a {@code StackOverflowError} in
 * incremental {@code javac} runs (e.g. {@code quarkus:dev} + {@code graphitron:dev}); cold
 * full builds masked it because batch compilation primes name tables and class symbols
 * cheaply, and the dev loop's single-file incremental compile pays those costs cold.
 *
 * <p><b>Carve-out vs. the "code-string assertions banned" principle.</b>
 * {@code development-principles.adoc} § "Behaviour is pinned at the pipeline tier and above"
 * bans code-string assertions on generated method bodies because they test implementation,
 * not behaviour, and break on every refactor. The defect this test guards is itself an
 * implementation-shape property: chain depth has no behavioural correlate, only a (fragile,
 * JVM- and stack-dependent) {@code StackOverflowError} symptom. The assertion is a single
 * bound (depth ≤ {@link #CHAIN_DEPTH_BOUND}) that does not name specific call sequences, so
 * it doesn't break on refactors that change <em>what</em> is emitted, only on regressions
 * that re-introduce a long chain.
 *
 * <p><b>Bound choice.</b> {@link #CHAIN_DEPTH_BOUND} is plenty conservative (cold-stack budget
 * tolerates several hundred chained calls) and tight enough that a regression re-introducing
 * a long chain trips it immediately.
 */
@PipelineTier
class SchemaEmissionChainDepthPipelineTest {

    /**
     * Per-statement chain-depth cap. Statement-flattened emission (R254) holds every
     * generated expression-statement well below this; the bound is a tripwire for a
     * regression that re-introduces fluent emission.
     */
    private static final int CHAIN_DEPTH_BOUND = 16;

    /**
     * Federation @link + a realistic mix of types and directives. The chain-depth regression
     * surfaced when a federation @link version bump expanded the imported directive set,
     * folding more {@code .additionalDirective(...)} chain segments onto the same expression.
     */
    private static final String FEDERATION_SDL = """
        directive @link(url: String!, import: [String!]) repeatable on SCHEMA
        directive @key(fields: String!, resolvable: Boolean = true) repeatable on OBJECT | INTERFACE
        directive @external on FIELD_DEFINITION | OBJECT
        directive @shareable repeatable on OBJECT | FIELD_DEFINITION
        directive @auth(roles: [String!]) on FIELD_DEFINITION | OBJECT

        extend schema @link(url: "https://specs.apollo.dev/federation/v2.10",
                            import: ["@key", "@external", "@shareable"])

        type Query {
            users: [User!]!
            secret: String @auth(roles: ["admin", "ops"])
            findUsers(filter: AddressInput): [User!]!
        }

        type User @key(fields: "id") @shareable {
            id: ID!
            firstName: String
            lastName: String
            email: String @external
            status: Status
        }

        type FilmActor @key(fields: "filmId actorId") {
            filmId: Int!
            actorId: Int!
        }

        enum Status { ACTIVE INACTIVE PENDING }

        input AddressInput {
            street: String
            city: String
            postalCode: String
        }
        """;

    /**
     * Same realistic mix, scaled up: many fields per type, several types, several enum values.
     * Forces the per-type {@code <Name>Type.type()} method body to register enough elements
     * that the pre-R254 fluent form would have produced a chain dozens of segments long.
     */
    private static final String LARGE_SDL = """
        directive @auth(roles: [String!]) on FIELD_DEFINITION

        type Query {
            field01: String  field02: String  field03: String  field04: String  field05: String
            field06: String  field07: String  field08: String  field09: String  field10: String
            field11: String  field12: String  field13: String  field14: String  field15: String
            field16: String  field17: String  field18: String  field19: String  field20: String
            field21: String  field22: String  field23: String  field24: String  field25: String
        }

        type Customer {
            id: ID!
            firstName: String
            lastName: String
            email: String @auth(roles: ["admin"])
            postalCode: String
            country: String
            phone: String
            createdAt: String
            updatedAt: String
            status: CustomerStatus
        }

        enum CustomerStatus {
            ACTIVE  INACTIVE  PENDING  SUSPENDED  TERMINATED
            ONBOARDING  ARCHIVED  DELETED  RESTORED  MIGRATING
        }

        input CustomerFilter {
            firstName: String
            lastName: String
            email: String
            status: CustomerStatus
            country: String
        }
        """;

    @Test
    void chainDepthBound_holdsOnGraphitronSchemaClass_federationFixture() {
        var bundle = TestSchemaHelper.buildBundle(FEDERATION_SDL);
        String rendered = GraphitronSchemaClassGenerator
            .generate(bundle.model(), bundle.assembled(), Set.of(), "com.example", false)
            .get(0).toString();
        assertThat(maxChainDepth(rendered))
            .as("GraphitronSchema chain depth must stay within bound; "
                + "regression re-introduces a long fluent chain")
            .isLessThanOrEqualTo(CHAIN_DEPTH_BOUND);
    }

    @Test
    void chainDepthBound_holdsOnGraphitronSchemaClass_largeFixture() {
        var bundle = TestSchemaHelper.buildBundle(LARGE_SDL);
        String rendered = GraphitronSchemaClassGenerator
            .generate(bundle.model(), bundle.assembled(), Set.of(), "com.example", false)
            .get(0).toString();
        assertThat(maxChainDepth(rendered))
            .as("GraphitronSchema chain depth must stay within bound on a large schema")
            .isLessThanOrEqualTo(CHAIN_DEPTH_BOUND);
    }

    @Test
    void chainDepthBound_holdsOnEveryEmittedPerTypeClass_federationFixture() {
        assertPerTypeChainDepth(FEDERATION_SDL);
    }

    @Test
    void chainDepthBound_holdsOnEveryEmittedPerTypeClass_largeFixture() {
        assertPerTypeChainDepth(LARGE_SDL);
    }

    private static void assertPerTypeChainDepth(String sdl) {
        var bundle = TestSchemaHelper.buildBundle(sdl);
        var perType = new ArrayList<TypeSpec>();
        perType.addAll(ObjectTypeGenerator.generate(bundle.model(), bundle.assembled()));
        perType.addAll(InputTypeGenerator.generate(bundle.model()));
        perType.addAll(EnumTypeGenerator.generate(bundle.model()));

        assertThat(perType).isNotEmpty();
        for (var spec : perType) {
            String rendered = spec.toString();
            int depth = maxChainDepth(rendered);
            assertThat(depth)
                .as("%sType chain depth must stay within bound; "
                    + "regression re-introduces a long fluent chain", spec.name())
                .isLessThanOrEqualTo(CHAIN_DEPTH_BOUND);
        }
    }

    @Test
    void statementCountInGraphitronSchemaBuildBody_scalesWithSchemaSize() {
        // Pins the shape: "many short statements", not "one long chain that happens to be
        // under the bound today". Two synthetic schemas at different sizes; the build body's
        // statement count grows with element count.
        int smallStmts = buildMethodStatementCount("""
            type Query { x: String }
            """);
        int largeStmts = buildMethodStatementCount(LARGE_SDL);
        assertThat(largeStmts)
            .as("build() statement count must grow with schema size; "
                + "regression collapses statements into a chained expression")
            .isGreaterThan(smallStmts);
    }

    private static int buildMethodStatementCount(String sdl) {
        var bundle = TestSchemaHelper.buildBundle(sdl);
        var spec = GraphitronSchemaClassGenerator
            .generate(bundle.model(), bundle.assembled(), Set.of(), "com.example", false)
            .get(0);
        var build = spec.methodSpecs().stream()
            .filter(m -> "build".equals(m.name()))
            .filter(m -> m.modifiers().contains(javax.lang.model.element.Modifier.PUBLIC))
            .findFirst()
            .orElseThrow();
        return countTopLevelStatements(build.code().toString());
    }

    /**
     * Counts top-level statements in a method body string. A statement is a {@code ;} that
     * is not nested inside parentheses, brackets, braces, or string literals. Block bodies
     * (control-flow lambdas, control flow) are counted by their inner statements as well so
     * the count tracks "how many builder calls did the emitter unroll" rather than "how many
     * top-level lines".
     */
    private static int countTopLevelStatements(String body) {
        String stripped = stripStringsAndComments(body);
        int count = 0;
        int parens = 0;
        for (int i = 0; i < stripped.length(); i++) {
            char c = stripped.charAt(i);
            if (c == '(' || c == '[') parens++;
            else if (c == ')' || c == ']') parens--;
            else if (c == ';' && parens <= 0) count++;
        }
        return count;
    }

    /**
     * Counts maximum chain depth in {@code source}. A chain is a sequence of consecutive
     * {@code .<ident>(...)} segments. The count resets at statement boundaries: a {@code ;}
     * token, a variable assignment (a line whose leading non-whitespace tokens are
     * {@code <ident> <ws>* =}), or a {@code return} keyword token. Chain depth never
     * decreases inside the same statement; the result is the deepest single-statement chain.
     *
     * <p>Strings and comments are stripped before scanning so a literal containing
     * {@code .foo(} doesn't count toward the depth.
     */
    static int maxChainDepth(String source) {
        String s = stripStringsAndComments(source);
        // Reset markers: ';', '\\b(?:return|yield)\\b', '^<ws>+<ident>\\s*=' (assignment).
        // Replace each with a single ';' so we can split.
        String collapsed = s
            .replaceAll("(?m)^\\s+\\w+(?:\\s*\\.\\s*\\w+)*\\s*=(?!=)", ";")
            .replaceAll("\\breturn\\b", ";")
            .replaceAll("\\byield\\b", ";");
        Pattern segment = Pattern.compile("\\.[A-Za-z_]\\w*\\s*\\(");
        int max = 0;
        for (String chunk : collapsed.split(";")) {
            int count = 0;
            Matcher m = segment.matcher(chunk);
            while (m.find()) count++;
            if (count > max) max = count;
        }
        return max;
    }

    /**
     * Replaces string literals with whitespace and removes line / block comments. Preserves
     * source length and most line boundaries so the assignment-line regex in
     * {@link #maxChainDepth(String)} still matches.
     */
    private static String stripStringsAndComments(String source) {
        StringBuilder out = new StringBuilder(source.length());
        int i = 0;
        int n = source.length();
        while (i < n) {
            char c = source.charAt(i);
            if (c == '"') {
                out.append(' ');
                i++;
                while (i < n && source.charAt(i) != '"') {
                    if (source.charAt(i) == '\\' && i + 1 < n) {
                        out.append(' ');
                        i++;
                    }
                    out.append(' ');
                    i++;
                }
                if (i < n) {
                    out.append(' ');
                    i++;
                }
            } else if (c == '\'') {
                out.append(' ');
                i++;
                while (i < n && source.charAt(i) != '\'') {
                    if (source.charAt(i) == '\\' && i + 1 < n) {
                        out.append(' ');
                        i++;
                    }
                    out.append(' ');
                    i++;
                }
                if (i < n) {
                    out.append(' ');
                    i++;
                }
            } else if (c == '/' && i + 1 < n && source.charAt(i + 1) == '/') {
                while (i < n && source.charAt(i) != '\n') {
                    out.append(' ');
                    i++;
                }
            } else if (c == '/' && i + 1 < n && source.charAt(i + 1) == '*') {
                out.append(' ');
                out.append(' ');
                i += 2;
                while (i + 1 < n && !(source.charAt(i) == '*' && source.charAt(i + 1) == '/')) {
                    out.append(source.charAt(i) == '\n' ? '\n' : ' ');
                    i++;
                }
                if (i + 1 < n) {
                    out.append(' ');
                    out.append(' ');
                    i += 2;
                }
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    /**
     * Smoke check on the scanner itself: a hand-written long-chain source must score above
     * the bound, so we know the regression-detection side of the test fires.
     */
    @Test
    void maxChainDepth_detectsLongChain() {
        StringBuilder sb = new StringBuilder("var x = builder");
        for (int i = 0; i < CHAIN_DEPTH_BOUND + 5; i++) {
            sb.append(".step").append(i).append("(arg)");
        }
        sb.append(";");
        assertThat(maxChainDepth(sb.toString()))
            .as("scanner counts a synthetic long fluent chain above the bound")
            .isGreaterThan(CHAIN_DEPTH_BOUND);
    }

    @Test
    void maxChainDepth_resetsOnStatementBoundary() {
        // Two unrelated chains in separate statements; each below the bound.
        String src = """
            var x = a.b().c().d().e();
            var y = p.q().r().s();
            """;
        assertThat(maxChainDepth(src)).isEqualTo(4);
    }

    /**
     * Pins what the scanner actually counts: the deepest single-chain-expression in any
     * one statement. A flat builder-statement body should score 1 per statement.
     */
    @Test
    void maxChainDepth_oneSegmentPerStatement_isOne() {
        String src = """
            b.field1(x);
            b.field2(y);
            b.field3(z);
            return b.build();
            """;
        assertThat(maxChainDepth(src)).isEqualTo(1);
    }
}
