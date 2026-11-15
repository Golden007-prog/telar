/*
 * The bill of materials of Telar: an app names the version once, in `platform(...)`, and every module
 * of Telar it adds gets that version.
 */
plugins {
    `java-platform`
    id("telar.publishing")
}

/*
 * Every published module is a project named telar-*, so a module that is added later is in the BOM
 * without a change here.
 */
val publishedModules: List<Project> =
    rootProject.subprojects
        .filter { it.path.startsWith(":telar-") && it != project }
        .sortedBy { it.path }

// The publications of a multiplatform module exist once it is configured.
publishedModules.forEach { evaluationDependsOn(it.path) }

/*
 * Each artifact the modules publish, also those of each platform of a multiplatform module, such as
 * telar-core-jvm: Gradle finds those through the module's metadata, but a Maven build names them.
 */
dependencies {
    constraints {
        publishedModules
            .flatMap { module -> module.extensions.getByType<PublishingExtension>().publications.withType<MavenPublication>() }
            .map { "${it.groupId}:${it.artifactId}:${it.version}" }
            .distinct()
            .sorted()
            .forEach { api(it) }
    }
}
