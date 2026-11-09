package ai.capri.app

/** Isolated ordinary Android process for instrumentation smoke tests, never the Agent process. */
class FridaFixtureService : android.app.Service() {
    override fun onBind(intent: android.content.Intent?): android.os.IBinder? = null
    override fun onStartCommand(intent: android.content.Intent?, flags: Int, startId: Int): Int {
        java.io.File(cacheDir,"frida-fixture.pid").writeText(android.os.Process.myPid().toString())
        return START_NOT_STICKY
    }
    override fun onDestroy() {
        java.io.File(cacheDir,"frida-fixture.pid").delete()
        super.onDestroy()
    }
}
