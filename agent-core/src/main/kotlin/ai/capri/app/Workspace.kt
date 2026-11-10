package ai.capri.app

import java.io.File

class Workspace(private val base: File) {
    fun sessionDirectory(id: String): File {
        require(id.matches(Regex("[a-zA-Z0-9-]+"))) { "Invalid workspace identifier." }
        return File(base, id)
    }
    fun root(id: String): File {
        require(id.matches(Regex("[a-zA-Z0-9-]+")))
        return File(base, id).apply { mkdirs() }
    }
    fun folder(id: String, name: String) = File(root(id), name).apply { mkdirs() }
    fun file(id: String, path: String): File {
        val root = folder(id, "workspace").canonicalFile
        require(path.isNotBlank() && !File(path).isAbsolute) { "仅允许工作区相对路径" }
        val file = File(root, path).canonicalFile
        require(file.path.startsWith(root.path + File.separator)) { "路径超出当前会话工作区" }
        return file
    }
    fun list(id: String): List<File> {
        val root = folder(id, "workspace").canonicalFile
        return root.walkTopDown().onEnter { it.canonicalPath.startsWith(root.path) && !java.nio.file.Files.isSymbolicLink(it.toPath()) }
            .filter { it.isFile && !java.nio.file.Files.isSymbolicLink(it.toPath()) && it.canonicalPath.startsWith(root.path + File.separator) }.take(200).toList()
    }
}
