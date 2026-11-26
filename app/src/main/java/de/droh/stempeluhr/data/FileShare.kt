package de.droh.stempeluhr.data

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

/** Gibt einen Export über den Teilen-Dialog des Handys weiter (Quick Share, E-Mail, Drive …). */
object FileShare {
    private const val SHARE_DIR = "export"

    /**
     * Schreibt [text] als Datei in den App-Cache und liefert einen Teilen-Dialog dafür.
     * Ältere geteilte Dateien im Cache werden dabei entfernt.
     */
    fun textFile(context: Context, fileName: String, mime: String, text: String, title: String): Intent {
        val dir = File(context.cacheDir, SHARE_DIR)
        dir.listFiles()?.forEach { it.delete() }
        dir.mkdirs()
        val file = File(dir, fileName)
        file.writeText(text, Charsets.UTF_8)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, file.name)
            // ClipData sorgt dafür, dass die Leseberechtigung auch im Auswahldialog ankommt.
            clipData = ClipData.newRawUri(file.name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(send, title)
    }
}
