package com.example.textmessageapi

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.provider.CallLog
import android.provider.MediaStore
import android.util.Log
import androidx.core.app.NotificationCompat
import org.json.JSONArray
import org.json.JSONObject

class MessageAndCallService : Service() {

    private val handler = Handler()
    private val runnable = object : Runnable {
        override fun run() {
            collectMessagesAndCalls()
            handler.postDelayed(this, 5000) // Run every 5 seconds
        }
    }

    // URIs to keep track of the files created
    private var messagesFileUri: Uri? = null
    private var callsFileUri: Uri? = null

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
        Log.d(TAG, "Service Destroyed")
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    private fun collectMessagesAndCalls() {
        println("Collecting Messages!")
        collectMessages()
        println("Collected Messages!")
        collectCallLogs()
        println("Collected Calls!")
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
        messagesFileUri = saveDataToJsonFile("messages.json", messages, messagesFileUri)
        println("Messages File Path ${messagesFileUri?.path}")

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
        callsFileUri = saveDataToJsonFile("calls.json", calls, callsFileUri)
        println("Messages File Path ${callsFileUri?.path}")
    }

    private fun saveDataToJsonFile(filename: String, data: JSONArray, existingUri: Uri?): Uri? {
        val resolver = contentResolver
        val externalUri = existingUri?:MediaStore.Files.getContentUri("external")

        // If file exists, delete it
        existingUri?.let {
            resolver.delete(it, null, null)
        }

        // Create new file
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
            put(MediaStore.MediaColumns.MIME_TYPE, "application/json")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOCUMENTS)
        }

        val uri: Uri? = resolver.insert(externalUri, contentValues)
        uri?.let {
            resolver.openOutputStream(it)?.use { outputStream ->
                outputStream.write(data.toString().toByteArray())
                Log.d(TAG, "Data saved to $filename")
            }
        } ?: Log.e(TAG, "Failed to create new file")

        return uri
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
