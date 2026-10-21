package jp.liki.pockethid

/** CCCD values belong to a bonded client, independently of a GATT server instance. */
internal class BondedSubscriptions(
    private val load: (String) -> Set<String>,
    private val save: (String, Set<String>) -> Unit,
    private val remove: (String) -> Unit,
) {
    fun restore(address: String, bonded: Boolean): Set<String> {
        if (!bonded) { remove(address); return emptySet() }
        return load(address).toSet()
    }

    fun update(address: String, bonded: Boolean, values: Set<String>) {
        if (bonded) save(address, values.toSet()) else remove(address)
    }

    fun forget(address: String) = remove(address)
}
