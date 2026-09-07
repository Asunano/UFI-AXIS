package com.ufi_axis.data.repository

import org.junit.Test
import org.junit.Assert.*

class ConnectionStateTest {

    @Test
    fun `ConnectionState - all values`() {
        val states = ConnectionState.values()
        assertEquals(4, states.size)
        assertNotNull(ConnectionState.valueOf("CONNECTED"))
        assertNotNull(ConnectionState.valueOf("DISCONNECTED"))
        assertNotNull(ConnectionState.valueOf("CONNECTING"))
        assertNotNull(ConnectionState.valueOf("RECONNECTING"))
    }

    @Test
    fun `ConnectionState - default is DISCONNECTED`() {
        val state = ConnectionState.DISCONNECTED
        assertEquals(ConnectionState.DISCONNECTED, state)
    }
}
