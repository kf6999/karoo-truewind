/*
 * Copyright 2026 TrueWind contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Pure Kotlin/JVM library: no Android and no karoo-ext dependency (SPEC section 3, rule 1).
plugins {
    alias(libs.plugins.jetbrains.kotlin.jvm)
}

java {
    // Match the app module's bytecode level so inline functions can be used from Android code.
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_11)
    }
}

// WP11 replay harness CLI (TESTING 3). A separate source set so the FIT SDK and the JSON library
// never reach the app's classpath; the pure parts (metrics, pipelines, seams) live in main under
// io.github.kf6999.truewind.core.replay.
val replay: SourceSet by sourceSets.creating {
    compileClasspath += sourceSets.main.get().output
    runtimeClasspath += sourceSets.main.get().output
}
val replayImplementation: Configuration by configurations.getting {
    extendsFrom(configurations.implementation.get())
}
sourceSets.test {
    compileClasspath += replay.output
    runtimeClasspath += replay.output
}
configurations.testImplementation { extendsFrom(replayImplementation) }

dependencies {
    // JSON tree parsing for provider responses (WP2/WP3); no serialization compiler plugin needed.
    implementation(libs.kotlinx.serialization.json)
    replayImplementation(libs.garmin.fit)
    testImplementation(kotlin("test"))
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.core)
}

tasks.test {
    useJUnit()
}

// ./gradlew :core:replay --args="--corpus corpus/fixtures --out build/replay/metrics.json"
// Relative paths resolve against the repository root.
tasks.register<JavaExec>("replay") {
    group = "verification"
    description = "Runs the TrueWind replay harness (TESTING.md section 3)."
    classpath = replay.runtimeClasspath
    mainClass.set("io.github.kf6999.truewind.core.replay.cli.ReplayCliKt")
    workingDir = rootDir
}
