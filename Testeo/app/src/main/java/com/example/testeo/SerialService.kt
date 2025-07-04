package com.example.testeo

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import java.io.IOException
import java.util.ArrayDeque

/**
 * Crea notificaciones y maneja cola de datos mientras la actividad no está en primer plano
 * Cadena de listeners: SerialSocket -> SerialService -> UI fragment
 */
class SerialService : Service(), SerialListener {

    inner class SerialBinder : Binder() {
        fun getService(): SerialService = this@SerialService
    }

    private enum class QueueType { Connect, ConnectError, Read, IoError }

    private class QueueItem {
        val type: QueueType
        var datas: ArrayDeque<ByteArray>? = null
        var e: Exception? = null

        constructor(type: QueueType) {
            this.type = type
            if (type == QueueType.Read) init()
        }

        constructor(type: QueueType, e: Exception) {
            this.type = type
            this.e = e
        }

        constructor(type: QueueType, datas: ArrayDeque<ByteArray>) {
            this.type = type
            this.datas = datas
        }

        fun init() {
            datas = ArrayDeque()
        }

        fun add(data: ByteArray) {
            datas?.add(data)
        }
    }

    private val mainLooper: Handler = Handler(Looper.getMainLooper())
    private val binder: IBinder = SerialBinder()
    private val queue1: ArrayDeque<QueueItem> = ArrayDeque()
    private val queue2: ArrayDeque<QueueItem> = ArrayDeque()
    private val lastRead: QueueItem = QueueItem(QueueType.Read)

    private var socket: SerialSocket? = null
    private var listener: SerialListener? = null
    private var connected: Boolean = false

    /**
     * Lifecycle
     */
    override fun onDestroy() {
        cancelNotification()
        disconnect()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    /**
     * API
     */
    @Throws(IOException::class)
    fun connect(socket: SerialSocket) {
        socket.connect(this)
        this.socket = socket
        connected = true
    }

    fun disconnect() {
        connected = false // ignorar datos/errores mientras se desconecta
        cancelNotification()
        socket?.let {
            it.disconnect()
            socket = null
        }
    }

    @Throws(IOException::class)
    fun write(data: ByteArray) {
        if (!connected)
            throw IOException("not connected")
        socket?.write(data)
    }

    fun attach(listener: SerialListener) {
        if (Looper.getMainLooper().thread != Thread.currentThread())
            throw IllegalArgumentException("not in main thread")

        initNotification()
        cancelNotification()

        // usar synchronized() para prevenir nuevos items en queue2
        synchronized(this) {
            this.listener = listener
        }

        // Procesar queue1
        for (item in queue1) {
            when (item.type) {
                QueueType.Connect -> listener.onSerialConnect()
                QueueType.ConnectError -> item.e?.let { listener.onSerialConnectError(it) }
                QueueType.Read -> item.datas?.let { listener.onSerialRead(it) }
                QueueType.IoError -> item.e?.let { listener.onSerialIoError(it) }
            }
        }

        // Procesar queue2
        for (item in queue2) {
            when (item.type) {
                QueueType.Connect -> listener.onSerialConnect()
                QueueType.ConnectError -> item.e?.let { listener.onSerialConnectError(it) }
                QueueType.Read -> item.datas?.let { listener.onSerialRead(it) }
                QueueType.IoError -> item.e?.let { listener.onSerialIoError(it) }
            }
        }

        queue1.clear()
        queue2.clear()
    }

    fun detach() {
        if (connected)
            createNotification()
        listener = null
    }

    private fun initNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nc = NotificationChannel(
                Constants.NOTIFICATION_CHANNEL,
                "Background service",
                NotificationManager.IMPORTANCE_LOW
            )
            nc.setShowBadge(false)
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(nc)
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    fun areNotificationsEnabled(): Boolean {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val nc = nm.getNotificationChannel(Constants.NOTIFICATION_CHANNEL)
        return nm.areNotificationsEnabled() && nc != null &&
                nc.importance > NotificationManager.IMPORTANCE_NONE
    }

    private fun createNotification() {
        val disconnectIntent = Intent()
            .setPackage(packageName)
            .setAction(Constants.INTENT_ACTION_DISCONNECT)

        val restartIntent = Intent()
            .setClassName(this, Constants.INTENT_CLASS_MAIN_ACTIVITY)
            .setAction(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)

        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            PendingIntent.FLAG_IMMUTABLE else 0

        val disconnectPendingIntent = PendingIntent.getBroadcast(this, 1, disconnectIntent, flags)
        val restartPendingIntent = PendingIntent.getActivity(this, 1, restartIntent, flags)

        val builder = NotificationCompat.Builder(this, Constants.NOTIFICATION_CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(resources.getColor(R.color.colorPrimary))
            .setContentTitle(resources.getString(R.string.app_name))
            .setContentText(socket?.let { "Connected to ${it.getName()}" } ?: "Background Service")
            .setContentIntent(restartPendingIntent)
            .setOngoing(true)
            .addAction(NotificationCompat.Action(
                R.drawable.ic_clear_white_24dp,
                "Disconnect",
                disconnectPendingIntent
            ))

        val notification: Notification = builder.build()
        startForeground(Constants.NOTIFY_MANAGER_START_FOREGROUND_SERVICE, notification)
    }

    private fun cancelNotification() {
        stopForeground(true)
    }

    /**
     * SerialListener Implementation
     */
    override fun onSerialConnect() {
        if (connected) {
            synchronized(this) {
                listener?.let { listener ->
                    mainLooper.post {
                        this.listener?.onSerialConnect() ?: run {
                            queue1.add(QueueItem(QueueType.Connect))
                        }
                    }
                } ?: run {
                    queue2.add(QueueItem(QueueType.Connect))
                }
            }
        }
    }

    override fun onSerialConnectError(e: Exception) {
        if (connected) {
            synchronized(this) {
                listener?.let { listener ->
                    mainLooper.post {
                        this.listener?.onSerialConnectError(e) ?: run {
                            queue1.add(QueueItem(QueueType.ConnectError, e))
                            disconnect()
                        }
                    }
                } ?: run {
                    queue2.add(QueueItem(QueueType.ConnectError, e))
                    disconnect()
                }
            }
        }
    }

    override fun onSerialRead(datas: ArrayDeque<ByteArray>) {
        throw UnsupportedOperationException()
    }

    /**
     * Reduce número de actualizaciones UI combinando chunks de datos.
     * Los datos pueden llegar en cientos de chunks por segundo, pero la UI solo
     * puede realizar una docena de actualizaciones si receiveText ya contiene mucho texto.
     */
    override fun onSerialRead(data: ByteArray) {
        if (connected) {
            synchronized(this) {
                listener?.let { listener ->
                    val first: Boolean
                    synchronized(lastRead) {
                        first = lastRead.datas?.isEmpty() ?: true
                        lastRead.add(data)
                    }

                    if (first) {
                        mainLooper.post {
                            val datas: ArrayDeque<ByteArray>
                            synchronized(lastRead) {
                                datas = lastRead.datas ?: ArrayDeque()
                                lastRead.init()
                            }

                            this.listener?.onSerialRead(datas) ?: run {
                                queue1.add(QueueItem(QueueType.Read, datas))
                            }
                        }
                    }
                } ?: run {
                    if (queue2.isEmpty() || queue2.last.type != QueueType.Read)
                        queue2.add(QueueItem(QueueType.Read))
                    queue2.last.add(data)
                }
            }
        }
    }

    override fun onSerialIoError(e: Exception) {
        if (connected) {
            synchronized(this) {
                listener?.let { listener ->
                    mainLooper.post {
                        this.listener?.onSerialIoError(e) ?: run {
                            queue1.add(QueueItem(QueueType.IoError, e))
                            disconnect()
                        }
                    }
                } ?: run {
                    queue2.add(QueueItem(QueueType.IoError, e))
                    disconnect()
                }
            }
        }
    }
}