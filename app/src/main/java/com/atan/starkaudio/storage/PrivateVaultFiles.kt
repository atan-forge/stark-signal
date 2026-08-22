package com.atan.starkaudio.storage

import java.io.File
import java.nio.file.Files

internal object PrivateVaultFiles {
    fun deleteTree(vaultRoot: File, path: String): Boolean = runCatching {
        val root = vaultRoot.canonicalFile
        val target = File(path)
        val canonical = target.canonicalFile
        require(isDescendant(root, canonical)) { "Deletion target is outside the private vault" }
        if (!target.exists()) return@runCatching true
        deleteNode(root, target)
    }.getOrDefault(false)

    private fun deleteNode(root: File, node: File): Boolean {
        val canonical = node.canonicalFile
        if (!isDescendant(root, canonical)) return false
        if (Files.isSymbolicLink(node.toPath())) return node.delete()
        if (node.isDirectory) {
            val children = node.listFiles() ?: return false
            if (children.any { !deleteNode(root, it) }) return false
        }
        return !node.exists() || node.delete()
    }

    private fun isDescendant(root: File, target: File): Boolean = target.path.startsWith(root.path + File.separator)
}
