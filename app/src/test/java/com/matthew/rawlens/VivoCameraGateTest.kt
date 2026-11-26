// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import org.junit.Assert.*
import org.junit.Test

class VivoCameraGateTest {
    private class Values(val refuse: Boolean = false) : VivoCameraGate.Store {
        val values = mutableMapOf(40 to 0, 33 to 7, 1 to 0)
        override fun get(key: Int) = values.getValue(key)
        override fun set(key: Int, value: Int) { if (!refuse) values[key] = value }
    }
    private class Saved(var held: Pair<Int, Int?>? = null) : VivoCameraGate.Journal {
        override fun read() = held
        override fun write(useVivoCamera: Int, vcf: Int?) { held = useVivoCamera to vcf }
        override fun clear() { held = null }
    }

    @Test fun `gate restores the values it found`() {
        val store = Values(); val journal = Saved()
        val gate = VivoCameraGate(store, journal, settle = {})
        assertTrue(gate.acquire(vcf = true))
        assertEquals(listOf(1, 2), listOf(store.get(40), store.get(33)))
        gate.release()
        assertEquals(listOf(0, 7), listOf(store.get(40), store.get(33)))
        assertNull(journal.held)
    }

    @Test fun `plain gate leaves VCF alone and refusal leaves nothing held`() {
        val store = Values()
        assertTrue(VivoCameraGate(store, Saved(), settle = {}).acquire(vcf = false))
        assertEquals(7, store.get(33))
        val journal = Saved()
        assertFalse(VivoCameraGate(Values(refuse = true), journal, settle = {}).acquire(vcf = true))
        assertNull(journal.held)
    }

    @Test fun `values left by a killed process are given back`() {
        val store = Values().apply { values[40] = 1; values[33] = 2 }
        val gate = VivoCameraGate(store, Saved(0 to 7), settle = {})
        assertTrue(gate.acquire(vcf = false))
        assertEquals(7, store.get(33))
        gate.release()
        assertEquals(0, store.get(40))
    }

    @Test fun `still ids and high resolution keys are valid`() {
        VivoCameraGate.highResKeys.validate()
        assertEquals(listOf("5", "5", "10"), VivoCameraGate.stillKeys(5).entries.map { it.value })
    }
}
