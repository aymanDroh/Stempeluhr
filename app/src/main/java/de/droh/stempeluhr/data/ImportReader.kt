package de.droh.stempeluhr.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import de.droh.stempeluhr.core.Backup
import de.droh.stempeluhr.core.BackupData
import de.droh.stempeluhr.core.ImportParser
import de.droh.stempeluhr.core.ImportResult
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.time.ZoneId

/** Was in einer gewählten Datei steckt. */
sealed interface ImportedFile {
    val fileName: String

    /** Vollständige Sicherung eines anderen Handys. */
    data class BackupFile(override val fileName: String, val data: BackupData) : ImportedFile

    /** Stundenzettel (PDF/CSV) oder Rohdaten-Export (CSV). */
    data class Times(override val fileName: String, val result: ImportResult) : ImportedFile
}

/** Liest eine vom Nutzer gewählte Datei (Sicherung, PDF, CSV oder Text) ein. */
object ImportReader {
    /** Mehr braucht keine Sicherung und kein Stundenzettel – schützt vor versehentlich gewählten Riesendateien. */
    private const val MAX_BYTES = 40 * 1024 * 1024

    fun fileName(context: Context, uri: Uri): String = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment.orEmpty()

    /**
     * Erkennt selbst, um welche Art Datei es sich handelt. Läuft im Hintergrund-Thread.
     * @throws de.droh.stempeluhr.core.BackupFormatException wenn eine Sicherung nicht lesbar ist.
     */
    fun read(context: Context, uri: Uri, zone: ZoneId): ImportedFile {
        val name = fileName(context, uri)
        val text = readText(context, uri)
        return if (Backup.looksLikeBackup(text)) {
            ImportedFile.BackupFile(name, Backup.decode(text))
        } else {
            ImportedFile.Times(name, ImportParser.parse(text, zone))
        }
    }

    /** Läuft im Hintergrund-Thread. */
    fun readText(context: Context, uri: Uri): String {
        val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                out.write(buffer, 0, n)
                if (out.size() > MAX_BYTES) throw IllegalStateException("Die Datei ist zu groß.")
            }
            out.toByteArray()
        } ?: throw IllegalStateException("Datei konnte nicht geöffnet werden.")
        val isPdf = bytes.size > 4 && String(bytes, 0, 5, Charsets.US_ASCII) == "%PDF-"
        if (isPdf) {
            PDFBoxResourceLoader.init(context.applicationContext)
            PDDocument.load(bytes).use { doc ->
                val stripper = PDFTextStripper().apply { sortByPosition = true }
                return stripper.getText(doc)
            }
        }
        return decode(bytes)
    }

    /** UTF-8, falls gültig – sonst Windows-1252 (typisch für Excel-CSV). */
    private fun decode(bytes: ByteArray): String = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (_: CharacterCodingException) {
        String(bytes, Charset.forName("windows-1252"))
    }
}
