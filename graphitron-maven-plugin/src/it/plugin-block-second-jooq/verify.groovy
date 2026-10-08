// A jar providing org/jooq/Constants.class beside graphitron's own jOOQ in the plugin realm is
// refused before the fact store opens, naming the stray jar and the pom entry that brought it.
def log = new File(basedir, "build.log").text

assert log.contains("graphitron runs on org.jooq:jooq:") :
    "Expected the realm check's two-jar refusal in build.log but got:\n${log}"
assert log.contains("also holds org.example.it:second-jooq:1.0 (declared under <plugin><dependencies> for graphitron-maven-plugin)") :
    "Expected the stray jar and its plugin-block entry to be named but got:\n${log}"
assert log.contains("Remove org.example.it:second-jooq from the plugin's <dependencies>") :
    "Expected the remedy to name the entry to remove but got:\n${log}"
assert !new File(basedir, "target/graphitron-model").exists() :
    "The store must not have been opened: the realm check runs before it"
