package com.zhishi.schedule.data

import android.content.Context
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File

/** Kept out of backups; the encryption key belongs to Android Keystore. */
class CampusCredentials(val studentId: String, val password: String) {
    override fun toString() = "CampusCredentials(redacted)"
}

interface CampusCredentialStorage {
    fun read(): CampusCredentials?
    fun save(credentials: CampusCredentials)
    fun clear()
}

class CampusCredentialStore(context: Context) : CampusCredentialStorage {
    private val file = AtomicFile(File(context.noBackupFilesDir, "campus-login.enc"))
    private val secrets = SecretStore("zhishi_campus_login_v1", "学校登录信息")

    override fun read(): CampusCredentials? {
        if (!file.baseFile.exists()) return null
        val data = JSONObject(secrets.decrypt(file.readFully().toString(Charsets.UTF_8)))
        return CampusCredentials(data.getString("studentId"), data.getString("password"))
    }

    override fun save(credentials: CampusCredentials) {
        val encrypted = secrets.encrypt(JSONObject().put("studentId", credentials.studentId)
            .put("password", credentials.password).toString())
        val output = file.startWrite()
        try { output.write(encrypted.toByteArray(Charsets.UTF_8)); file.finishWrite(output) }
        catch (e: Exception) { file.failWrite(output); throw e }
    }

    override fun clear() = file.delete()
}
