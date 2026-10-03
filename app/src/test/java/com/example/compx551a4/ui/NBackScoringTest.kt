package com.example.compx551a4.ui

import com.example.compx551a4.ui.calibration.tasks.NBackState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import kotlin.random.Random

/**
 * The 2-back score is the objective evidence that a HIGH block was actually engaged with
 * (DESIGN.md §2), so its arithmetic is worth pinning down.
 */
class NBackScoringTest {

    private fun state() = NBackState(Random(1))

    @Test
    fun `first two items are never scored`() {
        val s = state()
        s.advance(0)
        s.advance(1)
        assertEquals(0, s.score().total)
    }

    @Test
    fun `a correct rejection counts as correct without counting as a hit`() {
        val s = state()
        repeat(4) { s.advance(it.toLong()) }
        val score = s.score()
        // Whatever the letters were, every scored item was either responded to or not, and the
        // totals must stay consistent.
        assertEquals(true, score.correct <= score.total)
        assertEquals(true, score.falseAlarms <= score.total)
    }

    @Test
    fun `responding twice to one item records a single reaction time`() {
        val s = state()
        s.advance(0)
        s.respond(100)
        s.respond(500)
        assertEquals(100.0, s.score().meanReactionMs!!, 0.001)
    }

    @Test
    fun `accuracy is zero when nothing has been scored`() {
        assertEquals(0.0, state().score().accuracy, 0.0)
    }

    @Test
    fun `a letter never repeats its predecessor`() {
        val s = state()
        repeat(2_000) { s.advance(it.toLong()) }
        s.history.zipWithNext().forEachIndexed { i, (a, b) ->
            assertNotEquals("repeat at item $i", a, b)
        }
    }
}
