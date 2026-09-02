// Verifies the capture-only IT: the goal filled a store, refused nothing, and wrote no code.

// The store the run left behind, under the home the pom pinned. The database file sits inside a
// stamped subdirectory (the DDL hash and generator version name it), so this looks for the file
// rather than for a path.
def storeHome = new File(basedir, "target/model-store")
assert storeHome.isDirectory() :
    "Expected the capture run to create its store home: ${storeHome}"

def databases = []
storeHome.eachFileRecurse { if (it.name == "store.mv.db") databases << it }
assert databases.size() == 1 :
    "Expected exactly one store database under ${storeHome}, found ${databases}"
assert databases[0].length() > 0 :
    "Expected the store database to hold something: ${databases[0]}"

// Nothing emitted. The goal runs no plan, no renderers and no writer, so the generated-source
// root the generate goal owns must not exist at all.
def generatedRoot = new File(basedir, "target/generated-sources/graphitron")
assert !generatedRoot.exists() :
    "A capture run must emit nothing, but ${generatedRoot} exists"

def buildLog = new File(basedir, "build.log").text

// The run reports where it put what it captured, which is the one thing a consumer needs from it.
assert buildLog.contains("Captured graph 'capture-only' into") :
    "Expected the goal to report the graph and store it captured into"

// And it did not pronounce on the schema. This is the property the goal exists for: the same
// document fails `graphitron:validate`, and a command whose job is to produce a store does not
// refuse because it disliked the input.
assert !buildLog.contains("GraphQL schema validation failed") :
    "A capture run must not fail over the schema it read"
assert !buildLog.contains("no_such_fk") :
    "A capture run pronounces no verdict, so it has no rejection to render"
