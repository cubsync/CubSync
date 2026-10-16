package io.github.cubsync.desktop

import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.io.File
import javax.swing.JFileChooser
import javax.swing.UIManager

/**
 * The few things that need the desktop rather than the framework.
 *
 * Compose runs on the Swing event thread here, so these are called on the thread
 * that owns the window and can block on a modal dialog without freezing anything
 * else.
 */
private var lookAndFeelApplied = false

fun copyToClipboard(text: String) {
    runCatching {
        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
    }
}

/** A folder chooser. Returns null when the person cancels. */
fun pickDirectory(startAt: String? = null): String? {
    prepareDialogs()
    val chooser = JFileChooser()
    chooser.dialogTitle = "选择文件夹"
    chooser.fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
    chooser.isMultiSelectionEnabled = false
    if (!startAt.isNullOrBlank()) {
        runCatching { chooser.currentDirectory = File(startAt) }
    }
    return if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
        chooser.selectedFile?.absolutePath
    } else {
        null
    }
}

/** Opens a folder in the platform's file manager. */
fun openDirectory(path: String): Boolean = runCatching {
    val target = File(path)
    if (!target.isDirectory) return@runCatching false
    if (java.awt.Desktop.isDesktopSupported()) {
        java.awt.Desktop.getDesktop().open(target)
        true
    } else {
        false
    }
}.getOrDefault(false)

/** A file open dialog filtered to one extension. */
fun pickOpenFile(title: String, extension: String): String? {
    prepareDialogs()
    val chooser = JFileChooser()
    chooser.dialogTitle = title
    chooser.fileSelectionMode = JFileChooser.FILES_ONLY
    chooser.fileFilter = ExtensionFilter(extension)
    return if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
        chooser.selectedFile?.absolutePath
    } else {
        null
    }
}

/** A file save dialog that insists on the extension the format expects. */
fun pickSaveFile(title: String, defaultName: String, extension: String): String? {
    prepareDialogs()
    val chooser = JFileChooser()
    chooser.dialogTitle = title
    chooser.fileSelectionMode = JFileChooser.FILES_ONLY
    chooser.fileFilter = ExtensionFilter(extension)
    chooser.selectedFile = File(defaultName)
    return if (chooser.showSaveDialog(null) == JFileChooser.APPROVE_OPTION) {
        chooser.selectedFile?.let { file ->
            if (file.name.endsWith(extension, ignoreCase = true)) file else File(file.parentFile, file.name + extension)
        }?.absolutePath
    } else {
        null
    }
}

private class ExtensionFilter(private val extension: String) : javax.swing.filechooser.FileFilter() {
    override fun accept(file: File): Boolean = file.isDirectory || file.name.endsWith(extension, ignoreCase = true)

    override fun getDescription(): String = "*$extension"
}

/**
 * The file chooser inherits the platform look and feel only if it is asked for
 * before the first dialog, otherwise it appears as a Metal dialog on a dark window.
 */
private fun prepareDialogs() {
    if (lookAndFeelApplied) return
    lookAndFeelApplied = true
    runCatching { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()) }
}
