package io.github.sandroroque.getnf.ui.calmo

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import io.github.sandroroque.getnf.theme.Calmo

/*
 * Forms and settings in the Calmo look (D123): fields, wide buttons, switch rows and the one card a screen may lead
 * with. Ajustes and the account screens are built from these, not from Material's defaults.
 */

/** A pill as wide as the screen, for the one thing to do. */
@Composable
fun Wide(label: String, kind: ButtonKind, onClick: () -> Unit) = Wide(label, kind, enabled = true, onClick = onClick)

@Composable
fun Wide(label: String, kind: ButtonKind, enabled: Boolean, onClick: () -> Unit) {
  Pill(label, kind, enabled = enabled, modifier = Modifier.fillMaxWidth().height(48.dp), onClick = onClick)
}

@Composable
fun TextLink(label: String, onClick: () -> Unit) = TextLink(label, Modifier, onClick)

@Composable
fun TextLink(label: String, modifier: Modifier, onClick: () -> Unit) {
  Text(label, style = MaterialTheme.typography.labelLarge, color = Calmo.fg,
    modifier = modifier.clip(CircleShape).clickable(onClick = onClick).padding(horizontal = 6.dp, vertical = 8.dp))
}

/**
 * A text field: the label above while typing, an [error] in its place under the field (or a [hint] when there's
 * none). A [password] field hides what's typed, with an eye to show it.
 */
@Composable
fun Field(
  value: String, onValue: (String) -> Unit, label: String, modifier: Modifier = Modifier,
  type: KeyboardType = KeyboardType.Text, password: Boolean = false, error: String? = null, hint: String? = null,
  last: Boolean = false, onDone: () -> Unit = {},
) {
  var shown by remember { mutableStateOf(false) }
  OutlinedTextField(
    value, onValue, modifier.fillMaxWidth(), singleLine = true,
    label = { Text(label) },
    isError = error != null,
    supportingText = (error ?: hint)?.let { { Text(it) } },
    visualTransformation = if (password && !shown) PasswordVisualTransformation() else VisualTransformation.None,
    trailingIcon = if (password) ({
      Box(Modifier.clip(CircleShape).clickable { shown = !shown }.padding(10.dp)) {
        Icon(if (shown) Icons.EyeOff else Icons.Eye, if (shown) "Esconder a senha" else "Mostrar a senha", Modifier.size(20.dp), tint = Calmo.mute)
      }
    }) else null,
    keyboardOptions = KeyboardOptions(keyboardType = if (password) KeyboardType.Password else type,
      imeAction = if (last) ImeAction.Done else ImeAction.Next),
    keyboardActions = KeyboardActions(onDone = { onDone() }),
    shape = RoundedCornerShape(12.dp),
    colors = OutlinedTextFieldDefaults.colors(
      focusedBorderColor = Calmo.fg, unfocusedBorderColor = Calmo.line, focusedLabelColor = Calmo.fg,
      unfocusedLabelColor = Calmo.mute, cursorColor = Calmo.fg, focusedTextColor = Calmo.fg, unfocusedTextColor = Calmo.fg,
      focusedSupportingTextColor = Calmo.mute, unfocusedSupportingTextColor = Calmo.mute,
    ),
  )
}

/** A setting that is on or off: what it does, a line under it, and the switch. */
@Composable
fun SwitchRow(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
  Column {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 10.dp),
      verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
      Column(Modifier.weight(1f)) {
        Text(title, style = MaterialTheme.typography.bodyMedium, color = Calmo.fg)
        if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Calmo.mute)
      }
      Switch(checked, onChange, colors = SwitchDefaults.colors(
        checkedThumbColor = Calmo.bg, checkedTrackColor = Calmo.fg, checkedBorderColor = Calmo.fg,
        uncheckedThumbColor = Calmo.mute, uncheckedTrackColor = Calmo.bg, uncheckedBorderColor = Calmo.line,
      ))
    }
    Hairline()
  }
}

/** The card a screen may lead with (the account in Ajustes); everything else sits on hairlines. */
@Composable
fun LeadCard(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
  val shape = RoundedCornerShape(16.dp)
  Column(
    modifier.fillMaxWidth().clip(shape).background(Calmo.sheet).border(1.dp, Calmo.line, shape)
      .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).padding(16.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp), content = content,
  )
}

/** A round initial, or a person outline when there's no name. */
@Composable
fun Avatar(initial: String?, size: Int = 44) {
  Box(Modifier.size(size.dp).clip(CircleShape).border(1.dp, Calmo.fg, CircleShape), contentAlignment = Alignment.Center) {
    if (initial.isNullOrBlank()) Icon(Icons.Person, null, Modifier.size((size * 0.5).dp), tint = Calmo.fg)
    else Text(initial.take(1).uppercase(), style = MaterialTheme.typography.titleMedium, color = Calmo.fg)
  }
}

/** A message after an action, in a quiet box: what happened, or what went wrong and what to do. */
@Composable
fun Notice(text: String, error: Boolean = false) {
  val shape = RoundedCornerShape(12.dp)
  Text(text, style = MaterialTheme.typography.bodyMedium, color = if (error) MaterialTheme.colorScheme.error else Calmo.fg,
    modifier = Modifier.fillMaxWidth().clip(shape).background(Calmo.line.copy(alpha = .35f)).padding(horizontal = 14.dp, vertical = 10.dp))
}
