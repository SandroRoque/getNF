package io.github.sandroroque.getnf.data

import java.util.UUID

/**
 * Ids for the rows the parser makes, derived from the item's code (D104): two phones in one caderneta that read the
 * same item offline make the same store item and product, so their uploads merge instead of clashing on the server's
 * unique code key or leaving a duplicate product. Ids are unique per caderneta on the server, not globally.
 * Hand-made products and categories keep random ids.
 */
object CodeIds {
  fun storeItem(kind: String, code: String, storeCnpj: String): String = of("store_item", kind, code, storeCnpj)

  /** The product the parser makes for a store item it sees for the first time. */
  fun product(kind: String, code: String, storeCnpj: String): String = of("product", kind, code, storeCnpj)

  private fun of(vararg parts: String): String =
    UUID.nameUUIDFromBytes(("getnf:" + parts.joinToString("|")).toByteArray(Charsets.UTF_8)).toString()
}
