@file:Suppress("unused") // usages in build scripts are not tracked properly

import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.file.DuplicatesStrategy
import org.gradle.api.tasks.bundling.Zip
import org.gradle.jvm.tasks.Jar
import org.gradle.kotlin.dsl.task
import org.gradle.kotlin.dsl.*

val kotlinEmbeddableRootPackage = "org.jetbrains.kotlin"

val packagesToRelocate =
        listOf( "com.intellij",
                "com.google",
                "com.sampullara",
                "org.apache",
                "org.jdom",
                "org.picocontainer",
                "org.jline",
                "gnu",
                "org.fusesource")

// The shaded compiler "dummy" is used to rewrite dependencies in projects that are used with the embeddable compiler
// on the runtime and use some shaded dependencies from the compiler
// To speed-up rewriting process we want to have this dummy as small as possible.
// But due to the shadow plugin bug (https://github.com/johnrengelman/shadow/issues/262) it is not possible to use
// packagesToRelocate list to for the include list. Therefore the exclude list has to be created.
val packagesToExcludeFromDummy =
        listOf("org/jetbrains/kotlin/**",
               "org/intellij/lang/annotations/**",
               "org/jetbrains/jps/**",
               "META-INF/**",
               "com/sun/jna/**",
               "com/thoughtworks/xstream/**",
               "javaslang/**",
               "*.proto",
               "messages/**",
               "net/sf/cglib/**",
               "one/util/streamex/**",
               "org/iq80/snappy/**",
               "org/jline/**",
               "org/xmlpull/**",
               "*.txt")

private fun ShadowJar.configureEmbeddableCompilerRelocation(withJavaxInject: Boolean = true) {
    relocate("com.google.protobuf", "org.jetbrains.kotlin.protobuf")
    packagesToRelocate.forEach {
        relocate(it, "$kotlinEmbeddableRootPackage.$it")
    }
    if (withJavaxInject) {
        relocate("javax.inject", "$kotlinEmbeddableRootPackage.javax.inject")
    }
    relocate("org.fusesource", "$kotlinEmbeddableRootPackage.org.fusesource") {
        // TODO: remove "it." after #KT-12848 get addressed
        exclude("org.fusesource.jansi.internal.CLibrary")
    }
}

private fun Project.compilerShadowJar(taskName: String, body: ShadowJar.() -> Unit): Jar {

    val compilerJar = configurations.getOrCreate("compilerJar")
    dependencies.add(compilerJar.name, dependencies.project(":kotlin-compiler", configuration = "runtimeJar"))

    return task<ShadowJar>(taskName) {
        destinationDir = File(buildDir, "libs")
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE
        from(compilerJar)
        body()
    }
}

fun Project.embeddableCompiler(taskName: String = "embeddable", body: ShadowJar.() -> Unit = {}): Jar =
        compilerShadowJar(taskName) {
            configureEmbeddableCompilerRelocation()
            body()
        }

fun Project.compilerDummyForDependenciesRewriting(taskName: String = "compilerDummy", body: ShadowJar.() -> Unit = {}): Jar =
        compilerShadowJar(taskName) {
            exclude(packagesToExcludeFromDummy)
            body()
        }

const val COMPILER_DUMMY_JAR_CONFIGURATION_NAME = "compilerDummyJar"

fun Project.compilerDummyJar(task: Jar, body: Jar.() -> Unit = {}) {
    task.body()
    addArtifact(COMPILER_DUMMY_JAR_CONFIGURATION_NAME, task, task)
}

fun Project.embeddableCompilerDummyForDependenciesRewriting(taskName: String = "embeddable", body: Jar.() -> Unit = {}): Jar {
    val compilerDummyJar = configurations.getOrCreate("compilerDummyJar")
    dependencies.add(compilerDummyJar.name,
                     dependencies.project(":kotlin-compiler-embeddable", configuration = COMPILER_DUMMY_JAR_CONFIGURATION_NAME))

    return task<ShadowJar>(taskName) {
        destinationDir = File(buildDir, "libs")
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE
        from(compilerDummyJar)
        configureEmbeddableCompilerRelocation(withJavaxInject = false)
        body()
    }
}

fun Project.rewriteDepsToShadedJar(originalJarTask: Jar, shadowJarTask: Zip, body: Jar.() -> Unit = {}): Jar {
    val originalFiles by lazy {
        val jarContents = zipTree(originalJarTask.outputs.files.singleFile).files
        val basePath = jarContents.find { it.name == "MANIFEST.MF" }?.parentFile?.parentFile ?: throw GradleException("cannot determine the jar root dir")
        jarContents.map { it.relativeTo(basePath).path }.toSet()
    }
    return task<Jar>("rewrittenDepsJar") {
        originalJarTask.apply {
            classifier = "original"
        }
        shadowJarTask.apply {
            dependsOn(originalJarTask)
            from(originalJarTask)// { include("**") }
            classifier = "shadow"
        }
        dependsOn(shadowJarTask)
        from(project.zipTree(shadowJarTask.outputs.files.singleFile)) { include { originalFiles.any { originalFile -> it.file.canonicalPath.endsWith(originalFile) } } }
        body()
    }
}

fun Project.rewriteDepsToShadedCompiler(originalJarTask: Jar, body: Jar.() -> Unit = {}): Jar =
        rewriteDepsToShadedJar(originalJarTask, embeddableCompilerDummyForDependenciesRewriting(), body)
