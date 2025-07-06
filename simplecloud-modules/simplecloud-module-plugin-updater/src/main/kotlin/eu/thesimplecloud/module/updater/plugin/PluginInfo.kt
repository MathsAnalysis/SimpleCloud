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

package eu.thesimplecloud.module.updater.plugin

import com.fasterxml.jackson.annotation.JsonProperty

data class PluginInfo(

    @JsonProperty("name")
    val name: String,
    
    @JsonProperty("version")
    val version: String,
    
    @JsonProperty("platforms")
    val platforms: Map<String, String>,
    
    @JsonProperty("lastUpdated")
    val lastUpdated: String,
    
    @JsonProperty("checksum")
    val checksum: String? = null,
    
    @JsonProperty("fileSize")
    val fileSize: Long? = null,
    
    @JsonProperty("downloadedAt")
    val downloadedAt: Long? = null
) {
    fun toJson(): String {
        return """
            {
                "name": "$name",
                "version": "$version",
                "platforms": ${platformsToJson()},
                "lastUpdated": "$lastUpdated",
                "checksum": ${checksum?.let { "\"$it\"" } ?: "null"},
                "fileSize": ${fileSize ?: "null"},
                "downloadedAt": ${downloadedAt ?: "null"}
            }
        """.trimIndent()
    }
    
    private fun platformsToJson(): String {
        val platformEntries = platforms.entries.joinToString(",") { (platform, url) ->
            "\"$platform\": \"$url\""
        }
        return "{$platformEntries}"
    }
}