package de.droh.stempeluhr.ui

import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import de.droh.stempeluhr.R
import de.droh.stempeluhr.core.StampSource
import de.droh.stempeluhr.core.StampType
import kotlinx.coroutines.delay

// ---------------------------------------------------------------------------------------------
// Icons
// ---------------------------------------------------------------------------------------------

/**
 * Icon aus dem eigenen Satz (Material Symbols als Vektorgrafik).
 * [contentDescription] bleibt leer, wenn direkt daneben ein Text dasselbe sagt.
 */
@Composable
fun AppIcon(
    @DrawableRes id: Int,
    contentDescription: String? = null,
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
    size: Dp = 24.dp,
) {
    Icon(painterResource(id), contentDescription, modifier.size(size), tint)
}

/** Icon in einer getönten, abgerundeten Fläche – z. B. für Quellen und Einstellungs-Zeilen. */
@Composable
fun IconBubble(
    @DrawableRes icon: Int,
    color: Color,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    container: Color = color.copy(alpha = 0.12f),
    shape: Shape = RoundedCornerShape(size * 0.32f),
) {
    Box(modifier.size(size).clip(shape).background(container), contentAlignment = Alignment.Center) {
        AppIcon(icon, tint = color, size = size * 0.55f)
    }
}

@DrawableRes
fun sourceIcon(s: StampSource): Int = when (s) {
    StampSource.NFC -> R.drawable.ic_nfc
    StampSource.WLAN -> R.drawable.ic_wifi
    StampSource.GEOFENCE -> R.drawable.ic_location
    StampSource.MANUELL -> R.drawable.ic_manual
}

/** Name einer Quelle, wie er in der Oberfläche steht. */
fun sourceName(s: StampSource): String = when (s) {
    StampSource.NFC -> "NFC"
    StampSource.WLAN -> "WLAN"
    StampSource.GEOFENCE -> "Standort"
    StampSource.MANUELL -> "Manuell"
}

@Composable
fun sourceColor(s: StampSource): Color = when (s) {
    StampSource.NFC -> App.colors.violet
    StampSource.WLAN -> App.colors.info
    StampSource.GEOFENCE -> App.colors.good
    StampSource.MANUELL -> App.colors.warn
}

@DrawableRes
fun typeIcon(t: StampType): Int = if (t == StampType.IN) R.drawable.ic_in else R.drawable.ic_out

@Composable
fun typeColor(t: StampType): Color = if (t == StampType.IN) App.colors.good else App.colors.bad

// ---------------------------------------------------------------------------------------------
// Flächen und Überschriften
// ---------------------------------------------------------------------------------------------

/** Karte im App-Stil: abgerundet, flach, hebt sich durch die Farbe vom Hintergrund ab. */
@Composable
fun AppCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    onClickLabel: String? = null,
    container: Color = MaterialTheme.colorScheme.surfaceContainerLow,
    padding: Dp = 18.dp,
    spacing: Dp = 10.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .background(container)
            .then(
                if (onClick != null) {
                    Modifier.clickable(onClickLabel = onClickLabel, role = Role.Button, onClick = onClick)
                } else {
                    Modifier
                },
            )
            .padding(padding),
        verticalArrangement = Arrangement.spacedBy(spacing),
        content = content,
    )
}

/** Großer Titel oben auf jedem Haupt-Bildschirm, rechts optional Aktionen. */
@Composable
fun ScreenHeader(
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(modifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.headlineLarge,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.semantics { heading() },
            )
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        actions()
    }
}

/** Kleine Überschrift über einer Gruppe von Karten. */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier, trailing: @Composable RowScope.() -> Unit = {}) {
    Row(modifier.fillMaxWidth().padding(start = 6.dp, end = 2.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f).semantics { heading() },
        )
        trailing()
    }
}

/** Überschrift innerhalb einer Karte. */
@Composable
fun CardTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = modifier.semantics { heading() },
    )
}

/** Sehr kleine Beschriftung über einem Wert (z. B. „Kommen“ über der Uhrzeit). */
@Composable
fun Caption(text: String, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Text(text, style = MaterialTheme.typography.labelSmall, color = color, modifier = modifier, maxLines = 1)
}

/** Schmale Leiste oben auf Unterseiten: Zurück-Pfeil, Titel, optional Aktionen. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppTopBar(title: String, onBack: () -> Unit, actions: @Composable RowScope.() -> Unit = {}) {
    TopAppBar(
        title = { Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        navigationIcon = {
            IconButton(onClick = onBack) { AppIcon(R.drawable.ic_back, "Zurück") }
        },
        actions = actions,
        // Der Abstand zur Statusleiste kommt schon vom umgebenden Gerüst.
        windowInsets = WindowInsets(0, 0, 0, 0),
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.background,
            scrolledContainerColor = MaterialTheme.colorScheme.background,
        ),
    )
}

// ---------------------------------------------------------------------------------------------
// Etiketten, Hinweise, leere Zustände
// ---------------------------------------------------------------------------------------------

/** Abgerundetes Etikett, z. B. für Status oder Quelle. Die Farbe ist nie die einzige Aussage – der Text sagt dasselbe. */
@Composable
fun Pill(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
    container: Color = color.copy(alpha = 0.12f),
    @DrawableRes icon: Int? = null,
) {
    Row(
        modifier
            .clip(CircleShape)
            .background(container)
            .padding(start = if (icon != null) 7.dp else 10.dp, end = 10.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            AppIcon(icon, tint = color, size = 14.dp)
            Spacer(Modifier.width(4.dp))
        }
        Text(text, color = color, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Beschriftung eines Filter-Chips: immer einzeilig (die Chip-Reihe scrollt notfalls waagerecht). */
@Composable
fun ChipLabel(text: String) {
    Text(text, maxLines = 1, softWrap = false)
}

/** Etikett mit Icon und Namen einer Quelle. */
@Composable
fun SourcePill(source: StampSource, modifier: Modifier = Modifier) {
    Pill(sourceName(source), sourceColor(source), modifier, icon = sourceIcon(source))
}

/** Kompakter Hinweis über die ganze Breite: Icon, Titel, kurzer Text, Pfeil. */
@Composable
fun Banner(
    @DrawableRes icon: Int,
    title: String,
    text: String,
    color: Color,
    container: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .background(container)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppIcon(icon, tint = color, size = 24.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
            Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        AppIcon(R.drawable.ic_chevron_right, tint = color, size = 22.dp)
    }
}

/** Erklärender Text mit Info-Icon. */
@Composable
fun Hint(text: String, modifier: Modifier = Modifier, @DrawableRes icon: Int = R.drawable.ic_info) {
    Row(modifier.fillMaxWidth()) {
        AppIcon(icon, tint = MaterialTheme.colorScheme.onSurfaceVariant, size = 18.dp, modifier = Modifier.padding(top = 1.dp))
        Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Leerer Zustand: Icon, Titel, Erklärung und optional eine Aktion. */
@Composable
fun EmptyState(
    @DrawableRes icon: Int,
    title: String,
    text: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    AppCard(modifier, padding = 24.dp) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            IconBubble(icon, MaterialTheme.colorScheme.primary, size = 64.dp, shape = CircleShape)
            Spacer(Modifier.height(16.dp))
            Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
            Text(
                text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 6.dp),
            )
            if (action != null) {
                Spacer(Modifier.height(18.dp))
                action()
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Buttons und Auswahl
// ---------------------------------------------------------------------------------------------

private val ButtonHeight = 52.dp
private val ButtonPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp)

@Composable
private fun ButtonContent(text: String, @DrawableRes icon: Int?) {
    if (icon != null) {
        AppIcon(icon, size = 20.dp)
        Spacer(Modifier.width(8.dp))
    }
    Text(text, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

/** Die eine wichtigste Aktion eines Bildschirms. */
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    @DrawableRes icon: Int? = null,
    enabled: Boolean = true,
    container: Color = MaterialTheme.colorScheme.primary,
    content: Color = MaterialTheme.colorScheme.onPrimary,
) {
    Button(
        onClick = onClick,
        modifier = modifier.heightIn(min = ButtonHeight),
        enabled = enabled,
        contentPadding = ButtonPadding,
        colors = ButtonDefaults.buttonColors(containerColor = container, contentColor = content),
    ) { ButtonContent(text, icon) }
}

/** Zweitwichtigste Aktion: getönt statt gefüllt. [compact] für Aktionen neben einer Überschrift. */
@Composable
fun TonalButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    @DrawableRes icon: Int? = null,
    enabled: Boolean = true,
    compact: Boolean = false,
) {
    FilledTonalButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = if (compact) 40.dp else ButtonHeight),
        enabled = enabled,
        contentPadding = if (compact) PaddingValues(start = 12.dp, end = 16.dp) else ButtonPadding,
    ) { ButtonContent(text, icon) }
}

/** Nebenaktion mit Rahmen. [danger] färbt sie rot (Löschen). */
@Composable
fun QuietButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    @DrawableRes icon: Int? = null,
    enabled: Boolean = true,
    danger: Boolean = false,
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = ButtonHeight),
        enabled = enabled,
        contentPadding = ButtonPadding,
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
        ),
    ) { ButtonContent(text, icon) }
}

/** Auswahl zwischen wenigen gleichwertigen Möglichkeiten (z. B. Kommen/Gehen). */
@Composable
fun <T> SegmentedChoice(
    options: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    label: (T) -> String,
    modifier: Modifier = Modifier,
    icon: ((T) -> Int)? = null,
    selectedColor: @Composable (T) -> Color = { MaterialTheme.colorScheme.primary },
) {
    Row(
        modifier
            .fillMaxWidth()
            .clip(CircleShape)
            .background(App.colors.inset)
            .padding(4.dp)
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        options.forEach { option ->
            val isSelected = option == selected
            val color = if (isSelected) selectedColor(option) else MaterialTheme.colorScheme.onSurfaceVariant
            Row(
                Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp)
                    .clip(CircleShape)
                    .background(if (isSelected) MaterialTheme.colorScheme.surfaceContainerLow else Color.Transparent)
                    .selectable(selected = isSelected, role = Role.RadioButton, onClick = { onSelect(option) })
                    .padding(horizontal = 10.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (icon != null) {
                    AppIcon(icon(option), tint = color, size = 18.dp)
                    Spacer(Modifier.width(6.dp))
                }
                Text(
                    label(option),
                    style = MaterialTheme.typography.labelLarge,
                    color = if (isSelected) color else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Listen-Zeilen (vor allem für die Optionen)
// ---------------------------------------------------------------------------------------------

/** Karte, die mehrere Zeilen untereinander zusammenfasst, getrennt durch feine Linien. */
@Composable
fun RowGroup(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .background(MaterialTheme.colorScheme.surfaceContainerLow),
        content = content,
    )
}

/** Feine Trennlinie zwischen zwei Zeilen einer [RowGroup], eingerückt bis zum Text. */
@Composable
fun RowDivider(startIndent: Dp = 70.dp) {
    HorizontalDivider(Modifier.padding(start = startIndent), color = MaterialTheme.colorScheme.outlineVariant)
}

/**
 * Zeile mit Icon, Titel, optionalem Untertitel und einem Element rechts (Pfeil, Wert, Schalter …).
 * Mit [onClick] ist die ganze Zeile antippbar.
 */
@Composable
fun ListRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    @DrawableRes icon: Int? = null,
    iconColor: Color = MaterialTheme.colorScheme.primary,
    onClick: (() -> Unit)? = null,
    onClickLabel: String? = null,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier
            .fillMaxWidth()
            .then(
                if (onClick != null) {
                    Modifier.clickable(onClickLabel = onClickLabel, role = Role.Button, onClick = onClick)
                } else {
                    Modifier
                },
            )
            .heightIn(min = 64.dp)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            IconBubble(icon, iconColor)
            Spacer(Modifier.width(14.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        trailing()
    }
}

/** Pfeil nach rechts für Zeilen, die eine Unterseite öffnen. */
@Composable
fun Chevron() {
    AppIcon(R.drawable.ic_chevron_right, tint = MaterialTheme.colorScheme.outline, size = 22.dp)
}

/** Zeile mit Schalter; die ganze Zeile schaltet um. */
@Composable
fun SwitchRow(
    title: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    @DrawableRes icon: Int? = null,
    iconColor: Color = MaterialTheme.colorScheme.primary,
) {
    Row(
        modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
            .heightIn(min = 64.dp)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            IconBubble(icon, iconColor)
            Spacer(Modifier.width(14.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.width(12.dp))
        // Der Schalter selbst ist nur Anzeige – bedient wird die ganze Zeile.
        Switch(checked = checked, onCheckedChange = null, modifier = Modifier.clearAndSetSemantics { })
    }
}

/** Zahlenwert mit großen Minus/Plus-Knöpfen. */
@Composable
fun Stepper(
    value: String,
    onMinus: () -> Unit,
    onPlus: () -> Unit,
    modifier: Modifier = Modifier,
    canMinus: Boolean = true,
    canPlus: Boolean = true,
) {
    Row(
        modifier.fillMaxWidth().clip(CircleShape).background(App.colors.inset).padding(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FilledTonalIconButton(onClick = onMinus, enabled = canMinus, modifier = Modifier.size(48.dp)) {
            AppIcon(R.drawable.ic_remove, "Verringern")
        }
        Text(
            value,
            style = MaterialTheme.typography.headlineSmall.merge(TabularNumbers),
            textAlign = TextAlign.Center,
            modifier = Modifier.weight(1f),
        )
        FilledTonalIconButton(onClick = onPlus, enabled = canPlus, modifier = Modifier.size(48.dp)) {
            AppIcon(R.drawable.ic_add, "Erhöhen")
        }
    }
}

/** Wert am rechten Rand einer [ListRow] (antippbar zum Ändern). */
@Composable
fun RowValue(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall.merge(TabularNumbers),
        color = MaterialTheme.colorScheme.primary,
        maxLines = 1,
        modifier = Modifier.padding(start = 12.dp),
    )
}

/** Zeile „Beschriftung ……… Wert“. */
@Composable
fun InfoRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color = MaterialTheme.colorScheme.onSurface,
    bold: Boolean = false,
) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            Modifier.weight(1f),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.width(12.dp))
        Text(
            value,
            color = valueColor,
            style = MaterialTheme.typography.bodyMedium.merge(TabularNumbers),
            fontWeight = if (bold) FontWeight.Bold else FontWeight.SemiBold,
        )
    }
}

/** Punkt + Text: Status mit Farbe UND Wort. */
@Composable
fun StatusLine(text: String, color: Color, modifier: Modifier = Modifier, style: TextStyle = MaterialTheme.typography.labelSmall) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(5.dp))
        Text(text, style = style, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

// ---------------------------------------------------------------------------------------------
// Schriftgröße
// ---------------------------------------------------------------------------------------------

/**
 * Begrenzt die Schriftvergrößerung des Handys für Inhalte, deren Platz fest ist (Zifferblatt, Knöpfe mit fester
 * Breite). Überall sonst wächst die Schrift ungebremst mit und der Text bricht um.
 */
@Composable
fun LimitFontScale(max: Float, content: @Composable () -> Unit) {
    val density = LocalDensity.current
    CompositionLocalProvider(
        LocalDensity provides Density(density.density, density.fontScale.coerceAtMost(max)),
        content = content,
    )
}

// ---------------------------------------------------------------------------------------------
// Bewegung
// ---------------------------------------------------------------------------------------------

/** Blendet Inhalte beim ersten Anzeigen gestaffelt ein (kurz, damit nichts aufhält). */
@Composable
fun Appear(index: Int = 0, content: @Composable () -> Unit) {
    val state = remember { MutableTransitionState(false) }
    LaunchedEffect(Unit) {
        delay(35L * index.coerceAtMost(8))
        state.targetState = true
    }
    AnimatedVisibility(
        visibleState = state,
        enter = fadeIn(tween(260)) + slideInVertically(tween(320, easing = FastOutSlowInEasing)) { it / 8 },
    ) { content() }
}

/** Pulsierender Punkt für „läuft gerade“. */
@Composable
fun PulsingDot(color: Color, size: Dp = 10.dp) {
    val t = rememberInfiniteTransition(label = "pulse")
    val scale by t.animateFloat(
        initialValue = 0.6f,
        targetValue = 1.5f,
        animationSpec = infiniteRepeatable(tween(1100), RepeatMode.Reverse),
        label = "scale",
    )
    Box(Modifier.size(size * 1.8f), contentAlignment = Alignment.Center) {
        Box(Modifier.size(size).scale(scale).clip(CircleShape).background(color.copy(alpha = 0.3f)))
        Box(Modifier.size(size * 0.7f).clip(CircleShape).background(color))
    }
}

/** Text, der bei Änderung weich nach oben rollt (z. B. Uhrzeiten, Zähler). */
@Composable
fun RollingText(text: String, style: TextStyle, modifier: Modifier = Modifier, color: Color = Color.Unspecified) {
    AnimatedContent(
        targetState = text,
        transitionSpec = {
            (slideInVertically(tween(220)) { it / 2 } + fadeIn(tween(220))) togetherWith
                (slideOutVertically(tween(150)) { -it / 2 } + fadeOut(tween(150)))
        },
        label = "rolling",
        modifier = modifier,
    ) { value -> Text(value, style = style.merge(TabularNumbers), color = color, maxLines = 1) }
}

/** Drückt sich beim Antippen leicht ein – ohne das Layout zu verschieben. */
@Composable
fun Modifier.pressScale(interaction: MutableInteractionSource): Modifier {
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.96f else 1f, spring(stiffness = Spring.StiffnessMediumLow), label = "press")
    return this.scale(scale)
}

// ---------------------------------------------------------------------------------------------
// Fortschritt
// ---------------------------------------------------------------------------------------------

/** Fortschrittsring, der beim Anzeigen von 0 auf den Wert läuft. Der Wert steht zusätzlich als Text in der Mitte. */
@Composable
fun ProgressRing(
    progress: Float,
    color: Color,
    modifier: Modifier = Modifier,
    size: Dp = 140.dp,
    stroke: Dp = 14.dp,
    track: Color = MaterialTheme.colorScheme.surfaceVariant,
    center: @Composable () -> Unit,
) {
    val anim = remember { Animatable(0f) }
    LaunchedEffect(progress) {
        anim.animateTo(progress.coerceIn(0f, 1f), spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessVeryLow))
    }
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val s = stroke.toPx()
            val arcSize = Size(this.size.width - s, this.size.height - s)
            val topLeft = Offset(s / 2, s / 2)
            drawArc(track, -90f, 360f, false, topLeft, arcSize, style = Stroke(s, cap = StrokeCap.Round))
            if (anim.value > 0.001f) {
                drawArc(color, -90f, 360f * anim.value, false, topLeft, arcSize, style = Stroke(s, cap = StrokeCap.Round))
            }
        }
        center()
    }
}

/** Waagerechter Fortschrittsbalken mit Animation. */
@Composable
fun ProgressBar(
    progress: Float,
    color: Color,
    modifier: Modifier = Modifier,
    height: Dp = 8.dp,
    track: Color = MaterialTheme.colorScheme.surfaceVariant,
) {
    val anim = remember { Animatable(0f) }
    LaunchedEffect(progress) { anim.animateTo(progress.coerceIn(0f, 1f), tween(600, easing = FastOutSlowInEasing)) }
    Canvas(modifier.fillMaxWidth().height(height)) {
        val r = this.size.height / 2
        drawRoundRect(track, cornerRadius = CornerRadius(r, r))
        if (anim.value > 0f) {
            drawRoundRect(
                color,
                size = Size((this.size.width * anim.value).coerceAtLeast(this.size.height), this.size.height),
                cornerRadius = CornerRadius(r, r),
            )
        }
    }
}

/** Beschreibt eine rein gezeichnete Grafik für Screenreader. */
fun Modifier.describedAs(text: String): Modifier = this.semantics { contentDescription = text }
