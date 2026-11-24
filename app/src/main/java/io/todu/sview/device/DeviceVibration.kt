package io.todu.sview.device

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/** 界面点击和脚本振动共用设备适配的单次效果。 */
internal object DeviceVibration {
    @Suppress("DEPRECATION")
    fun vibrator(context: Context): Vibrator = if (Build.VERSION.SDK_INT >= 31) {
        context.getSystemService(VibratorManager::class.java).defaultVibrator
    } else {
        context.getSystemService(Vibrator::class.java)
    }

    /** 20 表示系统短促点击效果，其余值表示振动毫秒数。 */
    fun effect(duration: Long): VibrationEffect = if (duration == 20L && Build.VERSION.SDK_INT >= 29) {
        VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK)
    } else {
        VibrationEffect.createOneShot(duration, VibrationEffect.DEFAULT_AMPLITUDE)
    }

    /** 用户点击或完成返回手势时触发一次，不启动持续振动。 */
    fun click(context: Context) {
        val vibrator = vibrator(context)
        if (vibrator.hasVibrator()) vibrator.vibrate(effect(20L))
    }
}
