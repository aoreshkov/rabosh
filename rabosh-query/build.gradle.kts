plugins {
    id("rabosh.kotlin-library")
}

description = "Predicate AST, planner, segment pruning and execution."

dependencies {
    api(project(":rabosh-index"))

    testImplementation(project(":rabosh-testkit"))
}
