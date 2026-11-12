package io.github.sandroroque.getnf.data

/**
 * [start] and its ancestors, itself first, following [parent] until a root, a parent that isn't there, or a node seen
 * already (T-32, D187). Categories form a tree, but two phones moving categories under each other, or the server's
 * taxonomy sync, can leave a parent cycle in the data: every walk up the tree goes through here, so none hangs.
 */
fun <T : Any> chainUp(start: T?, parent: (T) -> T?): List<T> {
  val seen = HashSet<T>()
  val out = ArrayList<T>()
  var node = start
  while (node != null && seen.add(node)) { out += node; node = parent(node) }
  return out
}
