package no.sikt.graphitron.rewrite;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static no.sikt.graphitron.rewrite.TestSchemaHelper.buildSchema;
import static org.assertj.core.api.Assertions.assertThat;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;

/**
 * Pins the warn surface for {@code @asConnection} + required same-table {@code @nodeId} leaf.
 *
 * <p>R113 demoted the classifier rejection on this shape to a {@code LOG.warn}; production
 * schemas (e.g. opptak-subgraph's {@code Query.kompetanseregelverkGittIdV2}) deliberately
 * compose required same-table {@code @nodeId} args with {@code @asConnection} to ship a
 * paginated WHERE-IN connection to consumers, and a build break on that shape would be a
 * wire-format-incompatible change. The warn still fires (the always-bounded shape is
 * worth flagging for authors who didn't mean to compose them), but classification continues.
 *
 * <p>The four cases here pin both directions of the predicate at the warn surface:
 * <ul>
 *   <li>required leaf → warn fires, message format stable for migration tooling</li>
 *   <li>optional leaf → warn does not fire (∃-required predicate is False)</li>
 *   <li>FK-target leaf → warn does not fire (out of the predicate's scope)</li>
 *   <li>nullable-outer + required-inner → warn does not fire (conjunctive rule)</li>
 * </ul>
 */
@UnitTier
class AsConnectionSameTableWarnFormatTest {

    private static final String LOGGER_NAME = FieldBuilder.class.getName();

    private static final RewriteContext NODEID_CTX = new RewriteContext(
        List.of(),
        Path.of(""),
        Path.of(""),
        "fake.code.generated",
        "no.sikt.graphitron.rewrite.nodeidfixture",
        Map.of()
    );

    private static final String CONNECTION_DECLS = """
        type BazConnection { edges: [BazEdge!]! pageInfo: PageInfo! }
        type BazEdge { node: Baz! cursor: String! }
        type PageInfo { hasNextPage: Boolean! hasPreviousPage: Boolean! startCursor: String endCursor: String }
        """;

    private ListAppender<ILoggingEvent> appender;
    private Logger fieldBuilderLogger;

    @BeforeEach
    void attachAppender() {
        fieldBuilderLogger = (Logger) LoggerFactory.getLogger(LOGGER_NAME);
        appender = new ListAppender<>();
        appender.start();
        fieldBuilderLogger.addAppender(appender);
        fieldBuilderLogger.setLevel(Level.WARN);
    }

    @AfterEach
    void detachAppender() {
        fieldBuilderLogger.detachAppender(appender);
        appender.stop();
    }

    private List<String> warnMessagesContaining(String needle) {
        return appender.list.stream()
            .filter(e -> e.getLevel() == Level.WARN)
            .map(ILoggingEvent::getFormattedMessage)
            .filter(m -> m.contains(needle))
            .toList();
    }

    @Test
    void requiredLeaf_emitsWarn_namingFieldLeafAndType() {
        // Mirrors the production shape (required outer wrapper on a same-table @nodeId list arg
        // composed with @asConnection). The classifier emits the warn but classification still
        // produces a working QueryTableField + Connection wrapper at runtime.
        buildSchema("""
            type Baz implements Node @table(name: "baz") @node { id: ID! }
            """ + CONNECTION_DECLS + """
            type Query {
                bazByIds(ids: [ID!]! @nodeId(typeName: "Baz")): BazConnection @asConnection
            }
            """, NODEID_CTX);

        var msgs = warnMessagesContaining("@asConnection");
        assertThat(msgs).hasSize(1);
        var msg = msgs.get(0);
        assertThat(msg).contains("field 'bazByIds'");
        assertThat(msg).contains("@nodeId(typeName: 'Baz')");
        assertThat(msg).contains("'ids'");
        assertThat(msg).contains("every page of @asConnection would equal the input set");
        assertThat(msg).contains("make 'ids' nullable");
    }

    @Test
    void optionalLeaf_doesNotEmitWarn() {
        // Optional outer wrapper → ∃-required predicate is False → no warn. Pins the silence:
        // the advisory is *not* "any same-table leaf"; it is "required same-table leaf."
        buildSchema("""
            type Baz implements Node @table(name: "baz") @node { id: ID! }
            """ + CONNECTION_DECLS + """
            type Query {
                bazes(ids: [ID!] @nodeId(typeName: "Baz")): BazConnection @asConnection
            }
            """, NODEID_CTX);

        assertThat(warnMessagesContaining("@asConnection")).isEmpty();
    }

    @Test
    void fkTargetLeaf_doesNotEmitWarn() {
        // FK-target leaf (resolves to a *different* table via FK) is out of the predicate's
        // scope by construction; the warn is same-table-specific. Defends against accidental
        // broadening into FK-target territory.
        buildSchema("""
            type Bar implements Node @table(name: "bar") @node { id: ID! }
            type Baz implements Node @table(name: "baz") @node { id: ID! }
            type BarConnection { edges: [BarEdge!]! pageInfo: PageInfo! }
            type BarEdge { node: Bar! cursor: String! }
            type PageInfo { hasNextPage: Boolean! hasPreviousPage: Boolean! startCursor: String endCursor: String }
            type Query {
                barsByBaz(bazIds: [ID!]! @nodeId(typeName: "Baz")): BarConnection @asConnection
            }
            """, NODEID_CTX);

        assertThat(warnMessagesContaining("@asConnection")).isEmpty();
    }

    @Test
    void nullableOuterRequiredInner_doesNotEmitWarn() {
        // Conjunctive path-required: nullable outer arg short-circuits the path even though
        // the inner leaf is non-null. Pins that the predicate is conjunctive, not per-step.
        buildSchema("""
            type Baz implements Node @table(name: "baz") @node { id: ID! }
            """ + CONNECTION_DECLS + """
            input BazFilter @table(name: "baz") {
                ids: [ID!]! @nodeId(typeName: "Baz")
            }
            type Query {
                bazes(filter: BazFilter): BazConnection @asConnection
            }
            """, NODEID_CTX);

        assertThat(warnMessagesContaining("@asConnection")).isEmpty();
    }
}
