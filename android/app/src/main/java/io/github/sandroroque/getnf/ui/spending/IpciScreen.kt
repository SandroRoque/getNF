package io.github.sandroroque.getnf.ui.spending

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.sandroroque.getnf.GetNFApp
import io.github.sandroroque.getnf.data.Ipci
import io.github.sandroroque.getnf.data.Period
import io.github.sandroroque.getnf.data.Spending
import io.github.sandroroque.getnf.ui.Format
import io.github.sandroroque.getnf.ui.catalog.CategoryPickerDialog
import io.github.sandroroque.getnf.ui.catalog.pathOf
import java.time.YearMonth

/**
 * The IPCI (stage 7, D79): the user's own price index, for everything or one category. Shows the chained level, each
 * month's change with its coverage, what moved it, and D64's split of spending into prices and the rest. A month the
 * data can't support says so instead of showing a number.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IpciScreen(initialCategory: String?, onOpenProduct: (String) -> Unit) {
  val app = LocalContext.current.applicationContext as GetNFApp
  val lines by remember { app.database.spending().lines() }.collectAsStateWithLifecycle(null)
  val categories by remember { app.database.edit().categories() }.collectAsStateWithLifecycle(emptyList())
  val products by remember { app.database.edit().products() }.collectAsStateWithLifecycle(emptyList())
  var scope by rememberSaveable { mutableStateOf(initialCategory) }
  var picking by remember { mutableStateOf(false) }
  var open by rememberSaveable { mutableStateOf<String?>(null) }
  val parents = remember(categories) { categories.associate { it.id to it.parentId } }
  val months = remember(lines, parents, scope) {
    lines?.let { all ->
      val s = Spending(all, parents)
      Ipci.compute(if (scope == null) all else all.filter { scope in s.ancestry(it.categoryId) })
    }
  }
  val titles = products.associate { it.productId to it.title }

  Scaffold(topBar = { TopAppBar(title = { Text("Inflação pessoal") }) }) { padding ->
    val ms = months ?: return@Scaffold
    LazyColumn(
      Modifier.fillMaxSize().padding(padding),
      contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      item {
        Row {
          Text(scope?.let { pathOf(categories, it) } ?: "Todos os gastos", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
          TextButton(onClick = { picking = true }) { Text("Trocar") }
        }
        Headline(ms)
      }
      if (ms.size >= 2) item {
        TimeSeries(mapOf("" to ms.map { YearMonth.parse(it.period.key).atDay(15).toEpochDay() to it.index }), format = { "%.1f".format(it) })
        Text("Nível do índice, 100 no primeiro mês com compras.", style = MaterialTheme.typography.bodySmall)
      }
      item { SplitCard(ms) }
      item {
        HorizontalDivider()
        Text("Mês a mês", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
      }
      items(ms.reversed(), key = { it.period.key }) { m ->
        Column(Modifier.fillMaxWidth().clickable { open = if (open == m.period.key) null else m.period.key }.padding(vertical = 6.dp)) {
          Row {
            Text(label(m.period, long = true), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text(m.reading?.let(::signed) ?: if (m == ms.first()) "início" else "dados insuficientes",
              style = MaterialTheme.typography.bodyLarge,
              color = if (m.reading == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
          }
          if (m != ms.first()) Text(
            "${m.links.size} produto(s) comparado(s) · ${percent(m.coverage)} do gasto do mês" +
              if (m.estimated > 0) " · ${percent(m.estimated)} com desconto rateado (estimado)" else "",
            style = MaterialTheme.typography.bodySmall,
          )
          if (open == m.period.key) for ((id, pp) in m.contributions().take(8)) {
            val link = m.links.single { it.productId == id }
            Row(Modifier.fillMaxWidth().clickable { onOpenProduct(id) }.padding(start = 12.dp, top = 4.dp)) {
              Text(titles[id] ?: "?", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
              Text("${signed((link.ratio - 1) * 100)} → ${"%+.2f".format(pp)} p.p.", style = MaterialTheme.typography.bodySmall)
            }
          }
        }
        HorizontalDivider()
      }
      item {
        Text(
          "Como é calculado: a cada mês, cada produto comprado é comparado com o próprio preço da compra anterior " +
            "(o preço pago, por kg ou litro quando dá), e as variações são ponderadas pelo que você gastou em cada um e " +
            "encadeadas mês a mês. Um mês com menos de ${percent(Ipci.MIN_COVERAGE)} do gasto em produtos já comprados antes, " +
            "ou menos de ${Ipci.MIN_PRODUCTS} produtos, não tem leitura.",
          style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp),
        )
      }
    }
  }
  if (picking) CategoryPickerDialog("Índice de…", categories, current = scope, noneLabel = "Todos os gastos",
    onPick = { scope = it; picking = false; open = null }, onDismiss = { picking = false })
}

/** The latest month with a reading, and the change over the last 12 months when the chain covers them. */
@Composable
private fun Headline(ms: List<Ipci.Month>) {
  val latest = ms.lastOrNull { it.reading != null }
  Card(Modifier.fillMaxWidth()) {
    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
      if (latest == null) {
        Text("Dados insuficientes", style = MaterialTheme.typography.titleLarge)
        Text("Ainda não há meses com produtos suficientes comprados de novo para medir preços. Cada nota nova ajuda.",
          style = MaterialTheme.typography.bodySmall)
      } else {
        Text("${signed(latest.reading!!)} em ${label(latest.period, long = true)}", style = MaterialTheme.typography.titleLarge)
        val yearAgo = ms.firstOrNull { it.period == Period(YearMonth.parse(latest.period.key).minusMonths(12).toString()) }
        if (yearAgo != null && ms.filter { it.period > yearAgo.period && it.period <= latest.period }.all { it.sufficient })
          Text("${signed((latest.index / yearAgo.index - 1) * 100)} em 12 meses", style = MaterialTheme.typography.bodyMedium)
        if (ms.last() != latest) Text("Meses mais recentes: dados insuficientes.", style = MaterialTheme.typography.bodySmall)
      }
    }
  }
}

/** D64: how much of a spending change was prices, and how much was buying more, less or different things. */
@Composable
private fun SplitCard(ms: List<Ipci.Month>) {
  val readable = ms.filter { it.reading != null }
  val to = readable.lastOrNull() ?: return
  val from = ms.lastOrNull { it.period < to.period } ?: return
  val split = Ipci.split(ms, from.period, to.period) ?: return
  Card(Modifier.fillMaxWidth()) {
    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
      Text("${label(from.period, long = true)} → ${label(to.period, long = true)}", style = MaterialTheme.typography.titleSmall)
      Text("Gasto ${signed((split.spending - 1) * 100)} (${Format.brl(from.spentCents)} → ${Format.brl(to.spentCents)})")
      Text("= preços ${signed((split.prices - 1) * 100)} × consumo e escolhas ${signed((split.lifestyle - 1) * 100)}")
      Text("\"Consumo e escolhas\" é quanto e o que você comprou: trocar por marcas mais caras aparece aqui, não nos preços.",
        style = MaterialTheme.typography.bodySmall)
    }
  }
}

private fun signed(pct: Double) = "%+.1f%%".format(pct).replace('.', ',')
private fun percent(x: Double) = "${Math.round(x * 100)}%"
