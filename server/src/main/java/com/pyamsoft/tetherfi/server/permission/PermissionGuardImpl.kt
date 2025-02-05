package com.pyamsoft.tetherfi.server.permission

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.annotation.CheckResult
import androidx.core.content.ContextCompat
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
internal class PermissionGuardImpl
@Inject
internal constructor(
    private val context: Context,
) : PermissionGuard {

  override val requiredPermissions: List<String> by
      lazy(LazyThreadSafetyMode.NONE) {
        // Always require these WiFi permissions
        ALWAYS_PERMISSIONS +
            // On API < 33, we require location permission
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
              listOf(
                  android.Manifest.permission.ACCESS_COARSE_LOCATION,
                  android.Manifest.permission.ACCESS_FINE_LOCATION,
              )
            } else {
              // On API >= 33, we can use the new NEARBY_WIFI_DEVICES permission
              listOf(
                  android.Manifest.permission.NEARBY_WIFI_DEVICES,
              )
            }
      }

  @CheckResult
  private fun hasPermission(permission: String): Boolean {
    return ContextCompat.checkSelfPermission(context.applicationContext, permission) ==
        PackageManager.PERMISSION_GRANTED
  }

  override fun canCreateWiDiNetwork(): Boolean {
    return requiredPermissions.all { hasPermission(it) }
  }

  companion object {

    private val ALWAYS_PERMISSIONS =
        listOf(
            android.Manifest.permission.ACCESS_WIFI_STATE,
            android.Manifest.permission.CHANGE_WIFI_STATE,
        )
  }
}
