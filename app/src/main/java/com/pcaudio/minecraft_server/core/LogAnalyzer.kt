package com.pcaudio.minecraft_server.core

import android.content.res.Resources
import com.pcaudio.minecraft_server.R

data class LogIssue(val severity: String, val description: String, val rawLine: String)

class LogAnalyzer(private val resources: Resources) {

    fun analyze(logContent: String): List<LogIssue> {
        val issues = mutableListOf<LogIssue>()
        val lines = logContent.lines()

        for (line in lines) {
            when {
                line.contains("MixinApplyError") || line.contains("mixin apply failed") -> {
                    issues.add(LogIssue(resources.getString(R.string.logissue_mod_conflict_severity), resources.getString(R.string.logissue_mod_conflict), line))
                }
                line.contains("DuplicateModsFoundException") || line.contains("duplicate mod") -> {
                    issues.add(LogIssue(resources.getString(R.string.logissue_duplicate_mod_severity), resources.getString(R.string.logissue_duplicate_mod), line))
                }
                line.contains("MissingDependencyException") || (line.contains("requires") && line.contains("missing")) -> {
                    issues.add(LogIssue(resources.getString(R.string.logissue_missing_dependency_severity), resources.getString(R.string.logissue_missing_dependency), line))
                }
                line.contains("IncompatibleModException") || line.contains("incompatible") && line.contains("mod") -> {
                    issues.add(LogIssue(resources.getString(R.string.logissue_incompatibility_severity), resources.getString(R.string.logissue_incompatibility), line))
                }
                line.contains("UnsupportedClassVersionError") -> {
                    issues.add(LogIssue(resources.getString(R.string.logissue_java_version_severity), resources.getString(R.string.logissue_java_version), line))
                }
                line.contains("session.lock") && line.contains("already locked") -> {
                    issues.add(LogIssue(resources.getString(R.string.logissue_world_locked_severity), resources.getString(R.string.logissue_world_locked), line))
                }
                line.contains("OutOfMemoryError") -> {
                    issues.add(LogIssue(resources.getString(R.string.logissue_out_of_memory_severity), resources.getString(R.string.logissue_out_of_memory), line))
                }
                line.contains("CrashReport") || line.contains("Exception in server tick loop") -> {
                    issues.add(LogIssue(resources.getString(R.string.logissue_server_crash_severity), resources.getString(R.string.logissue_server_crash), line))
                }
                line.contains("Failed to initialize server") -> {
                    issues.add(LogIssue(resources.getString(R.string.logissue_startup_failed_severity), resources.getString(R.string.logissue_startup_failed), line))
                }
                line.contains("(incompatible)") -> {
                    val packName = line.substringBefore("(incompatible)").trim().substringAfterLast(",").trim()
                    issues.add(LogIssue(resources.getString(R.string.logissue_datapack_severity), resources.getString(R.string.logissue_datapack, packName), line))
                }
                line.contains("Description: Exception in server tick loop") -> {
                    issues.add(LogIssue(resources.getString(R.string.logissue_startup_crash_severity), resources.getString(R.string.logissue_startup_crash), line))
                }
                line.contains("Failed to initialize server") -> {
                    issues.add(LogIssue(resources.getString(R.string.logissue_startup_failed_severity), resources.getString(R.string.logissue_startup_failed_datapack), line))
                }
                line.contains("(incompatible)") -> {
                    issues.add(LogIssue(resources.getString(R.string.logissue_datapack_severity), resources.getString(R.string.logissue_datapack_long), line))
                }
                Regex("requires\\s+\\{([^}]+)\\}", RegexOption.IGNORE_CASE).containsMatchIn(line) -> {
                    val match = Regex("requires\\s+\\{([^}]+)\\}", RegexOption.IGNORE_CASE).find(line)
                    val dependency = match?.groupValues?.get(1) ?: resources.getString(R.string.logissue_unknown_dependency)
                    issues.add(LogIssue(resources.getString(R.string.logissue_missing_dependency_severity), resources.getString(R.string.logissue_missing_dependency_named, dependency), line))
                }
                line.contains("Mod resolution failed") -> {
                    issues.add(LogIssue(resources.getString(R.string.logissue_unresolved_severity), resources.getString(R.string.logissue_unresolved), line))
                }
                line.contains("witnesses:") && line.contains("depends on") -> {
                    issues.add(LogIssue(resources.getString(R.string.logissue_missing_dependency_severity), resources.getString(R.string.logissue_missing_dependency_present), line))
                }
                line.contains("libm.so.6") || (line.contains("dlopen failed") && line.contains(".so")) -> {
                    issues.add(LogIssue(resources.getString(R.string.logissue_native_library_severity), resources.getString(R.string.logissue_native_library), line))
                }
            }
        }

        return issues
    }

    fun summarize(logContent: String): String {
        val issues = analyze(logContent)
        if (issues.isEmpty()) {
            return if (logContent.contains("Done") && logContent.contains("For help")) resources.getString(R.string.logs_summary_started_ok) else resources.getString(R.string.logs_summary_no_issues)
        }
        val distinctSeverities = issues.map { it.severity }.distinct()
        return resources.getString(R.string.logs_summary_problems, issues.size, distinctSeverities.joinToString(", "))
    }
}