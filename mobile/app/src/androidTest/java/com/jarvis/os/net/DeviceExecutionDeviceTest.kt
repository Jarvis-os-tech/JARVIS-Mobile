package com.jarvis.os.net

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.jarvis.os.ui.MainActivity
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DeviceExecutionDeviceTest {
    @Test fun phoneCallsTheExistingPcExecutorAndOwnNativeAdapter() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.startActivitySync(Intent(instrumentation.targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val client = DeviceToolClient(instrumentation.targetContext)
        val tools = client.declarations()
        val names = (0 until tools.length()).map { tools.getJSONObject(it).getString("name") }
        assertTrue("Existing desktop tools must be discoverable", "pc_system_query" in names)
        assertTrue("Phone tools must also be available", "phone_status" in names)
        val local = JSONObject(client.execute(FunctionCall("local", "phone_status", emptyMap()), "device-test:local"))
        assertEquals("phone", local.getString("device"))
        val identity = "device-test:" + java.util.UUID.randomUUID()
        val call = FunctionCall("pc", "pc_system_query", mapOf("topic" to "time"))
        val remote = JSONObject(client.execute(call, identity))
        assertEquals("pc", remote.getString("device"))
        assertEquals("completed", remote.getString("status"))
        assertFalse(remote.getString("output").startsWith("ERROR:"))
        assertEquals(remote.toString(), JSONObject(client.execute(call, identity)).toString())
    }
}
