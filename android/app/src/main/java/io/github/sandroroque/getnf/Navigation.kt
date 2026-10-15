package io.github.sandroroque.getnf

import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import io.github.sandroroque.getnf.ui.home.HomeScreen
import io.github.sandroroque.getnf.ui.page.PageScreen
import io.github.sandroroque.getnf.ui.scan.ScanScreen
import io.github.sandroroque.getnf.ui.settings.SettingsScreen

/**
 * Navigation 3: the back stack is a plain list of keys; the last one is on screen.
 * Flow: Home → Scan → Home after automatic capture; portal lookup is explicitly started from Home.
 * Page → Page for "Next" replaces the page so Back still returns Home.
 */
@Composable
fun MainNavigation() {
  val backStack = rememberNavBackStack(Home)

  NavDisplay(
    backStack = backStack,
    entryDecorators = listOf(
      rememberSaveableStateHolderNavEntryDecorator(),
      rememberViewModelStoreNavEntryDecorator(),
    ),
    onBack = { backStack.removeLastOrNull() },
    entryProvider =
      entryProvider {
        entry<Home> {
          HomeScreen(
            onScan = { backStack.add(Scan) },
            onOpenPage = { chave -> backStack.add(Page(chave)) },
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
            // Straight on to the next waiting receipt, replacing this page so Back still returns Home.
            onNext = { next ->
              backStack.removeLastOrNull()
              backStack.add(Page(next))
            },
          )
        }
        entry<Settings> { SettingsScreen(onDone = { backStack.removeLastOrNull() }) }
      },
  )
}
