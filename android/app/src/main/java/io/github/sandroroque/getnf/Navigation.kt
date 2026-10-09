package io.github.sandroroque.getnf

import androidx.compose.runtime.Composable
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import io.github.sandroroque.getnf.ui.home.HomeScreen
import io.github.sandroroque.getnf.ui.page.PageScreen
import io.github.sandroroque.getnf.ui.scan.ScanScreen
import io.github.sandroroque.getnf.ui.settings.SettingsScreen

/**
 * Navigation 3: the back stack is a plain list of keys; the last one is on screen.
 * Flow: Home → Scan → Page (Scan is replaced, so Back from Page returns Home). Page → Page for "Next" (also replaced).
 */
@Composable
fun MainNavigation() {
  val backStack = rememberNavBackStack(Home)

  NavDisplay(
    backStack = backStack,
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
            onDone = { chave ->
              backStack.removeLastOrNull()
              backStack.add(Page(chave))
            },
            onAlreadySaved = { backStack.removeLastOrNull() },
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
