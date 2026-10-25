package io.github.sandroroque.getnf

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

/** Spending reports (stage 5): the first screen. */
@Serializable data object Spending : NavKey

/** The receipts list ("Notas"). */
@Serializable data object Home : NavKey

@Serializable data object Scan : NavKey

/** The SEFAZ page for an already-scanned receipt. */
@Serializable data class Page(val chave: String) : NavKey

/** What was read from a receipt's saved page (D50). */
@Serializable data class ReceiptDetail(val chave: String) : NavKey

@Serializable data object Settings : NavKey

/** Products: what needs a decision first, then everything (stage 4c). */
@Serializable data object Products : NavKey

@Serializable data class ProductDetail(val productId: String) : NavKey

/** The personal price index (stage 7), for everything or a category subtree. */
@Serializable data class IpciKey(val categoryId: String?) : NavKey

/** The category tree, editable (D75). */
@Serializable data object Categories : NavKey
