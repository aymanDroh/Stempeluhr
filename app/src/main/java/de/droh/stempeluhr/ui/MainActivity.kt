package de.droh.stempeluhr.ui

import android.nfc.NfcAdapter
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
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
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import de.droh.stempeluhr.engine.Notifications
import de.droh.stempeluhr.engine.StampEngine
import de.droh.stempeluhr.nfc.NfcTags
import de.droh.stempeluhr.service.MonitorService
import java.time.YearMonth

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
                AppRoot(
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

private enum class Tab(val label: String) { HEUTE("Heute"), VERLAUF("Verlauf"), WOCHEN("Wochen"), ABGLEICH("Abgleich"), OPTIONEN("Optionen") }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppRoot(
    resumeCount: Int,
    tagWriteMode: StampEngine.Mode?,
    onStartTagWrite: (StampEngine.Mode) -> Unit,
    onCancelTagWrite: () -> Unit,
) {
    var tab by rememberSaveable { mutableStateOf(Tab.HEUTE) }
    var reconcileMonth by rememberSaveable { mutableStateOf<String?>(null) }
    var showConfirmed by rememberSaveable { mutableStateOf(false) }
    val openMonths = rememberOpenReconcileMonths()
    // Abgleich-Tab nur zeigen, solange es offene Monate gibt (oder er gerade geöffnet ist).
    val showReconcileTab = openMonths.isNotEmpty() || tab == Tab.ABGLEICH
    val tabs = Tab.entries.filter { it != Tab.ABGLEICH || showReconcileTab }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainerLow, tonalElevation = 0.dp) {
                tabs.forEach { t ->
                    NavigationBarItem(
                        selected = tab == t,
                        onClick = {
                            if (t == Tab.ABGLEICH) showConfirmed = false
                            tab = t
                        },
                        icon = {
                            if (t == Tab.ABGLEICH && openMonths.isNotEmpty()) {
                                BadgedBox(badge = { Badge { Text("${openMonths.size}") } }) { TabIcon(t) }
                            } else {
                                TabIcon(t)
                            }
                        },
                        label = { Text(t.label, maxLines = 1) },
                    )
                }
            }
        },
    ) { padding ->
        AnimatedContent(
            targetState = tab,
            transitionSpec = {
                val forward = targetState.ordinal > initialState.ordinal
                (fadeIn(tween(250)) + slideInHorizontally(tween(300)) { w -> if (forward) w / 8 else -w / 8 }) togetherWith
                    fadeOut(tween(150))
            },
            label = "tabs",
            modifier = Modifier.padding(padding),
        ) { current ->
            val modifier = Modifier
            when (current) {
                Tab.HEUTE -> TodayScreen(
                    modifier, resumeCount,
                    onOpenSettings = { tab = Tab.OPTIONEN },
                    onOpenWeeks = { tab = Tab.WOCHEN },
                    onOpenReconcile = { tab = Tab.ABGLEICH },
                )
                Tab.VERLAUF -> HistoryScreen(modifier)
                Tab.WOCHEN -> WeeksScreen(modifier)
                Tab.ABGLEICH -> ReconcileScreen(
                    modifier,
                    month = reconcileMonth?.let { YearMonth.parse(it) },
                    showConfirmed = showConfirmed,
                    onShowConfirmedChange = { showConfirmed = it },
                    onMonthChange = { reconcileMonth = it?.toString() },
                    onImport = { tab = Tab.OPTIONEN },
                    onDone = { tab = Tab.HEUTE },
                )
                Tab.OPTIONEN -> SettingsScreen(
                    modifier, resumeCount, tagWriteMode, onStartTagWrite, onCancelTagWrite,
                    onImported = { month ->
                        reconcileMonth = month?.toString()
                        showConfirmed = false
                        tab = Tab.ABGLEICH
                    },
                    onShowConfirmedReconciles = {
                        showConfirmed = true
                        tab = Tab.ABGLEICH
                    },
                )
            }
        }
    }
}

@Composable
private fun TabIcon(t: Tab) {
    val icon = when (t) {
        Tab.HEUTE -> Icons.Filled.Home
        Tab.VERLAUF -> Icons.AutoMirrored.Filled.List
        Tab.WOCHEN -> Icons.Filled.DateRange
        Tab.ABGLEICH -> Icons.Filled.CheckCircle
        Tab.OPTIONEN -> Icons.Filled.Settings
    }
    Icon(icon, contentDescription = null)
}
