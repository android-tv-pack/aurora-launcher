package app.hermes.aurora

/**
 * Finds the beat in the loudness of the bass (0..1, about twenty readings a second).
 *
 * A beat is the moment the bass jumps up: clearly louder than its recent average AND clearly louder than it was
 * a moment ago. The second condition matters: a kick drum stays loud for a quarter of a second, and without it
 * one kick counted as two beats.
 */
internal class Beat {
    private var avg = 0f
    private var p1 = 0f
    private var p2 = 0f
    private var last = 0L

    /** [bass] at [now] (milliseconds): true when this reading is a beat */
    fun feed(bass: Float, now: Long): Boolean {
        val hit = bass > avg * 1.28f + 0.07f && bass > 0.3f && bass - minOf(p1, p2) > RISE && now - last > GAP_MS
        if (hit) last = now
        avg = avg * 0.92f + bass * 0.08f
        p2 = p1; p1 = bass
        return hit
    }

    val average: Float get() = avg

    companion object {
        const val RISE = 0.15f
        const val GAP_MS = 170L
    }
}
