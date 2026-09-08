package no.sikt.graphitron.rewrite.test.querydb;

import graphql.GraphQL;
import no.sikt.graphitron.generated.Graphitron;
import no.sikt.graphitron.rewrite.test.tier.ExecutionTier;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Execution-tier coverage for a <em>generated filter</em> — a predicate graphitron mints from a
 * schema argument bound to a column, with no developer method involved — over a converter-backed
 * column, in all four shapes the condition glue lowers one to.
 *
 * <p>The column is {@code converter_org.org_code} and its two children's copies of it: a
 * PostgreSQL domain over {@code bigint} whose jOOQ codegen attached
 * {@link no.sikt.graphitron.rewrite.fixtures.codegen.OrgCodeStringConverter}, so generated code
 * handles it as {@code String} while the SQL type stays the numeric domain. The four shapes are
 * single-column equality and membership over {@code converter_campus.org_code}, and their
 * row-value forms over {@code converter_campus_term}'s composite key
 * {@code (org_code, term_no)}, reached through a {@code @nodeId} filter.
 *
 * <p>What each case proves is that PostgreSQL accepted the statement: there is no
 * {@code org_code_domain = character varying} operator, so a value that reached the database at
 * the converter's user type fails the request outright rather than answering the wrong rows. The
 * returned rows are therefore the whole assertion. This is the only enforcer of the bind's type:
 * {@link ConditionSqlBaselineTest} renders every bind as {@code ?} and so can pin the statement
 * text while saying nothing about what a bind carries.
 *
 * <p>The row-shape cases read their ids off the {@code converterCampusTerms} listing rather than
 * minting them with the generated {@code NodeIdEncoder}, because minting one in the test would
 * re-encode the very converted value the case is verifying.
 */
@ExecutionTier
@SuppressWarnings("unchecked")
class ConverterFilterExecutionTest {

    static PostgreSQLContainer postgres;
    static DSLContext dsl;
    static GraphQL graphql;

    @BeforeAll
    static void startDatabase() {
        var localUrl = System.getProperty("test.db.url");
        if (localUrl != null) {
            var user = System.getProperty("test.db.username", "postgres");
            var pass = System.getProperty("test.db.password", "postgres");
            dsl = DSL.using(localUrl, user, pass);
        } else {
            postgres = new PostgreSQLContainer("postgres:18-alpine").withInitScript("init.sql");
            postgres.start();
            dsl = DSL.using(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        }
        graphql = Graphitron.newGraphQL().build();
    }

    @AfterAll
    static void stopDatabase() {
        if (postgres != null) postgres.stop();
    }

    private Map<String, Object> execute(String query) {
        var input = Graphitron.newExecutionInput(dsl, "{}", "test-user").query(query).build();
        var result = graphql.execute(input);
        assertThat(result.getErrors()).as("graphql errors: " + result.getErrors()).isEmpty();
        return result.getData();
    }

    private List<Map<String, Object>> rows(String query, String root) {
        return (List<Map<String, Object>>) execute(query).get(root);
    }

    @Test
    void singleColumnEquality_bindsAtTheDomainType_answersTheOrgsCampuses() {
        // BodyParam.Eq -> `campus.ORG_CODE.eq(orgCode)`. NTNU's two campuses, in primary-key order.
        var campuses = rows(
            "{ converterCampusesByOrgCode(orgCode: \"1120\") { campusName } }",
            "converterCampusesByOrgCode");
        assertThat(campuses).extracting(row -> row.get("campusName"))
            .containsExactly("Trondheim", "Gjøvik");
    }

    @Test
    void singleColumnMembership_bindsEveryElement_answersTheUnionAndTheSingleton() {
        // BodyParam.In -> `campus.ORG_CODE.in(orgCodes)`. Both orgs, then one, so a list whose
        // elements bound at the wrong type cannot pass by answering everything.
        var both = rows(
            "{ converterCampusesByOrgCodes(orgCodes: [\"186\", \"1120\"]) { campusName } }",
            "converterCampusesByOrgCodes");
        assertThat(both).extracting(row -> row.get("campusName"))
            .containsExactly("Tromsø", "Trondheim", "Gjøvik");

        var uit = rows(
            "{ converterCampusesByOrgCodes(orgCodes: [\"186\"]) { campusName } }",
            "converterCampusesByOrgCodes");
        assertThat(uit).extracting(row -> row.get("campusName")).containsExactly("Tromsø");
    }

    @Test
    void rowEquality_bindsBothCellsOfTheCompositeKey_answersTheOneTerm() {
        // BodyParam.RowEq -> `DSL.row(term.ORG_CODE, term.TERM_NO).eq(decoded)`, where the decoded
        // Row2's first cell is the converted org_code.
        String springNtnu = idOfTerm("Spring NTNU");
        var terms = rows(
            "{ converterCampusTermByNodeId(filter: {id: \"" + springNtnu + "\"}) { termName } }",
            "converterCampusTermByNodeId");
        assertThat(terms).extracting(row -> row.get("termName")).containsExactly("Spring NTNU");
    }

    @Test
    void rowMembership_bindsEveryRowsCells_answersExactlyThoseTerms() {
        // BodyParam.RowIn -> `DSL.row(term.ORG_CODE, term.TERM_NO).in(decodedRows)`. Two of the
        // three rows, spanning both orgs, so a filter that fell through would over-answer.
        String autumnUit = idOfTerm("Autumn UiT");
        String autumnNtnu = idOfTerm("Autumn NTNU");
        var terms = rows(
            "{ converterCampusTermsByNodeIds(filter: {ids: [\"" + autumnUit + "\", \"" + autumnNtnu
                + "\"]}) { termName } }",
            "converterCampusTermsByNodeIds");
        assertThat(terms).extracting(row -> row.get("termName"))
            .containsExactlyInAnyOrder("Autumn UiT", "Autumn NTNU");
    }

    /** The node id the server minted for the named term, read off the plain listing root. */
    private String idOfTerm(String termName) {
        var terms = rows("{ converterCampusTerms { id termName } }", "converterCampusTerms");
        return terms.stream()
            .filter(row -> termName.equals(row.get("termName")))
            .map(row -> (String) row.get("id"))
            .findFirst()
            .orElseThrow(() -> new AssertionError("no converter_campus_term row named " + termName));
    }
}
