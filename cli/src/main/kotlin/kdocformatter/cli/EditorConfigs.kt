/*
 * Copyright (C) 2022 Tor Norbye
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package kdocformatter.cli

import java.io.File
import java.util.*
import kdocformatter.KDocFormattingOptions

/**
 * Basic support for [.editorconfig] files
 * (http://https://editorconfig.org/).
 *
 * This will construct [KDocFormattingOptions] applicable for
 * a given file. The [KDocFormattingOptions.maxLineWidth]
 * property is initialized from the [.editorconfig] property
 * `max_line_length` applicable to Kotlin source files, and the
 * [KDocFormattingOptions.maxCommentWidth] is initialized from the
 * property `max_line_length` applicable to Markdown source files (but
 * **not** inherited from non-Markdown specific declarations such as
 * [*].]
 *
 * We're processing it ourselves here instead of using one of the
 * available editorconfig libraries on GitHub because of that special
 * handling of markdown settings where we want to consult values without
 * inheritance.
 */
object EditorConfigs {
  var root: KDocFormattingOptions? = null
    set(value) {
      dirToConfig.clear()
      field = value
    }
  private val dirToConfig = mutableMapOf<File, EditorConfig>()

  fun getOptions(file: File): KDocFormattingOptions {
    val parent = file.parentFile ?: return root ?: KDocFormattingOptions()
    return getConfig(parent)?.getOptions() ?: root ?: KDocFormattingOptions()
  }

  @Suppress("FileComparisons")
  private fun getConfig(dir: File?): EditorConfig? {
    dir ?: return null

    val existing = dirToConfig[dir]
    if (existing != null) {
      return if (existing !== EditorConfig.NONE) existing else null
    }

    val configFile =
        findEditorConfigFile(dir)
            ?: run {
              dirToConfig[dir] = EditorConfig.NONE

              var curr = dir.parentFile
              while (true) {
                dirToConfig[curr] = EditorConfig.NONE
                curr = curr.parentFile ?: break
              }

              return null
            }

    val configFolder = configFile.parentFile
    val parentConfigFolder = configFolder?.parentFile
    val parent = getConfig(parentConfigFolder)
    val config = EditorConfig.createEditorConfig(configFile, parent)
    dirToConfig[dir] = config

    if (configFolder != dir) {
      var curr: File = dir
      while (true) {
        if (curr == configFolder) {
          break
        } else {
          dirToConfig[curr] = config
        }
        curr = curr.parentFile ?: break
      }
    }

    return config
  }

  private fun findEditorConfigFile(fromDir: File): File? {
    var dir = fromDir
    while (true) {
      val file = File(dir, ".editorconfig")
      if (file.isFile) {
        return file
      }
      dir = dir.parentFile ?: return null
    }
  }

  class EditorConfig
  private constructor(
      private val root: Boolean,
      private val file: File,
      private val parent: EditorConfig?,
      private val sections: List<SectionMap>
  ) {
    private data class SectionMap(val section: String, val map: Map<String, String>)

    private var options: KDocFormattingOptions? = null

    fun getOptions(): KDocFormattingOptions {
      return options ?: computeOptions().also { options = it }
    }

    fun getValue(key: String, eligibleSection: String, includeRoot: Boolean = true): Any? {
      var value: String? = null
      for (section in sections) {
        val name = section.section
        if (includeRoot && name == "[*]" || name.contains(eligibleSection)) {
          // last applicable value wins
          section.map[key]?.let { value = it }
        }
      }

      if (value == null && !root) {
        return parent?.getValue(key, eligibleSection, includeRoot)
      }

      return value
    }

    private fun computeOptions(): KDocFormattingOptions {
      val options =
          (if (!root) parent?.getOptions()?.copy() else null)
              ?: EditorConfigs.root?.copy() ?: KDocFormattingOptions()

      getValue("max_line_length", "*.kt")?.let { stringValue ->
        if (stringValue == "unset") {
          EditorConfigs.root?.maxLineWidth?.let { options.maxLineWidth = it }
        } else {
          (stringValue as? String)?.toIntOrNull()?.let { value -> options.maxLineWidth = value }
        }
      }

      getValue("max_line_length", "*.md", false)?.let { stringValue ->
        if (stringValue == "unset") {
          EditorConfigs.root?.maxCommentWidth?.let { options.maxCommentWidth = it }
        } else {
          (stringValue as? String)?.toIntOrNull()?.let { value -> options.maxCommentWidth = value }
        }
      }

      getValue("indent_size", "*.kt")?.let { stringValue ->
        if (stringValue == "unset") {
          EditorConfigs.root?.hangingIndent?.let { options.hangingIndent = it }
        } else {
          (stringValue as? String)?.toIntOrNull()?.let { value -> options.hangingIndent = value }
        }
      }

      getValue("tab_width", "*.kt")?.let { stringValue ->
        if (stringValue == "unset") {
          EditorConfigs.root?.tabWidth?.let { options.tabWidth = it }
        } else {
          (stringValue as? String)?.toIntOrNull()?.let { value -> options.tabWidth = value }
        }
      }

      val oneline =
          getValue("kdoc_formatter_doc_do_not_wrap_if_one_line", "*.kt")
              ?: getValue("ij_kotlin_doc_do_not_wrap_if_one_line", "*.kt")
                  ?: getValue("ij_java_doc_do_not_wrap_if_one_line", "*.java")
      oneline?.let { stringValue ->
        if (stringValue == "unset") {
          EditorConfigs.root?.collapseSingleLine?.let { options.collapseSingleLine = it }
        } else {
          (stringValue as? String)?.toBoolean()?.let { value ->
            options.collapseSingleLine = !value
          }
        }
      }

      return options
    }

    override fun toString(): String {
      return file.toString()
    }

    companion object {
      val NONE = EditorConfig(true, File(""), null, emptyList())

      // If root -- no need to keep going
      fun createEditorConfig(config: File, parent: EditorConfig?): EditorConfig {
        // Maybe have a default flag, e.g. --default-line-width
        val sections = ArrayList<SectionMap>()
        var section: SectionMap? = null
        var map = HashMap<String, String>()
        var root = false

        val lines = config.readLines()
        for (line in lines) {
          if (line.startsWith("#") || line.startsWith(";") || line.isBlank()) {
            continue
          }
          if (line.startsWith("[")) {
            val globs =
                line
                    .removePrefix("[")
                    .removeSuffix("]")
                    .removePrefix("{")
                    .removeSuffix("}")
                    .split(",")

            if (globs.any {
              it == "*" || it == "*.kt" || it == "*.kts" || it == "*.md" || it == "*.java"
            }) {
              map = HashMap()
              section = SectionMap(line, map)
              sections.add(section)
            } else {
              section = null
            }
          } else {
            val eq = line.indexOf('=')

            val key = line.substring(0, eq).trim().lowercase()
            if (key == "root") {
              root = line.substring(eq + 1).trim().toBoolean()
            } else
                when (key) {
                  "max_line_length",
                  "indent_size",
                  "tab_width",
                  "kdoc_formatter_doc_do_not_wrap_if_one_line",
                  "ij_java_doc_do_not_wrap_if_one_line" ->
                      if (section != null) {
                        val value = line.substring(eq + 1).trim()
                        map[key] = value
                      }
                }
          }
        }

        return EditorConfig(root, config, parent, sections)
      }
    }
  }
}
