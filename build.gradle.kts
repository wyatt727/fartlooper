// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.kapt) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.detekt)
    alias(libs.plugins.ktlint) apply false
    alias(libs.plugins.dependency.check) apply false
    jacoco
}

// Enable dependency locking for better cache keys
allprojects {
    dependencyLocking {
        lockAllConfigurations()
    }
}

// Configure detekt for root project
detekt {
    buildUponDefaultConfig = true
    allRules = false
    config.setFrom("$projectDir/config/detekt/detekt.yml")
    baseline = file("$projectDir/config/detekt/baseline.xml")
}

// CI/CD FINDING: Apply ktlint to all subprojects except vendor
// Vendor submodule exclusion prevents build conflicts with third-party dependencies
// that use incompatible repository configurations (BintrayJCenter deprecation)
subprojects {
    if (project.path.startsWith(":vendor")) {
        return@subprojects
    }

    // STATIC ANALYSIS FINDING: Plugin application order matters for proper task dependencies
    // ktlint must be applied before detekt to ensure proper classpath resolution
    apply(plugin = "org.jlleitschuh.gradle.ktlint")
    apply(plugin = "io.gitlab.arturbosch.detekt")
    apply(plugin = "org.owasp.dependencycheck")
    apply(plugin = "jacoco")

    // KTLINT CONFIGURATION FINDING: Comprehensive ktlint setup for Android projects
    configure<org.jlleitschuh.gradle.ktlint.KtlintExtension> {
        // KOTLIN VERSION FINDING: ktlint 0.50.0 provides best compatibility with Kotlin 1.9.22
        // Newer versions can cause compatibility issues with Compose compiler
        version.set("0.50.0")
        debug.set(false)
        verbose.set(true) // DEBUGGING FINDING: Verbose output essential for CI troubleshooting
        android.set(true) // ANDROID FINDING: Android-specific rules prevent false positives
        outputToConsole.set(true) // CI FINDING: Console output required for GitHub Actions logs
        ignoreFailures.set(false) // QUALITY GATE FINDING: Fail builds on violations to maintain code quality
        enableExperimentalRules.set(false) // STABILITY FINDING: Experimental rules cause inconsistent behavior

        // FILE FILTERING FINDING: Exclude generated code and vendor dependencies
        // Generated code exclusion prevents violations in auto-generated files
        // Build directory exclusion improves performance by avoiding compiled artifacts
        filter {
            exclude("**/generated/**")
            exclude("**/build/**")
            exclude("**/vendor/**")
        }

        // REPORTING FINDING: Multiple report formats support different CI/CD integrations
        // PLAIN: Human-readable console output for developer experience
        // CHECKSTYLE: Jenkins/GitHub Actions integration for inline PR comments
        // SARIF: GitHub Security tab integration for centralized violation tracking
        reporters {
            reporter(org.jlleitschuh.gradle.ktlint.reporter.ReporterType.PLAIN)
            reporter(org.jlleitschuh.gradle.ktlint.reporter.ReporterType.CHECKSTYLE)
            reporter(org.jlleitschuh.gradle.ktlint.reporter.ReporterType.SARIF)
        }
    }

    // DETEKT CONFIGURATION FINDING: Balanced rule enforcement for Android projects
    configure<io.gitlab.arturbosch.detekt.extensions.DetektExtension> {
        // RULE CONFIGURATION FINDING: Build upon default config provides sensible baseline
        // allRules=false prevents overwhelming developers with hundreds of violations
        buildUponDefaultConfig = true
        allRules = false

        // CONFIG FILE FINDING: Central configuration enables consistent rules across modules
        // detekt.yml contains carefully tuned rules for Android/Compose development
        config.setFrom("$rootDir/config/detekt/detekt.yml")

        // BASELINE FINDING: Baseline file allows gradual adoption of detekt rules
        // Existing violations are grandfathered while new code must meet standards
        baseline = file("$rootDir/config/detekt/baseline.xml")

        // SOURCE DIRECTORY FINDING: Explicit source specification ensures proper scanning
        // Both Java and Kotlin sources included for comprehensive analysis
        source.setFrom(
            "src/main/java",
            "src/main/kotlin"
        )
    }

    // TASK EXCLUSION FINDING: Exclude vendor directories from detekt tasks
    // Vendor exclusion prevents analysis of third-party code that doesn't meet our standards
    // Build/generated exclusions improve performance and avoid false positives
    tasks.withType<io.gitlab.arturbosch.detekt.Detekt>().configureEach {
        exclude("**/vendor/**", "**/build/**", "**/generated/**")
    }

    // TASK DEPENDENCY FINDING: Wire static analysis into each module's check task
    // afterEvaluate ensures tasks exist before creating dependencies
    // This makes `./gradlew check` run both ktlint and detekt automatically
    afterEvaluate {
        tasks.findByName("check")?.dependsOn("ktlintCheck", "detekt")

        // COVERAGE FINDING: Configure Jacoco for test coverage
        // JaCoCo 0.8.8 provides stable coverage collection for Kotlin/Android projects
        // Essential for meeting PDR NFR-02 requirement of ≥60% test coverage
        extensions.findByType<JacocoPluginExtension>()?.apply {
            toolVersion = "0.8.8"
        }

        // Add Jacoco test report task for Android modules
        if (plugins.hasPlugin("com.android.library") || plugins.hasPlugin("com.android.application")) {
            tasks.register<JacocoReport>("jacocoTestReport") {
                dependsOn("testDebugUnitTest")
                reports {
                    xml.required.set(true)
                    html.required.set(true)
                }

                val fileFilter = listOf(
                    "**/R.class",
                    "**/R\$*.class",
                    "**/BuildConfig.*",
                    "**/Manifest*.*",
                    "**/*Test*.*",
                    "android/**/*.*",
                    "**/databinding/**",
                    "**/android/databinding/**",
                    "**/androidx/databinding/**",
                    "**/di/**",
                    "**/*_MembersInjector.class",
                    "**/*_Factory.class",
                    "**/*_Provide*Factory.class",
                    "**/*Extensions*.*"
                )

                val debugTree = fileTree("${layout.buildDirectory.get()}/intermediates/javac/debug/classes") {
                    exclude(fileFilter)
                }
                val mainSrc = "${projectDir}/src/main/java"

                sourceDirectories.setFrom(files(mainSrc))
                classDirectories.setFrom(files(debugTree))
                executionData.setFrom(fileTree(layout.buildDirectory.get()) {
                    include("**/*.exec", "**/*.ec")
                })
            }

            // Add coverage verification task
            tasks.register<JacocoCoverageVerification>("jacocoTestCoverageVerification") {
                dependsOn("jacocoTestReport")
                violationRules {
                    rule {
                        limit {
                            minimum = "0.60".toBigDecimal() // 60% minimum coverage per PDR NFR-02
                        }
                    }
                }
            }
        }
    }
}

tasks.register("clean", Delete::class) {
    delete(rootProject.layout.buildDirectory)
}
