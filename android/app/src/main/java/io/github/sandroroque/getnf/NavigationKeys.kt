package io.github.sandroroque.getnf

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

@Serializable data object Home : NavKey

@Serializable data object Scan : NavKey

/** The SEFAZ page for an already-scanned receipt. */
@Serializable data class Page(val chave: String) : NavKey

/** What was read from a receipt's saved page (D50). */
@Serializable data class ReceiptDetail(val chave: String) : NavKey

@Serializable data object Settings : NavKey
