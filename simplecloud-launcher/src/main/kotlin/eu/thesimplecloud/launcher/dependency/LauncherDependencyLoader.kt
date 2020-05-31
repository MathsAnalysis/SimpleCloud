package eu.thesimplecloud.launcher.dependency

import eu.thesimplecloud.api.depedency.Dependency

class LauncherDependencyLoader {

    fun loadLauncherDependencies() {
        val dependencyLoader = DependencyLoader.INSTANCE
        dependencyLoader.addRepositories("https://repo.maven.apache.org/maven2/", "https://repo.thesimplecloud.eu/artifactory/gradle-dev-local/")
        dependencyLoader.addDependencies(
                Dependency("org.jline", "jline", "3.14.0"),
                Dependency("org.litote.kmongo", "kmongo", "3.11.2"),
                Dependency("commons-io", "commons-io", "2.6"),
                Dependency("org.slf4j", "slf4j-simple", "1.7.10"),
                Dependency("com.google.guava", "guava", "21.0"),
                Dependency("org.jetbrains.kotlinx", "kotlinx-coroutines-core", "1.3.5"),
                Dependency("com.google.code.gson", "gson", "2.8.6"),
                Dependency("io.netty", "netty-all", "4.1.49.Final"),
                Dependency("com.github.ajalt", "clikt", "2.2.0"))
        dependencyLoader.installDependencies()
    }

}