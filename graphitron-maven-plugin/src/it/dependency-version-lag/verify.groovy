// The positive end of the dependency-currency nudge, and the one tier that exercises both sides
// against real Maven resolution: the observed version off the consumer's mediated dependency graph
// and the reference version off ${plugin} / PluginDescriptor.getArtifacts(). Every other tier hands
// the decision plain strings, so this is what would catch the reference route being dead in
// production while every unit test stayed green.
def buildLog = new File(basedir, "build.log").text

assert buildLog.contains("graphql-java-version-lag") :
    "A consumer a minor line behind on graphql-java must be nudged, and the message must carry the rule id"
assert buildLog.contains("24.2") :
    "The nudge must name the version the consumer actually resolved"
assert buildLog.contains("com.graphql-java:graphql-java") :
    "The nudge must name the coordinate to bump"

// The advisory is not a gate: it rides the warning channel and the build still succeeds.
def wiring = new File(basedir, "target/generated-sources/graphitron/no/sikt/it/generated/Graphitron.java")
assert wiring.exists() :
    "Generation must proceed normally; the version nudge is advisory and fails nothing"

// jOOQ arrives at graphitron's own line through graphitron-sakila-db, and a current line is silent.
assert !buildLog.contains("jooq-version-lag") :
    "A consumer on graphitron's own jOOQ line must not be nudged about it"
