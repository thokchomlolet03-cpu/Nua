package org.nua.assess

import org.junit.Test
import org.junit.Assert.*
import java.nio.file.Files
import java.io.File

class ModelFilesTest {
    @Test fun failedModelRestoresPreviousBytes() {
        val dir = Files.createTempDirectory("nua-model-test").toFile()
        try {
            val old = File(dir, "model").apply { writeText("working") }
            val incoming = File(dir, "incoming").apply { writeText("invalid") }
            assertThrows(IllegalStateException::class.java) { ModelFiles.replace(incoming, old) { false } }
            assertEquals("working", old.readText())
        } finally { dir.deleteRecursively() }
    }
    @Test fun successfulInitializationCommitsReplacement() {
        val dir = Files.createTempDirectory("nua-model-test").toFile()
        try {
            val old = File(dir, "model").apply { writeText("working") }
            val incoming = File(dir, "incoming").apply { writeText("better") }
            ModelFiles.replace(incoming, old) { old.readText() == "better" }
            assertEquals("better", old.readText())
            assertFalse(File(old.path + ".previous").exists())
        } finally { dir.deleteRecursively() }
    }
    @Test fun restartRestoresInterruptedReplacement() {
        val dir = Files.createTempDirectory("nua-model-test").toFile()
        try {
            val old = File(dir, "model").apply { writeText("unfinished") }
            File(old.path + ".previous").writeText("working")
            ModelFiles.recover(old)
            assertEquals("working", old.readText())
        } finally { dir.deleteRecursively() }
    }
}
