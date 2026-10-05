package de.droh.stempeluhr.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/** Liest eine vom Nutzer gewählte Datei (PDF, CSV oder Text) als Text ein. */
object ImportReader {

    fun fileName(context: Context, uri: Uri): String =
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: uri.lastPathSegment.orEmpty()

    /** Läuft im Hintergrund-Thread. */
    fun readText(context: Context, uri: Uri): String {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw IllegalStateException("Datei konnte nicht geöffnet werden.")
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
