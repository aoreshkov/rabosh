plugins {
    id("rabosh.kotlin-library")
}

description = "Retroactive index sidecars: compressed bitmap, inverted path indexes, shredded typed columns."

dependencies {
    api(project(":rabosh-catalog"))

    testImplementation(project(":rabosh-testkit"))
}
