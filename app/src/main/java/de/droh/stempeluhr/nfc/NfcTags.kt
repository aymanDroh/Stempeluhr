package de.droh.stempeluhr.nfc

import android.app.Activity
import android.content.Intent
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.Ndef
import android.nfc.tech.NdefFormatable
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.widget.Toast
import de.droh.stempeluhr.core.StampSource
import de.droh.stempeluhr.core.StampType
import de.droh.stempeluhr.data.Settings
import de.droh.stempeluhr.engine.StampEngine

object NfcTags {
    /** Eigener MIME-Typ: Android öffnet beim Scannen direkt diese App. */
    const val MIME = "application/vnd.de.droh.stempeluhr"

    fun message(packageName: String, mode: StampEngine.Mode): NdefMessage = NdefMessage(
        arrayOf(
            NdefRecord.createMime(MIME, mode.name.lowercase().toByteArray(Charsets.US_ASCII)),
            NdefRecord.createApplicationRecord(packageName),
        ),
    )

    fun parseMode(payload: ByteArray?): StampEngine.Mode {
        val text = payload?.toString(Charsets.US_ASCII)?.trim()?.uppercase()
        return StampEngine.Mode.entries.firstOrNull { it.name == text } ?: StampEngine.Mode.TOGGLE
    }

    /** Beschreibt einen Tag. Läuft im NFC-Thread. @return Fehlermeldung oder null bei Erfolg. */
    fun write(tag: Tag, message: NdefMessage): String? {
        try {
            val ndef = Ndef.get(tag)
            if (ndef != null) {
                ndef.connect()
                ndef.use {
                    if (!it.isWritable) return "Tag ist schreibgeschützt."
                    if (it.maxSize < message.byteArrayLength) return "Tag ist zu klein (${it.maxSize} Bytes)."
                    it.writeNdefMessage(message)
                }
                return null
            }
            val formatable = NdefFormatable.get(tag) ?: return "Dieser Tag-Typ unterstützt kein NDEF."
            formatable.connect()
            formatable.use { it.format(message) }
            return null
        } catch (e: Exception) {
            return "Schreiben fehlgeschlagen: ${e.message ?: e.javaClass.simpleName}"
        }
    }
}

/**
 * Unsichtbare Activity, die Android beim Scannen eines Stempeluhr-Tags startet.
 * Speichert das Ereignis, gibt kurz Rückmeldung und schließt sich sofort.
 */
class NfcStampActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handle(intent)
        finish()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
        finish()
    }

    @Suppress("DEPRECATION")
    private fun handle(intent: Intent?) {
        if (intent?.action != NfcAdapter.ACTION_NDEF_DISCOVERED) return
        if (!Settings.get(this).nfcEnabled) {
            toast("NFC-Stempeln ist in der App ausgeschaltet.")
            return
        }
        val messages = intent.getParcelableArrayExtra(NfcAdapter.EXTRA_NDEF_MESSAGES)
        val record = messages
            ?.filterIsInstance<NdefMessage>()
            ?.flatMap { it.records.asList() }
            ?.firstOrNull { it.tnf == NdefRecord.TNF_MIME_MEDIA && String(it.type, Charsets.US_ASCII) == NfcTags.MIME }
        val mode = NfcTags.parseMode(record?.payload)
        val type = StampEngine.precise(this, StampSource.NFC, mode)
        if (type == null) {
            toast("Bereits erfasst (doppelt gescannt).")
            return
        }
        vibrate(type)
        toast("${type.label} erfasst (NFC)")
    }

    private fun toast(text: String) = Toast.makeText(applicationContext, text, Toast.LENGTH_SHORT).show()

    private fun vibrate(type: StampType) {
        val vibrator = getSystemService(Vibrator::class.java) ?: return
        val effect = if (type == StampType.IN) {
            VibrationEffect.createOneShot(120, VibrationEffect.DEFAULT_AMPLITUDE)
        } else {
            VibrationEffect.createWaveform(longArrayOf(0, 80, 80, 80), -1)
        }
        vibrator.vibrate(effect)
    }
}
