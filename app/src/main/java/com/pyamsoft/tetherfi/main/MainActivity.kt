package com.pyamsoft.tetherfi.main

import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import com.pyamsoft.pydroid.arch.SaveStateDisposableEffect
import com.pyamsoft.pydroid.bus.EventBus
import com.pyamsoft.pydroid.core.requireNotNull
import com.pyamsoft.pydroid.ui.app.installPYDroid
import com.pyamsoft.pydroid.ui.changelog.ChangeLogProvider
import com.pyamsoft.pydroid.ui.changelog.buildChangeLog
import com.pyamsoft.pydroid.util.PermissionRequester
import com.pyamsoft.pydroid.util.doOnCreate
import com.pyamsoft.pydroid.util.stableLayoutHideNavigation
import com.pyamsoft.tetherfi.ObjectGraph
import com.pyamsoft.tetherfi.R
import com.pyamsoft.tetherfi.TetherFiTheme
import com.pyamsoft.tetherfi.status.PermissionRequests
import com.pyamsoft.tetherfi.status.PermissionResponse
import com.pyamsoft.tetherfi.ui.InstallPYDroidExtras
import javax.inject.Inject
import javax.inject.Named
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import timber.log.Timber

class MainActivity : AppCompatActivity() {

  @Inject @JvmField internal var viewModel: MainViewModeler? = null

  @JvmField @Inject internal var permissionRequestBus: EventBus<PermissionRequests>? = null
  @JvmField @Inject internal var permissionResponseBus: EventBus<PermissionResponse>? = null

  @JvmField
  @Inject
  @Named("server")
  internal var serverPermissionRequester: PermissionRequester? = null

  @JvmField
  @Inject
  @Named("notification")
  internal var notificationPermissionRequester: PermissionRequester? = null

  private var serverRequester: PermissionRequester.Requester? = null
  private var notificationRequester: PermissionRequester.Requester? = null

  init {
    doOnCreate {
      installPYDroid(
          provider =
              object : ChangeLogProvider {

                override val applicationIcon = R.mipmap.ic_launcher

                override val changelog = buildChangeLog {
                  change("Full usage of Jetpack Compose")
                  feature("Allow horizontal swiping between pages")
                  bugfix("Major performance gains by optimizing a bunch of Compose code")
                }
              },
      )
    }

    doOnCreate { registerToSendPermissionResults() }

    doOnCreate { registerToRespondToPermissionRequests() }
  }

  private fun registerToSendPermissionResults() {
    serverRequester?.unregister()
    notificationRequester?.unregister()

    serverRequester =
        serverPermissionRequester.requireNotNull().registerRequester(this) { granted ->
          if (granted) {
            Timber.d("Network permission granted, toggle proxy")

            // Broadcast in the background
            lifecycleScope.launch(context = Dispatchers.IO) {
              permissionResponseBus.requireNotNull().send(PermissionResponse.ToggleProxy)
            }
          } else {
            Timber.w("Network permission not granted")
          }
        }

    notificationRequester =
        notificationPermissionRequester.requireNotNull().registerRequester(this) { granted ->
          if (granted) {
            Timber.d("Notification permission granted")

            // Broadcast in the background
            lifecycleScope.launch(context = Dispatchers.IO) {
              permissionResponseBus.requireNotNull().send(PermissionResponse.RefreshNotification)
            }
          } else {
            Timber.w("Notification permission not granted")
          }
        }
  }

  private fun registerToRespondToPermissionRequests() {
    lifecycleScope.launch(context = Dispatchers.IO) {
      permissionRequestBus.requireNotNull().onEvent {
        when (it) {
          is PermissionRequests.Notification -> {
            notificationRequester.requireNotNull().requestPermissions()
          }
          is PermissionRequests.Server -> {
            serverRequester.requireNotNull().requestPermissions()
          }
        }
      }
    }
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    stableLayoutHideNavigation()

    val component = ObjectGraph.ApplicationScope.retrieve(this).plusMain().create()
    component.inject(this)
    ObjectGraph.ActivityScope.install(this, component)

    val vm = viewModel.requireNotNull()
    val appName = getString(R.string.app_name)

    setContent {
      val state = vm.state
      val theme by state.theme.collectAsState()

      SaveStateDisposableEffect(vm)

      TetherFiTheme(
        theme = theme,
      ) {
        SystemBars()
        InstallPYDroidExtras()
        MainEntry(
          modifier = Modifier.fillMaxSize(),
          appName = appName,
          state = state,
          onOpenSettings = { vm.handleOpenSettings() },
          onCloseSettings = { vm.handleCloseSettings() },
        )
      }
    }
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
  }

  override fun onResume() {
    super.onResume()
    viewModel.requireNotNull().handleSyncDarkTheme(this)
    reportFullyDrawn()
  }

  override fun onConfigurationChanged(newConfig: Configuration) {
    super.onConfigurationChanged(newConfig)
    viewModel?.handleSyncDarkTheme(this)
  }

  override fun onDestroy() {
    super.onDestroy()

    notificationRequester?.unregister()
    serverRequester?.unregister()

    permissionRequestBus = null
    permissionResponseBus = null
    serverPermissionRequester = null
    notificationPermissionRequester = null
    serverRequester = null
    notificationRequester = null
    viewModel = null
  }
}
