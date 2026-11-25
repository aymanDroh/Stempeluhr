package de.droh.stempeluhr.data

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import de.droh.stempeluhr.core.Backup
import de.droh.stempeluhr.core.BackupData
import de.droh.stempeluhr.core.BackupEvent
import de.droh.stempeluhr.core.RestorePlan
import java.io.File
import java.time.LocalDate

/** Ergebnis einer Wiederherstellung. */
data class RestoreResult(val plan: RestorePlan, val settingsApplied: Boolean)

/**
 * Erstellt Sicherungen aller Daten dieses Handys und spielt sie auf einem anderen wieder ein.
 * Alle Funktionen mit Dateizugriff laufen im Hintergrund-Thread.
 */
object BackupStore {
    private const val SHARE_DIR = "sicherung"

    /** Momentaufnahme aller Ereignisse (auch gelöschter), importierten Tage und Einstellungen. */
    fun snapshot(context: Context): BackupData {
        val db = StampDb.get(context)
        return BackupData(
            createdAt = System.currentTimeMillis(),
            appVersion = appVersion(context),
            device = deviceName(),
            events = db.all(includeDeleted = true).map {
                BackupEvent(it.ts, it.type, it.source, it.note, it.createdAt, it.originalTs, it.deleted)
            },
            importedDays = db.importedDaysFull(),
            settings = Settings.get(context).exportPrefs(),
        )
    }

    /** Schreibt die Sicherung an einen vom Nutzer gewählten Ort ("Speichern unter"). */
    fun write(context: Context, uri: Uri, data: BackupData) {
        val out = context.contentResolver.openOutputStream(uri)
            ?: throw IllegalStateException("Datei konnte nicht geschrieben werden.")
        out.use { it.write(Backup.encode(data).toByteArray(Charsets.UTF_8)) }
    }

    /**
     * Legt die Sicherung im App-Cache ab und liefert einen Teilen-Dialog dafür
     * (Quick Share, Nearby Share, E-Mail, Drive …).
     */
    fun shareIntent(context: Context, data: BackupData): Intent =
        shareTextFile(context, Backup.fileName(LocalDate.now()), Backup.MIME, Backup.encode(data), "Sicherung senden")

    /**
     * Schreibt [text] als Datei in den App-Cache und liefert einen Teilen-Dialog dafür.
     * Ältere geteilte Dateien im Cache werden dabei entfernt.
     */
    fun shareTextFile(context: Context, fileName: String, mime: String, text: String, title: String): Intent {
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

    /**
     * Führt die Sicherung mit den Daten dieses Handys zusammen (nichts wird gelöscht oder überschrieben).
     * Einstellungen werden nur übernommen, wenn [withSettings] gesetzt ist.
     */
    fun restore(context: Context, data: BackupData, withSettings: Boolean): RestoreResult {
        val plan = StampDb.get(context).restoreBackup(data)
        val applied = withSettings && data.settings.isNotEmpty()
        if (applied) Settings.get(context).importPrefs(data.settings)
        return RestoreResult(plan, applied)
    }

    private fun appVersion(context: Context): String? = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }.getOrNull()

    /** Z. B. "Samsung SM-S901B" – nur zur Anzeige in der Vorschau auf dem anderen Handy. */
    private fun deviceName(): String {
        val maker = Build.MANUFACTURER.orEmpty().replaceFirstChar { it.uppercase() }
        val model = Build.MODEL.orEmpty()
        return if (model.startsWith(maker, ignoreCase = true)) model else "$maker $model".trim()
    }
}
