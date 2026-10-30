package io.github.sandroroque.getnf.ui.calmo

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.sandroroque.getnf.theme.Calmo
import io.github.sandroroque.getnf.theme.Display
import io.github.sandroroque.getnf.theme.DisplaySmall
import io.github.sandroroque.getnf.theme.Title

/*
 * The building blocks of the "Calmo" screens (D81). A screen opens with one large number or title; lists are rows of
 * name and amount on hairlines; color appears only as a category dot; who set a value is a small mark; decisions are
 * cards whose answers are buttons.
 */

// ---- icons: line drawings on a 24-unit grid, from the mockups ----

private fun line(name: String, vararg paths: String) = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
  for (d in paths) addPath(addPathNodes(d), stroke = SolidColor(Color.Black), strokeLineWidth = 1.7f,
    strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round, fill = null)
}.build()

object Icons {
  val Home = line("home", "M3 10.5 12 3l9 7.5V20a1 1 0 0 1-1 1h-5v-6H9v6H4a1 1 0 0 1-1-1z")
  val Chart = line("chart", "M4 20V10M10 20V4M16 20v-7M22 20H2")
  val Inbox = line("inbox", "M4 13h4l2 3h4l2-3h4M5 5h14l1 8v6H4v-6z")
  val Grid = line("grid", "M5 3.5h4a1.5 1.5 0 0 1 1.5 1.5v4A1.5 1.5 0 0 1 9 10.5H5A1.5 1.5 0 0 1 3.5 9V5A1.5 1.5 0 0 1 5 3.5z",
    "M15 3.5h4a1.5 1.5 0 0 1 1.5 1.5v4a1.5 1.5 0 0 1-1.5 1.5h-4A1.5 1.5 0 0 1 13.5 9V5A1.5 1.5 0 0 1 15 3.5z",
    "M5 13.5h4a1.5 1.5 0 0 1 1.5 1.5v4A1.5 1.5 0 0 1 9 20.5H5A1.5 1.5 0 0 1 3.5 19v-4A1.5 1.5 0 0 1 5 13.5z",
    "M15 13.5h4a1.5 1.5 0 0 1 1.5 1.5v4a1.5 1.5 0 0 1-1.5 1.5h-4a1.5 1.5 0 0 1-1.5-1.5v-4a1.5 1.5 0 0 1 1.5-1.5z")
  // The receipt, as in the app's icon (D118).
  val Scan = line("scan", "M6 3h12v18l-2-1.5-2 1.5-2-1.5-2 1.5-2-1.5L6 21z", "M9 8h6M9 11.5h6M9 15h3.5")
  val Back = line("back", "M15 5l-7 7 7 7")
  val Search = line("search", "M17.5 11a6.5 6.5 0 1 1-13 0 6.5 6.5 0 0 1 13 0zM16 16l4.5 4.5")
  val Chevron = line("chevron", "M9 5l7 7-7 7")
  val Close = line("close", "M6 6l12 12M18 6L6 18")
  val Torch = line("torch", "M13 3L5 13h6l-1 8 8-10h-6l1-8z")
  val More = line("more", "M5 11.2a.8.8 0 1 1 0 1.6.8.8 0 0 1 0-1.6zM12 11.2a.8.8 0 1 1 0 1.6.8.8 0 0 1 0-1.6zM19 11.2a.8.8 0 1 1 0 1.6.8.8 0 0 1 0-1.6z")
  val Focus = line("focus", "M12 3v4M12 17v4M3 12h4M17 12h4M15 12a3 3 0 1 1-6 0 3 3 0 0 1 6 0z")
  val Arrow = line("arrow", "M12 19V5M5.5 11.5 12 5l6.5 6.5")
  val Check = line("check", "M5 12.5l4.5 4.5L19 7")
  val Eye = line("eye", "M2.5 12S6 5.5 12 5.5 21.5 12 21.5 12 18 18.5 12 18.5 2.5 12 2.5 12z", "M14.8 12a2.8 2.8 0 1 1-5.6 0 2.8 2.8 0 0 1 5.6 0z")
  val EyeOff = line("eye-off", "M4 4l16 16", "M9.9 5.8A9.6 9.6 0 0 1 12 5.5C18 5.5 21.5 12 21.5 12a17 17 0 0 1-2.6 3.4M6.4 6.9C3.9 8.6 2.5 12 2.5 12S6 18.5 12 18.5c1.6 0 3-.4 4.2-1", "M10 10a2.8 2.8 0 0 0 4 4")
  val Mail = line("mail", "M3.5 6h17v12h-17z", "M3.8 6.4 12 13l8.2-6.6")
  val Sync = line("sync", "M19.5 12a7.5 7.5 0 0 1-13 5.1M4.5 12a7.5 7.5 0 0 1 13-5.1", "M17.5 3v4h-4M6.5 21v-4h4")
  val Person = line("person", "M15.5 8a3.5 3.5 0 1 1-7 0 3.5 3.5 0 0 1 7 0zM5 20c.8-3.4 3.6-5.5 7-5.5s6.2 2.1 7 5.5")
}

// ---- type helpers ----

/** Digits that line up in columns. */
val Tabular = TextStyle(fontFeatureSettings = "tnum")

@Composable fun BigNumber(text: String, modifier: Modifier = Modifier, color: Color = Calmo.fg) =
  Text(text, modifier, style = Display.merge(Tabular), color = color)

@Composable fun MidNumber(text: String, modifier: Modifier = Modifier, color: Color = Calmo.fg) =
  Text(text, modifier, style = DisplaySmall.merge(Tabular), color = color)

@Composable fun ScreenTitle(text: String, modifier: Modifier = Modifier) = Text(text, modifier, style = Title, color = Calmo.fg)

@Composable fun Sub(text: String, modifier: Modifier = Modifier) =
  Text(text, modifier, style = MaterialTheme.typography.bodySmall, color = Calmo.mute)

@Composable fun SectionLabel(text: String, modifier: Modifier = Modifier) =
  Text(text.uppercase(), modifier, style = MaterialTheme.typography.labelSmall, color = Calmo.mute)

// ---- structure ----

@Composable fun Hairline(modifier: Modifier = Modifier) =
  Box(modifier.fillMaxWidth().height(1.dp).background(Calmo.line))

/** A tab screen's header: its title, and something at the right (the account, a count). */
@Composable
fun TabHeader(title: String, trailing: @Composable RowScope.() -> Unit = {}) {
  Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
    ScreenTitle(title, Modifier.weight(1f))
    trailing()
  }
}

/** A detail screen's header: back to where you came from, and an action at the right. */
@Composable
fun BackHeader(label: String, onBack: () -> Unit, trailing: @Composable RowScope.() -> Unit = {}) {
  Row(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
    Row(Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onBack).padding(vertical = 8.dp, horizontal = 2.dp),
      verticalAlignment = Alignment.CenterVertically) {
      Icon(Icons.Back, null, Modifier.size(18.dp), tint = Calmo.fg)
      Text(label, style = MaterialTheme.typography.bodyMedium, color = Calmo.fg)
    }
    Spacer(Modifier.weight(1f))
    trailing()
  }
}

/** A small round initial: the way to Ajustes. */
@Composable
fun AccountBadge(initial: String?, onClick: () -> Unit) {
  Box(Modifier.size(32.dp).clip(CircleShape).border(1.dp, Calmo.line, CircleShape).clickable(onClick = onClick),
    contentAlignment = Alignment.Center) {
    // Signed out there's no initial: a person outline instead.
    if (initial == null) Icon(Icons.Person, "Ajustes", Modifier.size(18.dp), tint = Calmo.fg)
    else Text(initial.uppercase(), style = MaterialTheme.typography.labelMedium, color = Calmo.fg)
  }
}

@Composable fun Dot(color: Color, size: Int = 8) = Canvas(Modifier.size(size.dp)) { drawCircle(color) }

/**
 * A row: optional category dot, a title with an optional line under it, and the amount at the right. Tappable when
 * [onClick] is given, with a chevron.
 */
@Composable
fun AmountRow(
  title: String, amount: String?, modifier: Modifier = Modifier, subtitle: String? = null, dot: Color? = null,
  onClick: (() -> Unit)? = null, trailingMark: String? = null,
) {
  Column(modifier) {
    Row(
      Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).padding(vertical = 11.dp),
      verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      if (dot != null) Dot(dot)
      Column(Modifier.weight(1f)) {
        Text(title, style = MaterialTheme.typography.bodyMedium, color = Calmo.fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Calmo.mute, maxLines = 1, overflow = TextOverflow.Ellipsis)
      }
      if (trailingMark != null) Mark(trailingMark)
      if (amount != null) Text(amount, style = MaterialTheme.typography.bodyMedium.merge(Tabular), color = Calmo.fg)
      if (onClick != null) Icon(Icons.Chevron, null, Modifier.size(14.dp), tint = Calmo.mute)
    }
    Hairline()
  }
}

/** A label and a value on one line, for a detail screen's facts. */
@Composable
fun FactRow(label: String, value: String, mark: String? = null, onClick: (() -> Unit)? = null) {
  Column {
    Row(Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).padding(vertical = 11.dp),
      verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      Text(label, style = MaterialTheme.typography.bodySmall, color = Calmo.mute, modifier = Modifier.width(96.dp))
      Text(value, style = MaterialTheme.typography.bodyMedium.merge(Tabular), color = Calmo.fg, modifier = Modifier.weight(1f),
        maxLines = 2, overflow = TextOverflow.Ellipsis)
      if (mark != null) Mark(mark, you = mark == "você")
      if (onClick != null) Icon(Icons.Chevron, null, Modifier.size(14.dp), tint = Calmo.mute)
    }
    Hairline()
  }
}

/** Who set a value: "você", "auto", "revisor", "da foto", "rateio", "da nota" (D112). */
@Composable
fun Mark(text: String, you: Boolean = false) {
  val color = if (you) Calmo.acc else Calmo.mute
  Text(text.uppercase(), style = MaterialTheme.typography.labelSmall, color = color,
    modifier = Modifier.border(1.dp, if (you) Calmo.acc else Calmo.line, RoundedCornerShape(4.dp)).padding(horizontal = 4.dp))
}

/** Text tabs with an underline under the selected one. */
@Composable
fun Segments(options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
  Column {
    Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
      options.forEachIndexed { i, label ->
        Column(Modifier.clickable { onSelect(i) }.padding(top = 6.dp)) {
          Text(label, style = MaterialTheme.typography.bodyMedium, color = if (i == selected) Calmo.fg else Calmo.mute)
          Spacer(Modifier.height(7.dp))
          Box(Modifier.height(2.dp).width(if (i == selected) 24.dp else 0.dp).background(Calmo.fg))
        }
      }
    }
    Hairline()
  }
}

enum class ButtonKind { Solid, Outline, Ghost }

/** A pill button; an optional [mark] says whose answer it is. */
@Composable
fun Pill(
  label: String, kind: ButtonKind = ButtonKind.Outline, mark: String? = null, enabled: Boolean = true,
  modifier: Modifier = Modifier, onClick: () -> Unit,
) {
  val shape = RoundedCornerShape(999.dp)
  val (bg, fg, border) = when (kind) {
    // A disabled solid button goes quiet instead of keeping its weight.
    ButtonKind.Solid -> if (enabled) Triple(Calmo.fg, Calmo.bg, Calmo.fg) else Triple(Calmo.line, Calmo.mute, Calmo.line)
    ButtonKind.Outline -> Triple(Color.Transparent, Calmo.fg, Calmo.fg)
    ButtonKind.Ghost -> Triple(Color.Transparent, Calmo.mute, Calmo.line)
  }
  Row(
    modifier.clip(shape).background(bg).border(1.dp, border, shape).clickable(enabled = enabled, onClick = onClick)
      .padding(horizontal = 14.dp, vertical = 8.dp),
    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
  ) {
    Text(label, style = MaterialTheme.typography.labelLarge, color = if (enabled) fg else Calmo.mute)
    if (mark != null) Text(mark.uppercase(), style = MaterialTheme.typography.labelSmall, color = fg.copy(alpha = .7f))
  }
}

/** One decision: what it's about (and the money involved), why it's asked, and its answers as buttons. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DecisionCard(
  title: String, amount: String?, why: String, modifier: Modifier = Modifier, onOpen: (() -> Unit)? = null,
  extra: @Composable () -> Unit = {}, answers: @Composable () -> Unit,
) {
  val shape = RoundedCornerShape(14.dp)
  Column(
    modifier.fillMaxWidth().clip(shape).background(Calmo.sheet).border(1.dp, Calmo.line, shape)
      .then(if (onOpen != null) Modifier.clickable(onClick = onOpen) else Modifier).padding(horizontal = 14.dp, vertical = 12.dp),
    verticalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      Text(title, style = MaterialTheme.typography.bodyMedium, color = Calmo.fg, modifier = Modifier.weight(1f))
      if (amount != null) Text(amount, style = MaterialTheme.typography.bodyMedium.merge(Tabular), color = Calmo.fg)
    }
    Text(why, style = MaterialTheme.typography.bodySmall, color = Calmo.mute)
    extra()
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { answers() }
  }
}

/** A search field drawn as the mockups' quiet box. */
@Composable
fun SearchBox(value: String, onValue: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier) {
  val shape = RoundedCornerShape(10.dp)
  Row(modifier.fillMaxWidth().border(1.dp, Calmo.line, shape).padding(horizontal = 10.dp, vertical = 9.dp),
    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    Icon(Icons.Search, null, Modifier.size(16.dp), tint = Calmo.mute)
    Box(Modifier.weight(1f)) {
      if (value.isEmpty()) Text(placeholder, style = MaterialTheme.typography.bodyMedium, color = Calmo.mute)
      BasicTextField(value, onValue, singleLine = true, textStyle = MaterialTheme.typography.bodyMedium.copy(color = Calmo.fg),
        cursorBrush = SolidColor(Calmo.fg), modifier = Modifier.fillMaxWidth())
    }
  }
}

/** What will appear here, and how to make it appear. */
@Composable
fun EmptyState(text: String, modifier: Modifier = Modifier) =
  Text(text, modifier.fillMaxWidth().padding(vertical = 24.dp), style = MaterialTheme.typography.bodyMedium, color = Calmo.mute)
