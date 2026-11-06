// A consumer resolving a jOOQ below 3.20 is refused by graphitron:generate before anything is
// written, in its own coordinate and version, rather than left to fail in javac on a generated
// file. Real Maven resolution is the point: the version is read off the mediated dependency graph.
def log = new File(basedir, "build.log").text

assert log.contains("Graphitron's generated code needs org.jooq:jooq 3.20 or later, and this build resolves 3.19.24.") :
    "Expected the jOOQ minimum refusal naming the resolved version but got:\n${log}"
assert log.contains("regenerate your jOOQ catalog") :
    "Expected the refusal to say what to change but got:\n${log}"
assert !new File(basedir, "target/generated-sources/graphitron").exists() :
    "Nothing may be generated for a build below the jOOQ minimum"
assert !new File(basedir, "target/graphitron-model").exists() :
    "The store must not have been opened: the check runs before it"
