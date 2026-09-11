package com.example.test_project.processing.ocr

import android.content.Context
import java.io.File

/** Revisioned cache names prevent stale weights after an application update. */
internal object ModelAssets {
    @Synchronized
    fun path(context: Context, name: String): String {
        val revision = context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
        val directory = File(context.cacheDir, "ocr-$revision").apply { mkdirs() }
        context.cacheDir
            .listFiles()
            ?.filter { it.isDirectory && it.name.matches(Regex("ocr-[0-9]+")) && it != directory }
            ?.forEach { it.deleteRecursively() }
        val target = File(directory, name)
        if (!target.exists()) {
            val temporary = File(directory, "$name.tmp")
            try {
                context.assets.open(name).use { input ->
                    temporary.outputStream().use { output -> input.copyTo(output) }
                }
                check(temporary.renameTo(target)) { "Unable to cache $name" }
            } finally {
                temporary.delete()
            }
        }
        return target.path
    }
}
