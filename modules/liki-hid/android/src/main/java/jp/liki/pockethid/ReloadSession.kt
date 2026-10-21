package jp.liki.pockethid

/** Keeps the native connection across JS runtimes without retaining their callbacks. Main thread only. */
internal class ReloadSession<T> {
    private var value: T? = null
    private var owner: Any? = null
    fun owns(client: Any): Boolean = owner === client

    fun acquire(client: Any, create: () -> T): T {
        owner = client
        return value ?: create().also { value = it }
    }

    fun release(client: Any, discard: Boolean = false, cleanup: (T) -> Unit) {
        if (owner !== client) return
        owner = null
        val current = value ?: return
        if (discard) value = null
        cleanup(current)
    }
}
