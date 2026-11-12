package com.itsash.flick

import java.io.File
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Replays a real recording (tools/sensorrec, `L` linear acceleration rows + `CHOP` rows from
 * Motorola's hardware chop-chop sensor) through [ChopDetector] and prints, per threshold, how
 * many Motorola chop-chops it caught and how many extra fires it made. Skipped unless the
 * env var CHOP_REC points at the CSV. Fed at 100 Hz like GestureEngine.
 */
class ChopReplayTest {
    @Test
    fun replay() {
        val path = System.getenv("CHOP_REC")
        assumeTrue(path != null && File(path).exists())
        val rows = mutableListOf<FloatArray>()
        val moto = mutableListOf<Double>()
        File(path!!).forEachLine { line ->
            val p = line.split(',')
            when (p[0]) {
                "L" -> rows += floatArrayOf(p[1].toFloat(), p[2].toFloat(), p[3].toFloat(), p[4].toFloat())
                "CHOP" -> moto += p[1].toDouble()
            }
        }
        val step = (System.getenv("CHOP_STEP") ?: "2").toInt()
        println("samples ${rows.size} (every $step), moto chop-chops ${moto.size}")
        for (thr in listOf(15f, 22f, 30f, 35f, 40f, 50f, 60f)) {
            val d = ChopDetector(thr)
            val fires = mutableListOf<Double>()
            val motions = StringBuilder()
            for (i in rows.indices step step) {
                val r = rows[i]
                val t = ((r[0] + 100_000.0) * 1_000_000.0).toLong()
                val seq = d.motionSeq
                if (d.add(t, r[1], r[2], r[3])) fires += r[0].toDouble()
                if (d.motionSeq != seq && d.lastMotion.contains("strong in a row 0").not()) {
                    val m = moto.minByOrNull { kotlin.math.abs(it - r[0]) }
                    motions.append("  %8.2f s (moto %+5.0f ms): %s\n".format(r[0] / 1000, r[0] - (m ?: 0.0), d.lastMotion))
                }
            }
            if (thr == 30f) print(motions)
            // Flick fires when the motion has ended, a few hundred ms after Motorola's event.
            fun near(f: Double, m: Double) = f - m in -300.0..800.0
            val delays = moto.mapNotNull { m -> fires.firstOrNull { near(it, m) }?.minus(m) }
            val missed = moto.filter { m -> fires.none { near(it, m) } }.map { (it / 1000).toInt() }
            val extra = fires.filter { f -> moto.none { near(f, it) } }.map { (it / 1000).toInt() }
            println(
                "thr %4.1f: fires %2d, caught %2d / %d, missed at %s s, extra at %s s, delay after moto %s ms"
                    .format(thr, fires.size, delays.size, moto.size, missed, extra, delays.map { it.toInt() }.sorted())
            )
        }
    }
}
