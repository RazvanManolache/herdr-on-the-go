package io.github.razvanmanolache.herdronthego

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import androidx.test.runner.AndroidJUnitRunner
import java.io.File
import java.util.UUID

/** MainActivity tests also use isolated storage, including automatic legacy migration. */
class IsolatedTestApplication : Application() {
    private val prefix = "activity_test_${UUID.randomUUID()}_"
    private val names = mutableSetOf<String>()
    override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
        synchronized(names) { names.add(name) }
        return super.getSharedPreferences(prefix + name, mode)
    }
    override fun deleteSharedPreferences(name: String) = super.deleteSharedPreferences(prefix + name)
    override fun getNoBackupFilesDir(): File = File(super.getNoBackupFilesDir(), prefix).also { it.mkdirs() }
    fun cleanup() {
        synchronized(names) { names.toList() }.forEach { deleteSharedPreferences(it) }
        noBackupFilesDir.deleteRecursively()
    }
}

class IsolatedTestRunner : AndroidJUnitRunner() {
    private var testApplication: IsolatedTestApplication? = null
    override fun newApplication(cl: ClassLoader, className: String, context: Context): Application =
        super.newApplication(javaClass.classLoader, IsolatedTestApplication::class.java.name, context).also {
            testApplication = it as IsolatedTestApplication
        }
    override fun finish(resultCode: Int, results: Bundle) {
        testApplication?.cleanup()
        super.finish(resultCode, results)
    }
}
