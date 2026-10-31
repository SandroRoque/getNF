package io.github.sandroroque.getnf.ui.scan

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.sandroroque.getnf.data.LookupResult
import io.github.sandroroque.getnf.data.ProductSighting
import io.github.sandroroque.getnf.data.StorePrice
import io.github.sandroroque.getnf.theme.Calmo
import io.github.sandroroque.getnf.ui.calmo.Tabular
import io.github.sandroroque.getnf.ui.Format
import io.github.sandroroque.getnf.ui.calmo.AmountRow
import io.github.sandroroque.getnf.ui.calmo.ButtonKind
import io.github.sandroroque.getnf.ui.calmo.SectionLabel
import io.github.sandroroque.getnf.ui.calmo.Sub
import io.github.sandroroque.getnf.ui.calmo.Wide
import io.github.sandroroque.getnf.ui.catalog.CalmoSheet

/**
 * What a product's barcode turned up (camera plan, step 3), in a sheet over the camera, which keeps looking: another
 * code replaces it. [open] is the open caderneta's id (null without an account); [names] the cadernetas' names, when
 * known. "Ver o produto" only for the open caderneta's product: another caderneta's would need switching to it.
 */
@Composable
internal fun ProductSheet(result: LookupResult?, open: String?, names: Map<String, String>, onOpenProduct: (String) -> Unit, onDismiss: () -> Unit) {
  when (result) {
    null -> CalmoSheet("Procurando…", onDismiss) { Sub("Nas notas de todas as cadernetas deste celular.") }
    is LookupResult.Found -> {
      val here = result.sightings.firstOrNull { it.ledger == open }
      val first = here ?: result.sightings.first()
      CalmoSheet(first.name, onDismiss) {
        Sightings(first, if (here == null) names[first.ledger] ?: "Outra caderneta" else null)
        val others = result.sightings.filter { it !== first }
        if (others.isNotEmpty()) {
          SectionLabel("Em outras cadernetas")
          Column {
            for (s in others) AmountRow(
              title = names[s.ledger] ?: "Outra caderneta", amount = price(s.last.cents, s.basis),
              subtitle = "${Format.date(s.last.at)} · ${s.last.storeName ?: "loja sem nome"}",
            )
          }
        }
        if (here != null) Wide("Ver o produto", ButtonKind.Outline) { onOpenProduct(here.productId) }
        Wide("Fechar", ButtonKind.Ghost, onClick = onDismiss)
      }
    }
    is LookupResult.NeverBought -> CalmoSheet("Você ainda não comprou este produto", onDismiss) {
      Sub("Nenhuma nota deste celular tem o código ${result.code}. Quando ele aparecer numa nota, o preço que você pagou fica aqui.")
      Wide("Fechar", ButtonKind.Ghost, onClick = onDismiss)
    }
    is LookupResult.ScaleLabel -> CalmoSheet("Etiqueta da balança da loja", onDismiss) {
      Sub("O código desta etiqueta muda a cada pesagem, então não dá para procurar por ele. Nas notas, o produto aparece pelo nome: procure-o no Catálogo.")
      Wide("Fechar", ButtonKind.Ghost, onClick = onDismiss)
    }
    is LookupResult.NotAProduct -> CalmoSheet("Não é o código de um produto", onDismiss) {
      Sub("O código ${result.code} é da própria loja, não de um produto que se encontre em outras.")
      Wide("Fechar", ButtonKind.Ghost, onClick = onDismiss)
    }
  }
}

/** The product in one caderneta: the last price, each store's latest (the cheapest marked) and the recent average. */
@Composable
private fun Sightings(s: ProductSighting, elsewhere: String?) {
  Sub(listOfNotNull(s.categoryLabel ?: "Sem categoria", elsewhere).joinToString(" · "))
  Column {
    Text(price(s.last.cents, s.basis), style = MaterialTheme.typography.headlineMedium.merge(Tabular), color = Calmo.fg)
    Sub("Última compra: ${Format.date(s.last.at)}, ${s.last.storeName ?: "loja sem nome"}")
  }
  if (s.byStore.size > 1) {
    Spacer(Modifier.height(4.dp))
    SectionLabel("Por loja")
    Column { s.byStore.forEachIndexed { i, p -> StoreRow(p, s.basis, cheapest = i == 0) } }
  }
  s.averageCents?.let { avg ->
    Sub("Média dos últimos 3 meses: ${price(avg, s.basis)} · ${Format.count(s.purchases, "compra", "compras")} ao todo")
  } ?: Sub(Format.count(s.purchases, "compra", "compras") + ", nenhuma nos últimos 3 meses")
}

@Composable
private fun StoreRow(p: StorePrice, basis: String, cheapest: Boolean) =
  AmountRow(title = p.storeName ?: "Loja sem nome", amount = price(p.cents, basis), subtitle = Format.date(p.at),
    trailingMark = if (cheapest) "mais barato" else null)

private fun price(cents: Long, basis: String) = Format.brl(cents) + when (basis) { "kg" -> "/kg"; "l" -> "/l"; else -> "" }

