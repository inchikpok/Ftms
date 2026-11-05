package com.custom.treadmill.logic

import android.app.Activity
import android.app.ActivityManager
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.sqlite.db.SupportSQLiteDatabase
import com.custom.treadmill.R
import com.custom.treadmill.TreadmillApp
import com.custom.treadmill.data.database.AppDatabase
import com.custom.treadmill.service.TreadmillService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

@Serializable
data class BackupManifest(
    val version: Int = 1,
    val appVersionName: String = "",
    val dbVersion: Int = 2,
    val createdAt: Long = 0L,
    val programCount: Int = 0,
    val workoutCount: Int = 0,
    val profileCount: Int = 0
)

sealed class BackupResult {
    data class Success(val manifest: BackupManifest) : BackupResult()
    data class Error(val message: String) : BackupResult()
}

object BackupManager {

    private const val DB_NAME = "treadmill.db"
    private const val PREFS_NAME = "treadmill_settings"

    private const val ENTRY_MANIFEST = "manifest.json"
    private const val ENTRY_DB = "database/$DB_NAME"
    private const val ENTRY_DB_WAL = "database/$DB_NAME-wal"
    private const val ENTRY_DB_SHM = "database/$DB_NAME-shm"
    private const val ENTRY_PREFS = "shared_prefs/$PREFS_NAME.xml"

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }

    fun defaultFileName(): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd-HHmmss", Locale.US)
        return "treadmill-backup-${fmt.format(Date())}.zip"
    }

    fun isWorkoutServiceRunning(context: Context): Boolean {
        return try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                ?: return false
            @Suppress("DEPRECATION")
            am.getRunningServices(Int.MAX_VALUE)
                .any { it.service.className == TreadmillService::class.java.name }
        } catch (_: Exception) {
            false
        }
    }

    private fun dbFile(context: Context): File =
        context.getDatabasePath(DB_NAME)

    private fun walFile(context: Context): File =
        File(dbFile(context).path + "-wal")

    private fun shmFile(context: Context): File =
        File(dbFile(context).path + "-shm")

    private fun prefsFile(context: Context): File =
        File(context.filesDir.parentFile, "shared_prefs/$PREFS_NAME.xml")

    private fun appVersionName(context: Context): String =
        try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""
        } catch (_: Exception) {
            ""
        }

    private fun countRows(db: SupportSQLiteDatabase, table: String): Int =
        try {
            db.query("SELECT COUNT(*) FROM $table").use { c ->
                if (c.moveToFirst()) c.getInt(0) else 0
            }
        } catch (_: Exception) {
            0
        }

    // ============================ EXPORT ============================

    suspend fun exportTo(uri: Uri, context: Context): BackupResult =
        withContext(Dispatchers.IO) {
            try {
                val app = TreadmillApp.instance

                try {
                    val raw = app.database.openHelper.writableDatabase
                    raw.query("PRAGMA wal_checkpoint(TRUNCATE)").use { it.moveToFirst() }
                } catch (_: Exception) {
                }

                val manifest = try {
                    val raw = app.database.openHelper.writableDatabase
                    BackupManifest(
                        version = 1,
                        appVersionName = appVersionName(context),
                        dbVersion = 2,
                        createdAt = System.currentTimeMillis(),
                        programCount = countRows(raw, "programs"),
                        workoutCount = countRows(raw, "workout_logs"),
                        profileCount = app.settingsStore.settings.value.profiles.size
                    )
                } catch (_: Exception) {
                    BackupManifest(
                        version = 1,
                        appVersionName = appVersionName(context),
                        createdAt = System.currentTimeMillis()
                    )
                }

                val db = dbFile(context)
                val wal = walFile(context)
                val shm = shmFile(context)
                val prefs = prefsFile(context)

                context.contentResolver.openOutputStream(uri)?.use { os ->
                    ZipOutputStream(os).use { zos ->
                        zos.putNextEntry(ZipEntry(ENTRY_MANIFEST))
                        zos.write(json.encodeToString(manifest).toByteArray(Charsets.UTF_8))
                        zos.closeEntry()

                        if (db.exists()) {
                            zos.putNextEntry(ZipEntry(ENTRY_DB))
                            db.inputStream().use { it.copyTo(zos) }
                            zos.closeEntry()
                        }
                        if (wal.exists() && wal.length() > 0) {
                            zos.putNextEntry(ZipEntry(ENTRY_DB_WAL))
                            wal.inputStream().use { it.copyTo(zos) }
                            zos.closeEntry()
                        }
                        if (shm.exists() && shm.length() > 0) {
                            zos.putNextEntry(ZipEntry(ENTRY_DB_SHM))
                            shm.inputStream().use { it.copyTo(zos) }
                            zos.closeEntry()
                        }
                        if (prefs.exists()) {
                            zos.putNextEntry(ZipEntry(ENTRY_PREFS))
                            prefs.inputStream().use { it.copyTo(zos) }
                            zos.closeEntry()
                        }
                    }
                } ?: return@withContext BackupResult.Error(
                    context.getString(R.string.backup_export_error, "cannot open output")
                )

                BackupResult.Success(manifest)
            } catch (e: Exception) {
                BackupResult.Error(
                    context.getString(R.string.backup_export_error, e.message ?: "unknown")
                )
            }
        }

    // ============================ READ MANIFEST ============================

    suspend fun readManifest(uri: Uri, context: Context): BackupManifest? =
        withContext(Dispatchers.IO) {
            try {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    ZipInputStream(input).use { zis ->
                        var entry: ZipEntry? = zis.nextEntry
                        while (entry != null) {
                            if (!entry.isDirectory && entry.name == ENTRY_MANIFEST) {
                                val text = zis.readBytes().toString(Charsets.UTF_8)
                                return@withContext try {
                                    json.decodeFromString<BackupManifest>(text)
                                } catch (_: Exception) {
                                    null
                                }
                            }
                            zis.closeEntry()
                            entry = zis.nextEntry
                        }
                    }
                }
                null
            } catch (_: Exception) {
                null
            }
        }

    // ============================ RESTORE ============================

    /**
     * Восстановление данных из ZIP. Требует перезапуска приложения.
     *
     * [ФИКС] Атомарное восстановление:
     *   1. Распаковать во временную папку
     *   2. Провалидировать (есть manifest + .db)
     *   3. Закрыть Room
     *   4. Переименовать текущие файлы в .bak (а не удалять)
     *   5. Скопировать новые файлы
     *   6. При ошибке на любом этапе — откатить .bak обратно
     *   7. При успехе — удалить .bak
     */
    suspend fun restoreFrom(uri: Uri, context: Context): BackupResult =
        withContext(Dispatchers.IO) {
            val tmpDir = File(context.cacheDir, "treadmill-restore-tmp")
            try {
                tmpDir.deleteRecursively()
                tmpDir.mkdirs()

                // 1. Распаковать во временную папку
                var manifest: BackupManifest? = null
                context.contentResolver.openInputStream(uri)?.use { input ->
                    ZipInputStream(input).use { zis ->
                        var entry: ZipEntry? = zis.nextEntry
                        while (entry != null) {
                            if (!entry.isDirectory) {
                                val target = when (entry.name) {
                                    ENTRY_MANIFEST -> File(tmpDir, "manifest.json")
                                    ENTRY_DB -> File(tmpDir, DB_NAME)
                                    ENTRY_DB_WAL -> File(tmpDir, "$DB_NAME-wal")
                                    ENTRY_DB_SHM -> File(tmpDir, "$DB_NAME-shm")
                                    ENTRY_PREFS -> File(tmpDir, "$PREFS_NAME.xml")
                                    else -> null
                                }
                                if (target != null) {
                                    target.parentFile?.mkdirs()
                                    target.outputStream().use { zis.copyTo(it) }
                                    if (entry.name == ENTRY_MANIFEST) {
                                        manifest = try {
                                            json.decodeFromString<BackupManifest>(
                                                target.readText(Charsets.UTF_8)
                                            )
                                        } catch (_: Exception) {
                                            null
                                        }
                                    }
                                }
                            }
                            zis.closeEntry()
                            entry = zis.nextEntry
                        }
                    }
                } ?: run {
                    tmpDir.deleteRecursively()
                    return@withContext BackupResult.Error(
                        context.getString(R.string.backup_import_read_error, "cannot open input")
                    )
                }

                // 2. Валидация
                val tmpDb = File(tmpDir, DB_NAME)
                val tmpManifest = File(tmpDir, "manifest.json")
                if (!tmpManifest.exists() || !tmpDb.exists()) {
                    tmpDir.deleteRecursively()
                    return@withContext BackupResult.Error(
                        context.getString(R.string.backup_import_invalid)
                    )
                }

                // 3. Закрыть Room
                AppDatabase.closeAndReset()

                val db = dbFile(context)
                val wal = walFile(context)
                val shm = shmFile(context)
                val prefs = prefsFile(context)

                val dbBak = File(db.path + ".bak")
                val walBak = File(wal.path + ".bak")
                val shmBak = File(shm.path + ".bak")
                val prefsBak = File(prefs.path + ".bak")

                // Очистка старых бэкапов, если остались от предыдущих попыток
                dbBak.delete(); walBak.delete(); shmBak.delete(); prefsBak.delete()

                // 4. Бэкап текущих файлов через rename (быстро + атомарно в пределах FS)
                if (db.exists()) db.renameTo(dbBak)
                if (wal.exists()) wal.renameTo(walBak)
                if (shm.exists()) shm.renameTo(shmBak)
                if (prefs.exists()) prefs.renameTo(prefsBak)

                try {
                    // 5. Замена на новые
                    db.parentFile?.mkdirs()
                    tmpDb.copyTo(db, overwrite = true)
                    File(tmpDir, "$DB_NAME-wal").takeIf { it.exists() }?.copyTo(wal, overwrite = true)
                    File(tmpDir, "$DB_NAME-shm").takeIf { it.exists() }?.copyTo(shm, overwrite = true)
                    File(tmpDir, "$PREFS_NAME.xml").takeIf { it.exists() }?.copyTo(prefs, overwrite = true)

                    // 6. Переоткрытие (sanity check)
                    AppDatabase.get(context)
                } catch (e: Exception) {
                    // ОТКАТ: восстанавливаем старые файлы из .bak
                    if (dbBak.exists()) { db.delete(); dbBak.renameTo(db) }
                    if (walBak.exists()) { wal.delete(); walBak.renameTo(wal) }
                    if (shmBak.exists()) { shm.delete(); shmBak.renameTo(shm) }
                    if (prefsBak.exists()) { prefs.delete(); prefsBak.renameTo(prefs) }

                    // Пытаемся открыть старую БД обратно, чтобы приложение не осталось без данных
                    try { AppDatabase.get(context) } catch (_: Exception) {}
                    throw e
                }

                // 7. Успех — удаляем бэкапы
                dbBak.delete(); walBak.delete(); shmBak.delete(); prefsBak.delete()

                BackupResult.Success(manifest ?: BackupManifest())
            } catch (e: Exception) {
                BackupResult.Error(
                    context.getString(R.string.backup_import_error, e.message ?: "unknown")
                )
            } finally {
                try {
                    tmpDir.deleteRecursively()
                } catch (_: Exception) {
                }
            }
        }

    // ============================ RESTART ============================

    fun restartApp(context: Context) {
        try {
            val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
                ?: return
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)

            val flags = PendingIntent.FLAG_CANCEL_CURRENT or
                (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
            val pending = PendingIntent.getActivity(context, 1, launch, flags)

            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            am.set(AlarmManager.RTC, System.currentTimeMillis() + 150, pending)

            Handler(Looper.getMainLooper()).postDelayed({
                android.os.Process.killProcess(android.os.Process.myPid())
            }, 100)
        } catch (_: Exception) {
            try {
                val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
                if (launch != null) {
                    launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    context.startActivity(launch)
                }
                findActivity(context)?.finishAffinity()
            } catch (_: Exception) {
            }
        }
    }

    private fun findActivity(context: Context): Activity? {
        var ctx: Context? = context
        while (ctx is ContextWrapper) {
            if (ctx is Activity) return ctx
            ctx = ctx.baseContext
        }
        return null
    }
}
