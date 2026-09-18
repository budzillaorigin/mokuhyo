package app.tsumugi.settings

import app.tsumugi.db.TsumugiDatabase
import app.tsumugi.integrations.wanikani.FakeSecrets
import app.tsumugi.testing.inMemoryDriver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class DeviceStateTest {

    private val db = TsumugiDatabase(inMemoryDriver(TsumugiDatabase.Schema))

    @Test
    fun freshInstallKeepsItsIdAcrossLaunches() {
        val secrets = FakeSecrets()
        val first = DeviceState(db, secrets).deviceId
        assertEquals(first, DeviceState(db, secrets).deviceId)
    }

    @Test
    fun databaseRestoredOntoAnotherDeviceGetsANewId() {
        val original = DeviceState(db, FakeSecrets()).deviceId
        // Same database (backup restore / device transfer), but this device's keychain doesn't hold the id.
        val otherPhone = FakeSecrets()
        val restored = DeviceState(db, otherPhone).deviceId
        assertNotEquals(original, restored)
        assertEquals(restored, DeviceState(db, otherPhone).deviceId, "the new id sticks")
    }

    @Test
    fun withoutSecretsTheStoredIdIsKept() {
        val id = DeviceState(db).deviceId
        assertEquals(id, DeviceState(db).deviceId)
    }
}
