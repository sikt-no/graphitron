// Verifies the basic-generate IT: the plugin ran and produced generated sources.
def generatedRoot = new File(basedir, "target/generated-sources/graphitron/no/sikt/it/generated")
assert generatedRoot.isDirectory() :
    "Expected generated-sources directory to exist: ${generatedRoot}"

def wiring = new File(generatedRoot, "Graphitron.java")
assert wiring.exists() :
    "Expected Graphitron.java to be generated under ${generatedRoot}"

// The dependency-currency nudge must stay quiet here, and this is the only tier that runs it
// against real Maven resolution and a real ${plugin} realm rather than hand-built artifacts.
// A false positive is the whole failure mode for an advisory that fires by design: it erodes
// trust in every other warning sharing the channel. This project exercises both silence shapes
// at once, because it carries jOOQ at the reactor's own version (transitively through
// graphitron-sakila-db, so "at current") and names graphql-java nowhere ("coordinate absent").
def buildLog = new File(basedir, "build.log").text
assert !buildLog.contains("jooq-version-lag") :
    "An up-to-date consumer must not be nudged about jOOQ"
assert !buildLog.contains("graphql-java-version-lag") :
    "A consumer that does not carry graphql-java must not be nudged about it"
