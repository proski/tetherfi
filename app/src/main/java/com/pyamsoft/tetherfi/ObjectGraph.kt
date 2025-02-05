package com.pyamsoft.tetherfi

import android.app.Activity
import android.app.Application
import android.app.Service
import androidx.annotation.CheckResult
import androidx.fragment.app.FragmentActivity
import com.pyamsoft.pydroid.core.requireNotNull
import com.pyamsoft.pydroid.util.doOnDestroy
import com.pyamsoft.tetherfi.main.MainComponent
import timber.log.Timber

internal object ObjectGraph {

  internal object ApplicationScope {

    private val trackingMap = mutableMapOf<Application, TetherFiComponent>()

    fun install(
        application: Application,
        component: TetherFiComponent,
    ) {
      trackingMap[application] = component
      Timber.d("Track ApplicationScoped install: $application $component")
    }

    @CheckResult
    fun retrieve(activity: Activity): TetherFiComponent {
      return retrieve(activity.application)
    }

    @CheckResult
    fun retrieve(service: Service): TetherFiComponent {
      return retrieve(service.application)
    }

    @CheckResult
    fun retrieve(application: Application): TetherFiComponent {
      return trackingMap[application].requireNotNull {
        "Could not find ApplicationScoped internals for Application: $application"
      }
    }
  }

  internal object ActivityScope {

    private val trackingMap = mutableMapOf<FragmentActivity, MainComponent>()

    fun install(
        activity: FragmentActivity,
        component: MainComponent,
    ) {
      trackingMap[activity] = component
      Timber.d("Track ActivityScoped install: $activity $component")

      activity.doOnDestroy {
        Timber.d("Remove ActivityScoped graph onDestroy")
        trackingMap.remove(activity)
      }
    }

    @CheckResult
    fun retrieve(activity: FragmentActivity): MainComponent {
      return trackingMap[activity].requireNotNull {
        "Could not find ActivityScoped internals for Activity: $activity"
      }
    }
  }
}
