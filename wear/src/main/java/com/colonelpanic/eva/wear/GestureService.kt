package com.colonelpanic.eva.wear

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.wear.ongoing.OngoingActivity
import androidx.wear.ongoing.Status
import com.colonelpanic.eva.diagnostics.EvaTrace
import kotlin.math.abs
import kotlin.math.sqrt

class GestureService :
    Service(),
    SensorEventListener {
    private val runtime get() = (application as WatchApplication).runtime
    private lateinit var sensors: SensorManager
    private var wakeLock: PowerManager.WakeLock? = null
    private var detector: AlternatingTwistDetector? = null
    private var applied: WearGestureConfiguration? = null
    private var acceleration = Double.POSITIVE_INFINITY
    private var accelerationAt = Long.MIN_VALUE
    private var lastGyroAt = 0L
    private var lastAccelAt = 0L
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val watchdog =
        object : Runnable {
            override fun run() {
                if (SystemClock.elapsedRealtime() - minOf(lastGyroAt, lastAccelAt) > 3000) {
                    runtime.error(
                        "Motion sensors stopped delivering samples. Reopen EVA on the watch and check battery/background restrictions.",
                    )
                    stopSelf()
                } else {
                    handler.postDelayed(this, 1000)
                }
            }
        }

    override fun onCreate() {
        super.onCreate()
        sensors = getSystemService(SensorManager::class.java)
    }

    @SuppressLint("WakelockTimeout")
    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        if (intent?.action == PAUSE) {
            runtime.pause()
            stopSelf()
            return START_NOT_STICKY
        }
        if (!runtime.config.enabled || runtime.paused) {
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            promote()
            if (applied != runtime.config) {
                sensors.unregisterListener(this)
                detector = AlternatingTwistDetector(runtime.config)
                applied = runtime.config
                val gyro = sensors.getDefaultSensor(Sensor.TYPE_GYROSCOPE, true) ?: sensors.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
                val accel = sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER, true) ?: sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
                check(gyro != null && accel != null) { "This watch needs a gyroscope and accelerometer." }
                check(sensors.registerListener(this, accel, SAMPLE_MICROS, 0)) { "Android refused accelerometer registration." }
                check(sensors.registerListener(this, gyro, SAMPLE_MICROS, 0)) { "Android refused gyroscope registration." }
                EvaTrace.info(
                    "wear.sensing.started",
                    "gyroWakeUp" to gyro.isWakeUpSensor,
                    "accelWakeUp" to accel.isWakeUpSensor,
                    "sampleMicros" to SAMPLE_MICROS,
                )
            }
            if (wakeLock == null) {
                wakeLock =
                    getSystemService(
                        PowerManager::class.java,
                    ).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "eva:WristGesture").apply { acquire() }
            }
            val twists = runtime.config.requiredPulses / 2
            val gesture = if (twists == 1) "Twist sharply out and back once." else "Twist sharply out and back $twists times."
            runtime.status("Listening, including with the screen off. $gesture")
            lastGyroAt = SystemClock.elapsedRealtime()
            lastAccelAt = lastGyroAt
            handler.removeCallbacks(watchdog)
            handler.postDelayed(watchdog, 3000)
            return START_STICKY
        } catch (error: Exception) {
            runtime.error("Cannot listen: ${error.message ?: error.javaClass.simpleName}. Open EVA on the watch to retry.")
            stopSelf()
            return START_NOT_STICKY
        }
    }

    private fun promote() {
        getSystemService(
            NotificationManager::class.java,
        ).createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.channel), NotificationManager.IMPORTANCE_LOW))
        val touch =
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, WatchActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        val pause =
            PendingIntent.getService(
                this,
                1,
                Intent(this, GestureService::class.java).setAction(PAUSE),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        val builder =
            NotificationCompat
                .Builder(
                    this,
                    CHANNEL,
                ).setSmallIcon(
                    R.drawable.ic_watch,
                ).setContentTitle(
                    getString(R.string.app_name),
                ).setContentText(
                    getString(R.string.notification, runtime.config.requiredPulses),
                ).setContentIntent(touch)
                .setOngoing(true)
                .addAction(0, getString(R.string.stop), pause)
        OngoingActivity
            .Builder(
                this,
                NOTIFICATION,
                builder,
            ).setStaticIcon(
                R.drawable.ic_watch,
            ).setTouchIntent(touch)
            .setStatus(Status.Builder().addTemplate(getString(R.string.notification, runtime.config.requiredPulses)).build())
            .build()
            .apply(this)
        startForeground(NOTIFICATION, builder.build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
    }

    override fun onSensorChanged(event: SensorEvent) {
        val millis = event.timestamp / 1_000_000
        if (event.sensor.type == Sensor.TYPE_ACCELEROMETER) {
            lastAccelAt = SystemClock.elapsedRealtime()
            val v = event.values
            acceleration = abs(sqrt(v[0].toDouble() * v[0] + v[1].toDouble() * v[1] + v[2].toDouble() * v[2]) - SensorManager.GRAVITY_EARTH)
            accelerationAt = millis
        } else if (event.sensor.type == Sensor.TYPE_GYROSCOPE) {
            lastGyroAt = SystemClock.elapsedRealtime()
            val a =
                acceleration.takeIf {
                    accelerationAt != Long.MIN_VALUE && abs(
                        millis - accelerationAt,
                    ) <= runtime.config.maxSampleGapMillis
                }
                    ?: Double.POSITIVE_INFINITY
            detector
                ?.sample(
                    millis,
                    event.values[0].toDouble(),
                    event.values[1].toDouble(),
                    event.values[2].toDouble(),
                    a,
                )?.forEach(runtime::gesture)
        }
    }

    override fun onAccuracyChanged(
        sensor: Sensor?,
        accuracy: Int,
    ) = Unit

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        handler.removeCallbacks(watchdog)
        sensors.unregisterListener(this)
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        EvaTrace.info("wear.sensing.stopped")
        super.onDestroy()
    }

    companion object {
        private const val SAMPLE_MICROS = 10_000
        private const val CHANNEL = "eva.wrist.listener"
        private const val NOTIFICATION = 7101
        private const val PAUSE = "com.colonelpanic.eva.wear.PAUSE"
    }
}
