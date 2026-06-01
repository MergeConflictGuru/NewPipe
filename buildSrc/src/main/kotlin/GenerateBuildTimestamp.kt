/*
 * SPDX-FileCopyrightText: 2026 NewPipe contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

abstract class GenerateBuildTimestamp : DefaultTask() {

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun run() {
        val buildTime = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss 'UTC'")
            .withZone(ZoneOffset.UTC)
            .format(Instant.now())
        val outputFile = outputDirectory.file("org/schabi/newpipe/BuildTimestamp.java")
            .get()
            .asFile
        outputFile.parentFile.mkdirs()
        outputFile.writeText(
            """
            package org.schabi.newpipe;

            public final class BuildTimestamp {
                public static final String VALUE = "$buildTime";

                private BuildTimestamp() { }
            }
            """.trimIndent()
        )
    }
}
