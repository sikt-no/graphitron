// Verifies the basic-generate IT: the plugin ran and produced generated sources.
def generatedRoot = new File(basedir, "target/generated-sources/graphitron/no/sikt/it/generated")
assert generatedRoot.isDirectory() :
    "Expected generated-sources directory to exist: ${generatedRoot}"

def wiring = new File(generatedRoot, "Graphitron.java")
assert wiring.exists() :
    "Expected Graphitron.java to be generated under ${generatedRoot}"
