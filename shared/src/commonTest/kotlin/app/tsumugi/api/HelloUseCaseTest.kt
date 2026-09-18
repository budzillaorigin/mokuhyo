package app.tsumugi.api

import app.tsumugi.platform.Platform
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HelloUseCaseTest {

    private val fakePlatform = object : Platform {
        override val name = "TestOS"
    }

    @Test
    fun greetingNamesPlatformAndVersion() = runTest {
        val greeting = HelloUseCase(fakePlatform).greeting().first()

        assertEquals("今日", greeting.title)
        assertTrue("TestOS" in greeting.message)
        assertTrue(SharedInfo.VERSION in greeting.message)
    }
}
