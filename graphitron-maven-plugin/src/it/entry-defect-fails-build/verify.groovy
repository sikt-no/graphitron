// Verifies the entry-defect-fails-build IT: a schema the store refuses fails generate with the
// stored defect's message, code and position, and the walk never adds its own account of it.
def log = new File(basedir, "build.log").text
def authorErrors = log.readLines().findAll { it.contains("Author error") }

assert log.contains("[TABLE_UNRESOLVED]") :
    "Expected the stored defect's code in build.log but got:\n${authorErrors.join('\n')}"

assert log.contains("A @table names a table the catalog does not hold") :
    "Expected the defect type's statement in build.log but got:\n${authorErrors.join('\n')}"

assert authorErrors.any { it.contains("schema.graphqls:5:") } :
    "Expected the defect at the @table's line in schema.graphqls but got:\n${authorErrors.join('\n')}"

assert authorErrors.every { it =~ /\[[A-Z_]+\]\s*$/ } :
    "Expected only stored defects, each ending in its code, and nothing from the walk but got:\n${authorErrors.join('\n')}"
