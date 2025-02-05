package com.pyamsoft.tetherfi

import android.app.Application
import androidx.annotation.CheckResult
import com.pyamsoft.pydroid.core.PYDroidLogger
import com.pyamsoft.pydroid.util.isDebugMode
import timber.log.Timber

fun Application.installLogger() {
  if (isDebugMode()) {
    Timber.plant(
        object : Timber.DebugTree() {
          override fun createStackElementTag(element: StackTraceElement): String {
            return element.run { "($fileName:$lineNumber)" }
          }
        },
    )
  }
}

@CheckResult
fun Application.createLogger(): PYDroidLogger? {
  if (isDebugMode()) {
    return object : PYDroidLogger {

      override fun d(
          tag: String,
          message: String,
          vararg args: Any,
      ) {
        Timber.tag(tag).d(message, args)
      }

      override fun w(
          tag: String,
          message: String,
          vararg args: Any,
      ) {
        Timber.tag(tag).w(message, args)
      }

      override fun e(
          tag: String,
          throwable: Throwable,
          message: String,
          vararg args: Any,
      ) {
        Timber.tag(tag).e(throwable, message, args)
      }
    }
  } else {
    return null
  }
}
