plugins {
    id("rabosh.kotlin-library")
}

description = "LSM storage core: WAL, memtable, SSTable, manifest, compaction, MVCC snapshots."

dependencies {
    api(project(":rabosh-variant"))

    testImplementation(project(":rabosh-testkit"))
}
