// A second jOOQ that a plugin-block entry carries transitively is refused before the fact store
// opens, naming the stray jar and the entry that brought it, whether or not this Maven records
// dependency trails for plugin artifacts.
def log = new File(basedir, "build.log").text

assert log.contains("graphitron runs on org.jooq:jooq:") :
    "Expected the realm check's two-jar refusal in build.log but got:\n${log}"
assert log.contains("also holds org.example.it:second-jooq:1.0 (brought in by org.example.it:second-jooq-carrier, declared under <plugin><dependencies> for graphitron-maven-plugin)") :
    "Expected the stray jar and the plugin-block entry that carries it to be named but got:\n${log}"
assert log.contains("Move org.example.it:second-jooq-carrier from the plugin's <dependencies> to the module's own <dependencies>") :
    "Expected the remedy to name the entry to move but got:\n${log}"
assert log.contains("exclude org.example.it:second-jooq from it") :
    "Expected the remedy to name the jar to exclude but got:\n${log}"
assert !new File(basedir, "target/graphitron-model").exists() :
    "The store must not have been opened: the realm check runs before it"
