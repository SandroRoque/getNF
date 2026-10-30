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
import io.github.sandroroque.getnf.ui.Format

/** One line about the SEFAZ lookup budget on [network], for the home screen and the dialog. */
fun budgetText(network: CurrentNetwork, status: LookupBudget.Status): String {
  val at = status.nextFreeAt?.let { Format.time(it) }
  return when {
    status.blocked -> "A SEFAZ está bloqueando ${network.label}. Tente depois das $at, ou troque de rede."
    status.remaining == 0 -> "Consultas à SEFAZ esgotadas em ${network.label} (${LookupBudget.LIMIT} por hora). Próxima às $at."
    else -> "${status.remaining} de ${LookupBudget.LIMIT} consultas à SEFAZ livres nesta hora, em ${network.label}."
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
      title = { Text("Buscar a página depois?") },
      text = {
        Text(
          p.text +
            "\n\nAs fotos ficam salvas de qualquer jeito. Em outra rede (outro chip, ou Wi-Fi em vez de dados móveis), " +
            "dá para buscar agora."
        )
      },
      confirmButton = {
        TextButton(
          onClick = {
            prompt = null
            p.onLater()
          }
        ) {
          Text("Depois")
        }
      },
      dismissButton = {
        TextButton(
          onClick = {
            prompt = null
            p.onFetch()
          }
        ) {
          Text("Buscar mesmo assim")
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
