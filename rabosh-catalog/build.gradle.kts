plugins {
    id("rabosh.kotlin-library")
}

description = "Schema inference: per-segment path sketches, cardinality estimation, merged collection model."

dependencies {
    api(project(":rabosh-core"))

    testImplementation(project(":rabosh-testkit"))
}
