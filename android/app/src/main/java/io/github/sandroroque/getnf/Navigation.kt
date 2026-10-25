package io.github.sandroroque.getnf

import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import io.github.sandroroque.getnf.ui.catalog.CategoriesScreen
import io.github.sandroroque.getnf.ui.catalog.ProductScreen
import io.github.sandroroque.getnf.ui.catalog.ProductsScreen
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import io.github.sandroroque.getnf.ui.home.HomeScreen
import io.github.sandroroque.getnf.ui.spending.SpendingScreen
import io.github.sandroroque.getnf.ui.page.PageScreen
import io.github.sandroroque.getnf.ui.receipt.ReceiptScreen
import io.github.sandroroque.getnf.ui.scan.ScanScreen
import io.github.sandroroque.getnf.ui.settings.SettingsScreen

/**
 * Navigation 3: the back stack is a plain list of keys; the last one is on screen. Three tabs at the bottom (stage 5):
 * Gastos (first), Notas and Produtos; switching tabs starts that tab's stack afresh.
 * Flow: Home → Scan → Home after automatic capture; portal lookup is explicitly started from Home.
 * Home → ReceiptDetail shows what was read from a saved page; its lines open their product.
 * Home → Products (the review list, stage 4c) → ProductDetail; Home → Categories edits the tree.
 * Page → Page for "Next" replaces the page so Back still returns Home.
 */
@Composable
fun MainNavigation() {
  val backStack = rememberNavBackStack(Spending)
  val tabs = listOf(Spending to ("📊" to "Gastos"), Home to ("🧾" to "Notas"), Products to ("🏷️" to "Produtos"))
  val root = backStack.firstOrNull()

  // No insets of its own: each screen's Scaffold handles the status bar; this one only makes room for the tabs.
  Scaffold(
    contentWindowInsets = WindowInsets(0, 0, 0, 0),
    bottomBar = {
      // Only on the three top screens; deeper screens keep the whole height and go back with Back.
      if (backStack.size == 1) NavigationBar {
        for ((key, look) in tabs) NavigationBarItem(
          selected = root == key,
          onClick = { if (root != key) { backStack.clear(); backStack.add(key) } },
          icon = { Text(look.first) },
          label = { Text(look.second) },
        )
      }
    },
  ) { padding ->
    NavDisplay(
      modifier = Modifier.padding(bottom = padding.calculateBottomPadding()).consumeWindowInsets(padding),
      backStack = backStack,
      entryDecorators = listOf(
        rememberSaveableStateHolderNavEntryDecorator(),
        rememberViewModelStoreNavEntryDecorator(),
      ),
      onBack = { backStack.removeLastOrNull() },
      entryProvider =
        entryProvider {
          entry<Spending> {
            SpendingScreen(
              onScan = { backStack.add(Scan) },
              onOpenProduct = { backStack.add(ProductDetail(it)) },
              onCategories = { backStack.add(Categories) },
              onSettings = { backStack.add(Settings) },
            )
          }
          entry<Home> {
            HomeScreen(
              onScan = { backStack.add(Scan) },
              onOpenPage = { chave -> backStack.add(Page(chave)) },
              onOpenReceipt = { chave -> backStack.add(ReceiptDetail(chave)) },
              onSettings = { backStack.add(Settings) },
            )
          }
          entry<Scan> {
            ScanScreen(
              onCaptured = { backStack.removeLastOrNull() },
              onCancel = { backStack.removeLastOrNull() },
            )
          }
          entry<Page> { key ->
            PageScreen(
              chave = key.chave,
              onDone = { backStack.removeLastOrNull() },
              // Straight on to the next waiting receipt, replacing this page so Back still returns where it came from.
              onNext = { next ->
                backStack.removeLastOrNull()
                backStack.add(Page(next))
              },
            )
          }
          entry<ReceiptDetail> { key -> ReceiptScreen(chave = key.chave, onOpenProduct = { backStack.add(ProductDetail(it)) }) }
          entry<Products> {
            ProductsScreen(onOpenProduct = { backStack.add(ProductDetail(it)) }, onCategories = { backStack.add(Categories) })
          }
          entry<ProductDetail> { key ->
            // "Separar" opens the new product in place of this one, so Back returns to where the user came from.
            ProductScreen(key.productId, onOpenProduct = { backStack.removeLastOrNull(); backStack.add(ProductDetail(it)) })
          }
          entry<Categories> { CategoriesScreen() }
          entry<Settings> { SettingsScreen(onDone = { backStack.removeLastOrNull() }) }
        },
    )
  }
}
