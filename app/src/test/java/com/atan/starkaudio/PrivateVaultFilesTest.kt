package com.atan.starkaudio

import com.atan.starkaudio.storage.PrivateVaultFiles
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class PrivateVaultFilesTest {
    @Test fun recursivelyDeletesOnlyInsideVault() {
        val parent = Files.createTempDirectory("stark-delete-test").toFile()
        try {
            val vault = parent.resolve("vault").apply { mkdirs() }
            val recording = vault.resolve("recordings/session").apply { mkdirs() }
            recording.resolve("part-001.m4a").writeBytes(byteArrayOf(1, 2, 3))
            assertTrue(PrivateVaultFiles.deleteTree(vault, recording.path))
            assertFalse(recording.exists())
        } finally { parent.deleteRecursively() }
    }

    @Test fun refusesTargetsOutsideVault() {
        val parent = Files.createTempDirectory("stark-delete-boundary").toFile()
        try {
            val vault = parent.resolve("vault").apply { mkdirs() }
            val outside = parent.resolve("keep.m4a").apply { writeText("keep") }
            assertFalse(PrivateVaultFiles.deleteTree(vault, outside.path))
            assertTrue(outside.exists())
        } finally { parent.deleteRecursively() }
    }
}
