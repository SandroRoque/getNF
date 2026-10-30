package io.github.sandroroque.getnf

import androidx.activity.compose.BackHandler
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
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
import io.github.sandroroque.getnf.data.Demo
import io.github.sandroroque.getnf.theme.Calmo
import io.github.sandroroque.getnf.ui.calmo.ButtonKind
import io.github.sandroroque.getnf.ui.calmo.Sub
import io.github.sandroroque.getnf.ui.catalog.CalmoSheet
import io.github.sandroroque.getnf.ui.onboarding.TextLink
import io.github.sandroroque.getnf.ui.onboarding.WelcomeScreen
import io.github.sandroroque.getnf.ui.onboarding.Wide
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
  val context = LocalContext.current
  val app = context.applicationContext as GetNFApp
  val demo = remember { app.accountSettings.demo }
  val scope = rememberCoroutineScope()
  var leavingDemo by remember { mutableStateOf(false) }
  var scanInDemo by remember { mutableStateOf(false) }
  fun tab(key: NavKey) { if (backStack.size != 1 || root != key) { backStack.clear(); backStack.add(key) } }
  fun open(key: NavKey) { if (key == Scan && demo) scanInDemo = true else backStack.add(key) }
  fun back() { backStack.removeLastOrNull() }
  fun enterDemo() { scope.launch { Demo.enter(context) } }

  // The first start (D115): the welcome, unless there are receipts already (an update, or a restore).
  val receipts by remember { app.database.lists().receipts() }.collectAsStateWithLifecycle(null)
  var welcomeSeen by remember { mutableStateOf(demo || app.onboarding.welcomeSeen) }
  LaunchedEffect(receipts) { if (!welcomeSeen && receipts?.isNotEmpty() == true) { app.onboarding.welcomeSeen = true; welcomeSeen = true } }
  if (!welcomeSeen) {
    if (receipts?.isEmpty() != true) return
    fun done(then: () -> Unit) { app.onboarding.welcomeSeen = true; welcomeSeen = true; then() }
    WelcomeScreen(
      onScan = { done { open(Scan) } }, onDemo = { done(::enterDemo) }, onExplore = { done {} }, onSignIn = { done { open(Settings) } },
    )
    return
  }

  // NavDisplay only handles Back with more than one screen open; at a tab's root, Back goes to Início first.
  BackHandler(enabled = backStack.size == 1 && root != Inicio) { tab(Inicio) }

  Scaffold(
    containerColor = Calmo.bg,
    contentWindowInsets = WindowInsets(0, 0, 0, 0),
    topBar = { if (demo) DemoBar { leavingDemo = true } },
    bottomBar = {
      if (backStack.size == 1) TabBar(
        selected = root?.let { if (it is Catalog) Catalog() else it },
        onTab = ::tab, onScan = { open(Scan) },
      )
    },
  ) { padding ->
    NavDisplay(
      modifier = Modifier.padding(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding()).consumeWindowInsets(padding),
      backStack = backStack,
      entryDecorators = listOf(rememberSaveableStateHolderNavEntryDecorator(), rememberViewModelStoreNavEntryDecorator()),
      onBack = { back() },
      entryProvider = entryProvider {
        entry<Inicio> {
          InicioScreen(
            onSpending = { tab(Spending) }, onDecisions = { tab(ParaVoce) }, onIpci = { open(IpciKey(null)) },
            onReceipt = { open(ReceiptDetail(it)) }, onSettings = { open(Settings) },
            onScan = { open(Scan) }, onDemo = ::enterDemo,
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
        entry<Scan> { ScanScreen(onCaptured = ::back, onCancel = ::back, onOpenPage = { back(); open(Page(it)) }) }
        entry<Page> { key ->
          // Saved: the receipt opens in its place, Back returning to where the fetch began.
          PageScreen(chave = key.chave, onDone = ::back, onSaved = { back(); open(ReceiptDetail(it)) })
        }
        entry<ReceiptDetail> { key ->
          ReceiptScreen(chave = key.chave, onOpenProduct = { open(ProductDetail(it)) }, onBack = ::back, onOpenPage = { back(); open(Page(it)) })
        }
        entry<ProductDetail> { key ->
          // "Separar" opens the new product in place of this one, so Back returns to where the user came from.
          ProductScreen(key.productId, onOpenProduct = { back(); open(ProductDetail(it)) }, onBack = ::back,
            onOpenReceipt = { open(ReceiptDetail(it)) })
        }
        entry<Settings> { SettingsScreen(onDone = ::back, onOpenLedger = { open(LedgerDetail(it)) }, onLeaveDemo = { leavingDemo = true }) }
        entry<LedgerDetail> { key -> io.github.sandroroque.getnf.ui.settings.CadernetaScreen(key.ledgerId, onBack = ::back) }
      },
    )
  }
  DemoDialogs(leavingDemo, scanInDemo) { leavingDemo = false; scanInDemo = false }
}

@Composable
private fun DemoDialogs(leaving: Boolean, scanning: Boolean, onDismiss: () -> Unit) {
  val context = LocalContext.current
  if (leaving) CalmoSheet("Sair do exemplo?", onDismiss = onDismiss) {
    Sub("O app volta para as suas notas. O exemplo não guarda nada: da próxima vez ele começa do zero.")
    Wide("Sair do exemplo", ButtonKind.Solid) { Demo.leave(context) }
    Wide("Continuar vendo", ButtonKind.Ghost, onDismiss)
  }
  if (scanning) CalmoSheet("Escanear uma nota", onDismiss = onDismiss) {
    Sub("No exemplo não dá para escanear: as notas aqui são inventadas. Saia do exemplo para escanear as suas.")
    Wide("Sair do exemplo", ButtonKind.Solid) { Demo.leave(context) }
    Wide("Continuar vendo", ButtonKind.Ghost, onDismiss)
  }
}

/** Always on top in the demo (D115): what this is, and the way out. */
@Composable
private fun DemoBar(onLeave: () -> Unit) {
  Column(Modifier.background(Calmo.sheet).statusBarsPadding()) {
    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 2.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
      Text("Exemplo · dados inventados", style = MaterialTheme.typography.labelLarge, color = Calmo.fg, modifier = Modifier.weight(1f))
      TextLink("Sair do exemplo", onLeave)
    }
    Hairline()
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
      // The receipt in a circle, named like the tabs around it (D118).
      Column(Modifier.clip(androidx.compose.foundation.shape.RoundedCornerShape(10.dp)).clickable(onClick = onScan).padding(horizontal = 6.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(46.dp).clip(CircleShape).border(1.5.dp, Calmo.fg, CircleShape), contentAlignment = Alignment.Center) {
          Icon(Icons.Scan, null, Modifier.size(24.dp), tint = Calmo.fg)
        }
        Text("Escanear", style = MaterialTheme.typography.labelMedium, color = Calmo.fg)
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
