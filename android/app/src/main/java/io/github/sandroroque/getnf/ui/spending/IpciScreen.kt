package io.github.sandroroque.getnf.ui.spending

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.sandroroque.getnf.GetNFApp
import io.github.sandroroque.getnf.data.Ipci
import io.github.sandroroque.getnf.data.Period
import io.github.sandroroque.getnf.data.Spending
import io.github.sandroroque.getnf.theme.Calmo
import io.github.sandroroque.getnf.ui.Format
import io.github.sandroroque.getnf.ui.calmo.AmountRow
import io.github.sandroroque.getnf.ui.calmo.BackHeader
import io.github.sandroroque.getnf.ui.calmo.BigNumber
import io.github.sandroroque.getnf.ui.calmo.EmptyState
import io.github.sandroroque.getnf.ui.calmo.Hairline
import io.github.sandroroque.getnf.ui.calmo.SectionLabel
import io.github.sandroroque.getnf.ui.calmo.Sub
import io.github.sandroroque.getnf.ui.catalog.CategoryPickerDialog
import io.github.sandroroque.getnf.ui.catalog.pathOf
import java.time.YearMonth

/**
 * Inflação pessoal (stage 7, D79; D81's look): one number when the data holds it, plain words when it doesn't. The
 * index over time, each month with what it rests on, what moved it, and the split of spending into prices and the rest.
 */
@Composable
fun IpciScreen(initialCategory: String?, onOpenProduct: (String) -> Unit, onBack: () -> Unit = {}) {
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

  LazyColumn(
    Modifier.fillMaxSize().statusBarsPadding(),
    contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp),
    verticalArrangement = Arrangement.spacedBy(14.dp),
  ) {
    item {
      BackHeader("Voltar", onBack) {
        Text(scope?.let { categories.firstOrNull { c -> c.id == it }?.label } ?: "Todos os gastos", style = MaterialTheme.typography.bodyMedium,
          color = Calmo.acc, modifier = Modifier.clickable { picking = true }.padding(8.dp))
      }
    }
    val ms = months ?: return@LazyColumn
    if (ms.size < 2) {
      item { EmptyState(io.github.sandroroque.getnf.ui.home.IPCI_HINT) }
      return@LazyColumn
    }
    val latest = ms.lastOrNull { it.reading != null }
    item {
      Column {
        if (latest == null) {
          BigNumber("—")
          Sub("Ainda poucos produtos comprados de novo para medir preços.")
        } else {
          BigNumber(signed(latest.reading!!), color = if (latest.reading!! > 0) Calmo.up else Calmo.down)
          Sub("em ${label(latest.period, long = true)}, comparando ${Format.count(latest.links.size, "produto", "produtos")} que você comprou de novo")
          val yearAgo = ms.firstOrNull { it.period == Period(YearMonth.parse(latest.period.key).minusMonths(12).toString()) }
          if (yearAgo != null && ms.filter { it.period > yearAgo.period && it.period <= latest.period }.all { it.sufficient })
            Sub("${signed((latest.index / yearAgo.index - 1) * 100)} em 12 meses")
        }
        scope?.let { Sub("Em ${pathOf(categories, it)}") }
      }
    }
    item {
      TimeSeries(mapOf("" to ms.map { YearMonth.parse(it.period.key).atDay(15).toEpochDay() to it.index }), format = { "%.1f".format(it).replace('.', ',') })
    }
    item { Split(ms) }
    item { SectionLabel("Mês a mês") }
    items(ms.reversed(), key = { it.period.key }) { m ->
      val first = m == ms.first()
      Column {
        Row(Modifier.fillMaxWidth().clickable(enabled = !first) { open = if (open == m.period.key) null else m.period.key }.padding(vertical = 11.dp),
          verticalAlignment = Alignment.CenterVertically) {
          Column(Modifier.weight(1f)) {
            Text(label(m.period, long = true).replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.bodyMedium, color = Calmo.fg)
            if (!first) Sub("${Format.count(m.links.size, "produto", "produtos")}, ${percent(m.coverage)} do gasto" +
              if (m.estimated > 0) " · ${percent(m.estimated)} com desconto rateado" else "")
          }
          Text(m.reading?.let(::signed) ?: if (first) "início" else "poucos dados", style = MaterialTheme.typography.bodyMedium,
            color = when { m.reading == null -> Calmo.mute; m.reading!! > 0 -> Calmo.up; else -> Calmo.down })
        }
        if (open == m.period.key) for ((id, pp) in m.contributions().take(8)) {
          val link = m.links.single { it.productId == id }
          AmountRow(titles[id] ?: "?", "${"%+.2f".format(pp).replace('.', ',')} p.p.",
            subtitle = "preço ${signed((link.ratio - 1) * 100)}", onClick = { onOpenProduct(id) }, modifier = Modifier.padding(start = 12.dp))
        }
        Hairline()
      }
    }
    item {
      Sub("Como é calculado: a cada mês, cada produto comprado é comparado com o próprio preço pago da compra anterior " +
        "(por kg ou litro quando dá); as variações pesam pelo que você gastou em cada um e se encadeiam mês a mês. Um mês " +
        "com menos de ${percent(Ipci.MIN_COVERAGE)} do gasto em produtos já comprados antes, ou menos de ${Ipci.MIN_PRODUCTS} produtos, não tem leitura.")
    }
  }
  if (picking) CategoryPickerDialog("Índice de…", categories, current = scope, noneLabel = "Todos os gastos",
    onPick = { scope = it; picking = false; open = null }, onDismiss = { picking = false })
}

/** D64: spending = prices × consumption and choices, between the last two months that hold up. */
@Composable
private fun Split(ms: List<Ipci.Month>) {
  val to = ms.lastOrNull { it.reading != null } ?: return
  val from = ms.lastOrNull { it.period < to.period } ?: return
  val s = Ipci.split(ms, from.period, to.period) ?: return
  Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
    SectionLabel("${label(from.period)} → ${label(to.period)}".replace("\n", " "))
    Text("Gasto ${signed((s.spending - 1) * 100)} = preços ${signed((s.prices - 1) * 100)} × consumo e escolhas ${signed((s.lifestyle - 1) * 100)}",
      style = MaterialTheme.typography.bodyMedium, color = Calmo.fg)
    Sub("Trocar por uma marca mais cara aumenta o gasto, não a inflação.")
  }
}

private fun signed(pct: Double) = "%+.1f%%".format(pct).replace('.', ',')
private fun percent(x: Double) = "${Math.round(x * 100)}%"
