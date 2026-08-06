package ru.compclub.tvshell.data

data class SessionState(
    val userId: Int = 0,
    val userName: String = "",
    val phone: String = "",
    val balance: Double = 0.0,
    val timeRemaining: String = "00:00:00",
    val remainingSeconds: Int = 0,
    val bookingId: Int = 0,
    val active: Boolean = false,
    val bannerMessage: String = "",
    val warnBanner: String = "",
)

class SessionStore {
    @Volatile
    var state: SessionState = SessionState()
        private set

    private val listeners = mutableListOf<(SessionState) -> Unit>()

    fun update(transform: (SessionState) -> SessionState) {
        state = transform(state)
        listeners.toList().forEach { it(state) }
    }

    fun clear() {
        update { SessionState() }
    }

    fun observe(listener: (SessionState) -> Unit) {
        listeners += listener
        listener(state)
    }

    fun removeObserver(listener: (SessionState) -> Unit) {
        listeners -= listener
    }
}
