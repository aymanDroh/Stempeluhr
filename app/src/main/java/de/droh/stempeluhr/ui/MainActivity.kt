package de.droh.stempeluhr.ui

import android.nfc.NfcAdapter
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import de.droh.stempeluhr.engine.Notifications
import de.droh.stempeluhr.engine.StampEngine
import de.droh.stempeluhr.nfc.NfcTags
import de.droh.stempeluhr.service.MonitorService

class MainActivity : ComponentActivity() {

    /** Zählt bei jedem onResume hoch, damit Berechtigungs-Status neu geprüft werden. */
    private val resumeCount = mutableIntStateOf(0)

    /** Gesetzt, solange auf einen NFC-Tag zum Beschreiben gewartet wird. */
    private val tagWriteMode = mutableStateOf<StampEngine.Mode?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Notifications.createChannels(this)
        setContent {
            StempelTheme {
                App(
                    resumeCount = resumeCount.intValue,
                    tagWriteMode = tagWriteMode.value,
                    onStartTagWrite = ::startTagWrite,
                    onCancelTagWrite = ::cancelTagWrite,
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        StampEngine.tick(this)
        MonitorService.start(this)
        resumeCount.intValue++
        if (tagWriteMode.value != null) enableReader()
    }

    override fun onPause() {
        super.onPause()
        NfcAdapter.getDefaultAdapter(this)?.disableReaderMode(this)
    }

    private fun startTagWrite(mode: StampEngine.Mode) {
        val adapter = NfcAdapter.getDefaultAdapter(this)
        if (adapter == null || !adapter.isEnabled) {
            Toast.makeText(this, "NFC ist nicht verfügbar oder ausgeschaltet.", Toast.LENGTH_LONG).show()
            return
        }
        tagWriteMode.value = mode
        enableReader()
    }

    private fun cancelTagWrite() {
        tagWriteMode.value = null
        NfcAdapter.getDefaultAdapter(this)?.disableReaderMode(this)
    }

    private fun enableReader() {
        val adapter = NfcAdapter.getDefaultAdapter(this) ?: return
        val flags = NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B or
            NfcAdapter.FLAG_READER_NFC_F or NfcAdapter.FLAG_READER_NFC_V
        adapter.enableReaderMode(this, { tag ->
            val mode = tagWriteMode.value ?: return@enableReaderMode
            val error = NfcTags.write(tag, NfcTags.message(packageName, mode))
            runOnUiThread {
                if (error == null) {
                    Toast.makeText(this, "Tag beschrieben – ab jetzt einfach mit entsperrtem Handy scannen.", Toast.LENGTH_LONG).show()
                    cancelTagWrite()
                } else {
                    Toast.makeText(this, error, Toast.LENGTH_LONG).show()
                }
            }
        }, flags, null)
    }
}

@Composable
fun StempelTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val colors = when {
        Build.VERSION.SDK_INT >= 31 && dark -> dynamicDarkColorScheme(context)
        Build.VERSION.SDK_INT >= 31 -> dynamicLightColorScheme(context)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = colors, content = content)
}

@Composable
private fun App(
    resumeCount: Int,
    tagWriteMode: StampEngine.Mode?,
    onStartTagWrite: (StampEngine.Mode) -> Unit,
    onCancelTagWrite: () -> Unit,
) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = tab == 0,
                    onClick = { tab = 0 },
                    icon = { Icon(Icons.Filled.Home, contentDescription = null) },
                    label = { Text("Heute") },
                )
                NavigationBarItem(
                    selected = tab == 1,
                    onClick = { tab = 1 },
                    icon = { Icon(Icons.AutoMirrored.Filled.List, contentDescription = null) },
                    label = { Text("Verlauf") },
                )
                NavigationBarItem(
                    selected = tab == 3,
                    onClick = { tab = 3 },
                    icon = { Icon(Icons.Filled.DateRange, contentDescription = null) },
                    label = { Text("Wochen") },
                )
                NavigationBarItem(
                    selected = tab == 2,
                    onClick = { tab = 2 },
                    icon = { Icon(Icons.Filled.Settings, contentDescription = null) },
                    label = { Text("Einstellungen") },
                )
            }
        },
    ) { padding ->
        val modifier = Modifier.padding(padding)
        when (tab) {
            0 -> TodayScreen(modifier, resumeCount, onOpenSettings = { tab = 2 }, onOpenWeeks = { tab = 3 })
            1 -> HistoryScreen(modifier)
            3 -> WeeksScreen(modifier)
            else -> SettingsScreen(modifier, resumeCount, tagWriteMode, onStartTagWrite, onCancelTagWrite)
        }
    }
}
