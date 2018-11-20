package com.zhaisoft.mylauncher.gestures

import android.text.TextUtils
import android.view.MotionEvent
import com.zhaisoft.mylauncher.Launcher
import com.zhaisoft.mylauncher.gestures.dt2s.DoubleTapGesture
import com.zhaisoft.mylauncher.util.TouchController

class GestureController(val launcher: Launcher) : TouchController {

    private val doubleTapGesture = DoubleTapGesture(this)

    override fun onControllerInterceptTouchEvent(ev: MotionEvent): Boolean {
        return false
    }

    override fun onControllerTouchEvent(ev: MotionEvent): Boolean {
        return false
    }

    fun createGestureHandler(className: String): GestureHandler? {
        if (!TextUtils.isEmpty(className)) {
            try {
                return Class.forName(className).getConstructor(Launcher::class.java).newInstance(launcher) as? GestureHandler
            } catch (t: Throwable) {
            }
        }
        return null
    }

    fun onBlankAreaTouch(ev: MotionEvent) {
        doubleTapGesture.isEnabled && doubleTapGesture.onTouchEvent(ev)
    }
}
