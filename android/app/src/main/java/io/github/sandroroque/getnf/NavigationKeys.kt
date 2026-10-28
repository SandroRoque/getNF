package io.github.sandroroque.getnf

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

/** Início (D81): the month at a glance. The first screen. */
@Serializable data object Inicio : NavKey

/** Gastos: where the money went (stage 5, D81). */
@Serializable data object Spending : NavKey

/** Para você: every decision, one queue (D81). */
@Serializable data object ParaVoce : NavKey

/** Catálogo: products, categories, stores and receipts; [segment] picks which (D81). */
@Serializable data class Catalog(val segment: Int = 0) : NavKey

@Serializable data class StoreDetail(val cnpj: String) : NavKey

@Serializable data object Scan : NavKey

/** The SEFAZ page for an already-scanned receipt. */
@Serializable data class Page(val chave: String) : NavKey

/** What was read from a receipt's saved page (D50). */
@Serializable data class ReceiptDetail(val chave: String) : NavKey

@Serializable data object Settings : NavKey

/** One caderneta in Ajustes (D104). */
@Serializable data class LedgerDetail(val ledgerId: String) : NavKey

@Serializable data class ProductDetail(val productId: String) : NavKey

/** The personal price index (stage 7), for everything or a category subtree. */
@Serializable data class IpciKey(val categoryId: String?) : NavKey

