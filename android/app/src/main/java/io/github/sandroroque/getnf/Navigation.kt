package io.github.sandroroque.getnf

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import io.github.sandroroque.getnf.theme.Calmo
import io.github.sandroroque.getnf.ui.calmo.Hairline
import io.github.sandroroque.getnf.ui.calmo.Icons
import io.github.sandroroque.getnf.ui.catalog.CatalogScreen
import io.github.sandroroque.getnf.ui.catalog.ProductScreen
import io.github.sandroroque.getnf.ui.catalog.StoreScreen
import io.github.sandroroque.getnf.ui.home.InicioScreen
import io.github.sandroroque.getnf.ui.page.PageScreen
import io.github.sandroroque.getnf.ui.receipt.ReceiptScreen
import io.github.sandroroque.getnf.ui.review.ParaVoceScreen
import io.github.sandroroque.getnf.ui.scan.ScanScreen
import io.github.sandroroque.getnf.ui.settings.SettingsScreen
import io.github.sandroroque.getnf.ui.spending.IpciScreen
import io.github.sandroroque.getnf.ui.spending.SpendingScreen

/**
 * Navigation 3: the back stack is a plain list of keys; the last one is on screen. Four tabs (D81): Início, Gastos,
 * Para você and Catálogo, with Scan as the bar's middle button. A tab starts its own stack afresh; the bar shows only
 * on the tab screens themselves. Page → Page for "Next" replaces the page so Back still returns where it came from.
 */
@Composable
fun MainNavigation() {
  val backStack = rememberNavBackStack(Inicio)
  val root = backStack.firstOrNull()
  fun tab(key: NavKey) { if (backStack.size != 1 || root != key) { backStack.clear(); backStack.add(key) } }
  fun open(key: NavKey) { backStack.add(key) }
  fun back() { backStack.removeLastOrNull() }

  Scaffold(
    containerColor = Calmo.bg,
    contentWindowInsets = WindowInsets(0, 0, 0, 0),
    bottomBar = {
      if (backStack.size == 1) TabBar(
        selected = root?.let { if (it is Catalog) Catalog() else it },
        onTab = ::tab, onScan = { open(Scan) },
      )
    },
  ) { padding ->
    NavDisplay(
      modifier = Modifier.padding(bottom = padding.calculateBottomPadding()).consumeWindowInsets(padding),
      backStack = backStack,
      entryDecorators = listOf(rememberSaveableStateHolderNavEntryDecorator(), rememberViewModelStoreNavEntryDecorator()),
      onBack = { back() },
      entryProvider = entryProvider {
        entry<Inicio> {
          InicioScreen(
            onSpending = { tab(Spending) }, onDecisions = { tab(ParaVoce) }, onIpci = { open(IpciKey(null)) },
            onReceipt = { open(ReceiptDetail(it)) }, onSettings = { open(Settings) },
          )
        }
        entry<Spending> {
          SpendingScreen(
            onScan = { open(Scan) }, onOpenProduct = { open(ProductDetail(it)) }, onCategories = { tab(Catalog(1)) },
            onSettings = { open(Settings) }, onIpci = { open(IpciKey(it)) }, onOpenStore = { open(StoreDetail(it)) },
          )
        }
        entry<ParaVoce> {
          ParaVoceScreen(
            onOpenPage = { open(Page(it)) }, onOpenProduct = { open(ProductDetail(it)) },
            onOpenReceipt = { open(ReceiptDetail(it)) }, onCategories = { tab(Catalog(1)) },
          )
        }
        entry<Catalog> { key ->
          CatalogScreen(
            initialSegment = key.segment, onOpenProduct = { open(ProductDetail(it)) }, onOpenStore = { open(StoreDetail(it)) },
            onOpenReceipt = { open(ReceiptDetail(it)) }, onOpenPage = { open(Page(it)) },
          )
        }
        entry<StoreDetail> { key ->
          StoreScreen(key.cnpj, onBack = ::back, onOpenReceipt = { open(ReceiptDetail(it)) }, onOpenProduct = { open(ProductDetail(it)) })
        }
        entry<IpciKey> { key -> IpciScreen(key.categoryId, onOpenProduct = { open(ProductDetail(it)) }, onBack = ::back) }
        entry<Scan> { ScanScreen(onCaptured = ::back, onCancel = ::back) }
        entry<Page> { key ->
          PageScreen(chave = key.chave, onDone = ::back, onNext = { next -> back(); open(Page(next)) })
        }
        entry<ReceiptDetail> { key -> ReceiptScreen(chave = key.chave, onOpenProduct = { open(ProductDetail(it)) }, onBack = ::back) }
        entry<ProductDetail> { key ->
          // "Separar" opens the new product in place of this one, so Back returns to where the user came from.
          ProductScreen(key.productId, onOpenProduct = { back(); open(ProductDetail(it)) }, onBack = ::back,
            onOpenReceipt = { open(ReceiptDetail(it)) })
        }
        entry<Settings> { SettingsScreen(onDone = ::back) }
      },
    )
  }
}

/** The bar: four tabs and the scan button in the middle (D81). */
@Composable
private fun TabBar(selected: NavKey?, onTab: (NavKey) -> Unit, onScan: () -> Unit) {
  Column(Modifier.background(Calmo.bg).navigationBarsPadding()) {
    Hairline()
    Row(Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 8.dp), verticalAlignment = Alignment.Bottom,
      horizontalArrangement = Arrangement.SpaceEvenly) {
      TabItem(Icons.Home, "Início", selected == Inicio) { onTab(Inicio) }
      TabItem(Icons.Chart, "Gastos", selected == Spending) { onTab(Spending) }
      Box(Modifier.size(52.dp).clip(CircleShape).border(1.5.dp, Calmo.fg, CircleShape).clickable(onClick = onScan),
        contentAlignment = Alignment.Center) {
        Icon(Icons.Scan, "Escanear nota", Modifier.size(25.dp), tint = Calmo.fg)
      }
      TabItem(Icons.Inbox, "Para você", selected == ParaVoce) { onTab(ParaVoce) }
      TabItem(Icons.Grid, "Catálogo", selected is Catalog) { onTab(Catalog()) }
    }
  }
}

@Composable
private fun TabItem(icon: ImageVector, label: String, selected: Boolean, onClick: () -> Unit) {
  val color = if (selected) Calmo.fg else Calmo.mute
  Column(Modifier.clip(androidx.compose.foundation.shape.RoundedCornerShape(10.dp)).clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 4.dp),
    horizontalAlignment = Alignment.CenterHorizontally) {
    Icon(icon, null, Modifier.size(22.dp), tint = color)
    Text(label, style = MaterialTheme.typography.labelMedium, color = color)
  }
}
