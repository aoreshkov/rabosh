plugins {
    id("rabosh.kotlin-library")
}

description = "Public facade: one object owning the store, the schema catalog and the index catalog."

dependencies {
    api(project(":rabosh-query"))

    testImplementation(project(":rabosh-testkit"))
    testImplementation(libs.kotlinx.serialization.json)
}
