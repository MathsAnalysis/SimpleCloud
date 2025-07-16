/*
 * MIT License
 *
 * Copyright (C) 2020-2022 The SimpleCloud authors
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated
 * documentation files (the "Software"), to deal in the Software without restriction, including without limitation
 * the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software,
 * and to permit persons to whom the Software is furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED,
 * INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT.
 * IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM,
 * DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE,
 * ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER
 * DEALINGS IN THE SOFTWARE.
 */

package eu.thesimplecloud.loader.dependency

import eu.thesimplecloud.runner.dependency.AdvancedCloudDependency
import java.io.File


class LauncherDependencyLoader {

    fun loadLauncherDependencies(): Set<File> {
        val dependencyLoader = DependencyLoader.INSTANCE
        return dependencyLoader.loadDependencies(
            listOf(
                "https://repo.maven.apache.org/maven2/",
                "https://repo.simplecloud.app/releases/"
            ),
            listOf(
                AdvancedCloudDependency("eu.thesimplecloud.clientserverapi", "clientserverapi", "4.1.18"),
                AdvancedCloudDependency("org.apache.commons", "commons-lang3", "3.18.0"),
                AdvancedCloudDependency("org.slf4j", "slf4j-nop", "2.1.0-alpha1"),
                AdvancedCloudDependency("org.fusesource.jansi", "jansi", "2.4.2"),
                AdvancedCloudDependency("org.jline", "jline", "3.30.4"),
                AdvancedCloudDependency("org.litote.kmongo", "kmongo", "5.2.1"),
                AdvancedCloudDependency("commons-io", "commons-io", "2.19.0"),
                AdvancedCloudDependency("org.slf4j", "slf4j-simple", "2.1.0-alpha1"),
                AdvancedCloudDependency("com.google.guava", "guava", "33.4.8-jre"),
                AdvancedCloudDependency("org.jetbrains.kotlinx", "kotlinx-coroutines-core", "1.10.2"),
                AdvancedCloudDependency("com.google.code.gson", "gson", "2.13.1"),
                AdvancedCloudDependency("io.netty", "netty-all", "5.0.0.Alpha2"),
                AdvancedCloudDependency("org.reflections", "reflections", "0.10.2"),
                AdvancedCloudDependency("org.mariadb.jdbc", "mariadb-java-client", "3.5.4"),
                AdvancedCloudDependency("com.github.ajalt.clikt", "clikt", "5.0.3"),
                AdvancedCloudDependency("net.kyori", "adventure-api", "4.23.0"),
                AdvancedCloudDependency("net.kyori", "adventure-text-serializer-gson", "4.23.0"),
                AdvancedCloudDependency("org.xerial", "sqlite-jdbc", "3.50.2.0")
            )
        )
    }

}