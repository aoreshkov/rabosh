plugins {
    // Not applied at the root — declared so each plugin's version is resolved once
    // and shared by every module that does apply it.
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.dokka) apply false
    alias(libs.plugins.benchmark) apply false

    // Applied: a Central deployment is one archive over every published module, so it is assembled
    // and checked from the only project that can see all of them. The aggregated API documentation
    // is one site over the same set, for the same reason — and so is the stability-tier audit, since
    // a type marked experimental in one module leaks through a signature in another's dump.
    id("rabosh.central-bundle")
    id("rabosh.api-docs")
    id("rabosh.api-tiers")
}
