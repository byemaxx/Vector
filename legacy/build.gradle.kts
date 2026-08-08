plugins { alias(libs.plugins.agp.lib) }

android {
    namespace = "org.matrix.vector.legacy"

    androidResources { enable = false }

    defaultConfig { consumerProguardFiles("consumer-rules.pro") }
}

dependencies {
    api(projects.xposed)
    implementation(projects.external.apache)
    implementation(projects.hiddenapi.bridge)
    implementation(projects.services.daemonService)
    compileOnly(libs.androidx.annotation)
    compileOnly(projects.hiddenapi.stubs)
}

/**
 * Lightweight guard for the release-only XResources/R8 regression fixed upstream in 73eba3a.
 *
 * Upstream inspects the optimized dex with dexlib2. Vector-SR deliberately keeps its older build
 * toolchain and adds no build-only dependency here, so this source guard rejects the constructs
 * that caused R8 to manufacture/merge synthetic functional classes carrying an XResources
 * reference. It is intentionally narrower than upstream's post-R8 verifier and is documented as a
 * toolchain adaptation rather than a source-identical backport.
 */
val checkXResourcesIsolationRelease by tasks.registering {
    group = "verification"
    description = "Reject XResources constructs known to leak through R8 synthetic class merging"

    doLast {
        val sourceFile = file("src/main/java/android/content/res/XResources.java")
        check(sourceFile.isFile) { "Missing ${sourceFile.path}" }

        // Comments contain examples of the forbidden constructs; remove them before scanning code.
        val code =
            sourceFile
                .readText()
                .replace(Regex("(?s)/\\*.*?\\*/"), "")
                .replace(Regex("//[^\\r\\n]*"), "")

        val forbidden =
            listOf(
                "->" to "lambda expression",
                ".computeIfAbsent(" to "Map.computeIfAbsent",
                ".computeIfPresent(" to "Map.computeIfPresent",
                ".compute(" to "Map.compute",
                ".merge(" to "Map.merge",
            )
        val found = forbidden.filter { (token, _) -> token in code }
        check(found.isEmpty()) {
            "XResources must not create functional/synthetic classes that R8 can merge outside " +
                "resource hooking. Found: ${found.joinToString { it.second }}"
        }
    }
}

// AGP creates preReleaseBuild lazily; configureEach also catches a task added after this script.
tasks.matching { it.name == "preReleaseBuild" }.configureEach {
    dependsOn(checkXResourcesIsolationRelease)
}