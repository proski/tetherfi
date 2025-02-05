package com.pyamsoft.tetherfi.main

import androidx.annotation.CheckResult
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.ZeroCornerSize
import androidx.compose.material.AppBarDefaults
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.LocalContentColor
import androidx.compose.material.LocalTextStyle
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
import androidx.compose.material.Tab
import androidx.compose.material.TabRow
import androidx.compose.material.TabRowDefaults
import androidx.compose.material.Text
import androidx.compose.material.TopAppBar
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import com.google.accompanist.pager.ExperimentalPagerApi
import com.google.accompanist.pager.PagerState
import com.google.accompanist.pager.pagerTabIndicatorOffset
import com.google.accompanist.pager.rememberPagerState
import com.pyamsoft.pydroid.ui.theme.ZeroElevation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Composable
@CheckResult
fun rememberAllTabs(): SnapshotStateList<MainView> {
  return remember {
    mutableStateListOf(
        MainView.Status,
        MainView.Info,
    )
  }
}

@Composable
@OptIn(ExperimentalPagerApi::class)
fun MainTopBar(
    modifier: Modifier = Modifier,
    appName: String,
    pagerState: PagerState,
    allTabs: SnapshotStateList<MainView>,
    onSettingsOpen: () -> Unit,
) {
  Surface(
      modifier = modifier,
      color = MaterialTheme.colors.background,
      elevation = ZeroElevation,
  ) {
    Surface(
        contentColor = MaterialTheme.colors.onPrimary,
        color = MaterialTheme.colors.primary,
        shape =
            MaterialTheme.shapes.medium.copy(
                topStart = ZeroCornerSize,
                topEnd = ZeroCornerSize,
            ),
        elevation = AppBarDefaults.TopAppBarElevation,
    ) {
      Column {
        TopAppBar(
            modifier = Modifier.fillMaxWidth().statusBarsPadding(),
            elevation = ZeroElevation,
            backgroundColor = Color.Transparent,
            contentColor = LocalContentColor.current,
            title = {
              Text(
                  text = appName,
              )
            },
            actions = {
              IconButton(
                  onClick = onSettingsOpen,
              ) {
                Icon(
                    imageVector = Icons.Filled.Settings,
                    contentDescription = "Open Settings",
                )
              }
            },
        )

        val currentPage = pagerState.currentPage
        TabRow(
            modifier = Modifier.fillMaxWidth(),
            selectedTabIndex = currentPage,
            backgroundColor = Color.Transparent,
            contentColor = LocalContentColor.current,
            indicator = { tabPositions ->
              TabRowDefaults.Indicator(
                  modifier = Modifier.pagerTabIndicatorOffset(pagerState, tabPositions),
              )
            },
        ) {
          val scope = rememberCoroutineScope()
          for (index in allTabs.indices) {
            val tab = allTabs[index]
            val isSelected =
                remember(
                    index,
                    currentPage,
                ) {
                  index == currentPage
                }

            MainTab(
                tab = tab,
                isSelected = isSelected,
                onSelected = {
                  // Click fires the index to update
                  // The index updating is caught by the snapshot flow
                  // Which then triggers the page update function
                  scope.launch(context = Dispatchers.Main) { pagerState.animateScrollToPage(index) }
                },
            )
          }
        }
      }
    }
  }
}

@Composable
private fun MainTab(
    modifier: Modifier = Modifier,
    tab: MainView,
    isSelected: Boolean,
    onSelected: () -> Unit,
) {
  val textStyle = LocalTextStyle.current
  Tab(
      modifier = modifier,
      selected = isSelected,
      onClick = onSelected,
      text = {
        Text(
            text = tab.name,
            style =
                textStyle.copy(
                    fontWeight = if (isSelected) FontWeight.W700 else null,
                ),
        )
      },
  )
}

@Preview
@Composable
@OptIn(ExperimentalPagerApi::class)
private fun PreviewMainTopBar() {
  MainTopBar(
      appName = "TEST",
      pagerState = rememberPagerState(),
      allTabs = rememberAllTabs(),
      onSettingsOpen = {},
  )
}
