package no.sikt.graphitron.rewrite.maven;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The realm check's decision, over hand-built rows and loader evidence. Maven's own realm
 * construction (which plugin-block entries land, in what order, with what trails) is what no
 * in-process test can reach; the {@code plugin-block-second-jooq} and
 * {@code plugin-block-mediated-jooq} invoker ITs carry that. The compiled-against version is a
 * parameter here, so none of these cases depends on the constant javac inlines.
 */
class PluginRealmTest {

    private static final String OURS = "3.20.11";
    private static final String PLUGIN = "no.sikt:graphitron-maven-plugin:maven-plugin:10-SNAPSHOT";

    private static final Path OUR_JAR = Path.of("/repo/org/jooq/jooq/3.20.11/jooq-3.20.11.jar");
    private static final Path PRO_JAR = Path.of("/repo/org/jooq/pro/jooq/3.19.18/jooq-3.19.18.jar");

    private static final PluginRealm.Row OUR_ROW = new PluginRealm.Row("org.jooq", "jooq", OURS, OUR_JAR,
        List.of(PLUGIN, "no.sikt:graphitron:jar:10-SNAPSHOT", "no.sikt:graphitron-model:jar:10-SNAPSHOT",
            "org.jooq:jooq:jar:3.20.11"));

    @Test
    void oneJooqAtOurVersionPasses() {
        var realm = new PluginRealm(List.of(OUR_ROW), List.of(), "graphitron-maven-plugin");

        assertThat(realm.refusal(List.of(OUR_JAR), OURS, OURS)).isEmpty();
    }

    @Test
    void aSecondJarNamesBothAndThePluginBlockEntryItsTrailLeadsTo() {
        var pro = new PluginRealm.Row("org.jooq.pro", "jooq", "3.19.18", PRO_JAR,
            List.of(PLUGIN, "no.fellesstudentsystem:sis-service:jar:1.0", "org.jooq.pro:jooq:jar:3.19.18"));
        var realm = new PluginRealm(List.of(pro, OUR_ROW), List.of("no.fellesstudentsystem:sis-service"),
            "graphitron-maven-plugin");

        assertThat(realm.refusal(List.of(PRO_JAR, OUR_JAR), "3.19.18", OURS)).hasValue(
            "graphitron runs on org.jooq:jooq:3.20.11, but its plugin classloader also holds"
                + " org.jooq.pro:jooq:3.19.18 (brought in by no.fellesstudentsystem:sis-service,"
                + " declared under <plugin><dependencies> for graphitron-maven-plugin). Two jOOQs in"
                + " the plugin classloader make graphitron run on whichever answers first.\n\n"
                + "Move no.fellesstudentsystem:sis-service from the plugin's <dependencies> to the"
                + " module's own <dependencies>; graphitron already reads the module's compile"
                + " classpath. If it must stay in the plugin block, exclude org.jooq.pro:jooq from it.");
    }

    @Test
    void aSecondJarWithNoTrailListsTheConsumerAddedEntriesAsCandidates() {
        var pro = new PluginRealm.Row("org.jooq.pro", "jooq", "3.19.18", PRO_JAR, null);
        var realm = new PluginRealm(List.of(pro, OUR_ROW),
            List.of("no.fellesstudentsystem:sis-service", "com.example:other"), "graphitron-maven-plugin");

        assertThat(realm.refusal(List.of(PRO_JAR, OUR_JAR), "3.19.18", OURS)).hasValueSatisfying(message -> {
            assertThat(message)
                .contains("also holds org.jooq.pro:jooq:3.19.18 (brought in by one of the entries declared"
                    + " under <plugin><dependencies> for graphitron-maven-plugin:"
                    + " no.fellesstudentsystem:sis-service, com.example:other)")
                .contains("Move the entry that brings it from the plugin's <dependencies>")
                .contains("exclude org.jooq.pro:jooq from it");
            assertThat(message).doesNotContain(OUR_JAR.toString());
        });
    }

    @Test
    void aSecondJarNoRowAccountsForIsNamedByItsPath() {
        var fat = Path.of("/repo/com/example/fat/1.0/fat-1.0.jar");
        var realm = new PluginRealm(List.of(OUR_ROW), List.of("com.example:fat"), "graphitron-maven-plugin");

        assertThat(realm.refusal(List.of(OUR_JAR, fat), OURS, OURS))
            .hasValueSatisfying(message -> assertThat(message).contains("also holds " + fat));
    }

    @Test
    void oneJarAtAnotherVersionNamesBothVersionsAndTheEntryToRemove() {
        var other = Path.of("/repo/org/jooq/jooq/3.19.99-it/jooq-3.19.99-it.jar");
        var mediated = new PluginRealm.Row("org.jooq", "jooq", "3.19.99-it", other,
            List.of(PLUGIN, "org.jooq:jooq:jar:3.19.99-it"));
        var realm = new PluginRealm(List.of(mediated), List.of("org.jooq:jooq"), "graphitron-maven-plugin");

        assertThat(realm.refusal(List.of(other), "3.19.99", OURS)).hasValue(
            "graphitron was compiled against org.jooq:jooq:3.20.11, but its plugin classloader holds"
                + " org.jooq:jooq:3.19.99-it (declared under <plugin><dependencies> for"
                + " graphitron-maven-plugin) in its place, which reports jOOQ 3.19.99. graphitron must"
                + " run on the jOOQ it was compiled against.\n\n"
                + "Remove org.jooq:jooq from the plugin's <dependencies>; graphitron must run on its own"
                + " jOOQ, and already reads the module's compile classpath for your code.");
    }

    @Test
    void noJooqAtAllIsNotJudged() {
        assertThat(PluginRealm.empty().refusal(List.of(), null, OURS)).isEmpty();
    }

    @Test
    void theTrailsSecondElementIsThePluginsDirectDependency() {
        assertThat(OUR_ROW.broughtInBy()).hasValue("no.sikt:graphitron");
        assertThat(new PluginRealm.Row("g", "a", "1", null, List.of(PLUGIN)).broughtInBy()).isEmpty();
    }
}
