import com.android.build.api.dsl.ApplicationExtension
import com.android.ide.common.signing.KeystoreHelper
import java.io.PrintStream
import java.util.UUID

val defaultManagerPackageName: String by rootProject.extra
val injectedPackageName: String by rootProject.extra
val injectedPackageUid: Int by rootProject.extra
val versionCodeProvider: Provider<String> by rootProject.extra
val versionNameProvider: Provider<String> by rootProject.extra

plugins {
  alias(libs.plugins.agp.app)
  alias(libs.plugins.kotlin)
  alias(libs.plugins.ktfmt)
}

android {
  defaultConfig {
    buildConfigField(
        "String",
        "DEFAULT_MANAGER_PACKAGE_NAME",
        """"$defaultManagerPackageName"""",
    )
    // Keep the public libxposed framework name compatible with LSPosed 2.0.
    // Some module companion apps check getFrameworkName() == "LSPosed".
    buildConfigField("String", "FRAMEWORK_NAME", """"LSPosed"""")
    buildConfigField("String", "MANAGER_INJECTED_PKG_NAME", """"$injectedPackageName"""")
    buildConfigField("int", "MANAGER_INJECTED_UID", """$injectedPackageUid""")
    // Keep version fields out of Java's ConstantValue attributes. Kotlin otherwise inlines them
    // into daemon classes, allowing an incremental build to retain a previous Git-derived version
    // even after BuildConfig.java and the APK manifest have been regenerated.
    buildConfigField(
        "String",
        "VERSION_NAME",
        "String.valueOf(\"${versionNameProvider.get()}\")",
    )
    buildConfigField(
        "long",
        "VERSION_CODE",
        "Long.parseLong(\"${versionCodeProvider.get()}\")",
    )

    val cliToken = UUID.randomUUID()
    // Inject the MSB and LSB as Long constants
    buildConfigField("Long", "CLI_TOKEN_MSB", "${cliToken.mostSignificantBits}L")
    buildConfigField("Long", "CLI_TOKEN_LSB", "${cliToken.leastSignificantBits}L")
  }

  buildTypes {
    all { externalNativeBuild { cmake { arguments += "-DANDROID_ALLOW_UNDEFINED_SYMBOLS=true" } } }
    release {
      isMinifyEnabled = true
      proguardFiles("proguard-rules.pro")
    }
  }

  externalNativeBuild { cmake { path("src/main/jni/CMakeLists.txt") } }

  namespace = "org.matrix.vector.daemon"
}

android.applicationVariants.all {
  val variantCapped = name.replaceFirstChar { it.uppercase() }
  val variantLowered = name.lowercase()

  val outSrcDir = layout.buildDirectory.dir("generated/source/signInfo/${variantLowered}").get()
  val signInfoTask =
      tasks.register("generate${variantCapped}SignInfo") {
        dependsOn(":app:validateSigning${variantCapped}")
        val sign =
            rootProject
                .project(":app")
                .extensions
                .getByType(ApplicationExtension::class.java)
                .buildTypes
                .named(variantLowered)
                .get()
                .signingConfig
        val outSrc = file("$outSrcDir/org/matrix/vector/daemon/utils/SignInfo.kt")
        // The generated certificate is compiled into the daemon and must always match the
        // manager APK produced by :app. Without these inputs Gradle can reuse SignInfo.kt after
        // the signing key changes, causing the daemon to reject its own manager.apk at runtime.
        sign?.storeFile?.let { inputs.file(it).withPathSensitivity(PathSensitivity.NONE) }
        inputs.property("signingStoreType", sign?.storeType ?: "")
        inputs.property("signingKeyAlias", sign?.keyAlias ?: "")
        outputs.file(outSrc)
        doLast {
          outSrc.parentFile.mkdirs()
          val certificateInfo =
              KeystoreHelper.getCertificateInfo(
                  sign?.storeType,
                  sign?.storeFile,
                  sign?.storePassword,
                  sign?.keyPassword,
                  sign?.keyAlias,
              )

          PrintStream(outSrc)
              .print(
                  """
                |package org.matrix.vector.daemon.utils
                |
                |object SignInfo {
                |    @JvmField
                |    val CERTIFICATE = byteArrayOf(${
                    certificateInfo.certificate.encoded.joinToString(",")
                })
                |}"""
                      .trimMargin())
        }
      }
  // registeoJavaGeneratingTask(signInfoTask, outSrcDir.asFile)

  kotlin.sourceSets.getByName(variantLowered) { kotlin.srcDir(signInfoTask.map { outSrcDir }) }
}

dependencies {
  implementation(libs.agp.apksig)
  implementation(libs.gson)
  implementation(libs.picocli)
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.kotlinx.coroutines.core)
  implementation(projects.external.apache)
  implementation(projects.hiddenapi.bridge)
  implementation(projects.services.daemonService)
  implementation(projects.services.managerService)
  compileOnly(libs.androidx.annotation)
  compileOnly(projects.hiddenapi.stubs)
}
