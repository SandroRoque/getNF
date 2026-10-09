package io.github.sandroroque.getnf.ui.page

import android.content.Context
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import io.github.sandroroque.getnf.GetNFApp
import io.github.sandroroque.getnf.data.CurrentNetwork
import io.github.sandroroque.getnf.data.LookupBudget
import java.text.DateFormat
import java.util.Date

/** One line about the SEFAZ lookup budget on [network], for the home screen and the dialog. */
fun budgetText(network: CurrentNetwork, status: LookupBudget.Status): String {
  val at = status.nextFreeAt?.let { DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(it)) }
  return when {
    status.blocked -> "SEFAZ is blocking ${network.label}. Try again after $at, or switch networks."
    status.remaining == 0 -> "SEFAZ budget used up on ${network.label} (${LookupBudget.LIMIT}/hour). Next slot at $at."
    else -> "${status.remaining} of ${LookupBudget.LIMIT} SEFAZ lookups left this hour on ${network.label}."
  }
}

/**
 * Asks before a page fetch that would probably get this network blocked (docs/sefaz-sc.md, D17). Only advises:
 * "Fetch anyway" is always there, e.g. after switching to another SIM. Place [Dialog] somewhere in the screen.
 */
class BudgetCheck internal constructor(private val context: Context) {
  private class Prompt(val text: String, val onFetch: () -> Unit, val onLater: () -> Unit)

  private var prompt by mutableStateOf<Prompt?>(null)

  fun request(onFetch: () -> Unit, onLater: () -> Unit) {
    val network = CurrentNetwork.of(context)
    val status = (context.applicationContext as GetNFApp).lookupBudget.status(network.key)
    if (status.remaining > 0) onFetch() else prompt = Prompt(budgetText(network, status), onFetch, onLater)
  }

  @Composable
  fun Dialog() {
    val p = prompt ?: return
    AlertDialog(
      onDismissRequest = { prompt = null },
      title = { Text("Fetch the page later?") },
      text = {
        Text(
          p.text +
            "\n\nThe photos are saved either way. If you switch to a different network (another SIM, or Wi-Fi vs mobile " +
            "data), you can fetch now."
        )
      },
      confirmButton = {
        TextButton(
          onClick = {
            prompt = null
            p.onLater()
          }
        ) {
          Text("Later")
        }
      },
      dismissButton = {
        TextButton(
          onClick = {
            prompt = null
            p.onFetch()
          }
        ) {
          Text("Fetch anyway")
        }
      },
    )
  }
}

@Composable
fun rememberBudgetCheck(): BudgetCheck {
  val context = LocalContext.current
  return remember { BudgetCheck(context) }
}
