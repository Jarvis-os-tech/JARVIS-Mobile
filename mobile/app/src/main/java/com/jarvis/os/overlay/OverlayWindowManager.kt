package com.jarvis.os.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Custom LifecycleOwner, ViewModelStoreOwner, and SavedStateRegistryOwner
 * required for running Jetpack Compose Views inside a Service WindowManager overlay.
 */
class OverlayLifecycleOwner : LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateRegistryController = SavedStateRegistryController.create(this)
    private val store = ViewModelStore()

    override val lifecycle: Lifecycle = lifecycleRegistry
    override val viewModelStore: ViewModelStore = store
    override val savedStateRegistry: SavedStateRegistry = savedStateRegistryController.savedStateRegistry

    fun onCreate() {
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
    }

    fun onResume() {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    }

    fun onDestroy() {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        store.clear()
    }
}

/**
 * Manages the floating system overlay window (TYPE_APPLICATION_OVERLAY).
 * Strict Siri-style behavior: completely invisible and touch-transparent (FLAG_NOT_TOUCHABLE)
 * when IDLE, seamlessly fading in the Dynamic Island & Halo Orb when voice is active.
 */
class OverlayWindowManager(
    private val context: Context
) {
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var composeView: ComposeView? = null
    private var lifecycleOwner: OverlayLifecycleOwner? = null
    private var isAttached = false

    private val overlayStateFlow = MutableStateFlow<OverlayUiState>(OverlayUiState.Idle)

    private val layoutParams = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.MATCH_PARENT,
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        },
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        alpha = 0.0f
    }

    /**
     * Attach overlay to window manager if permission is granted.
     */
    @Synchronized
    fun attach() {
        if (isAttached) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(context)) {
            Log.w(TAG, "Cannot attach overlay: SYSTEM_ALERT_WINDOW permission not granted")
            return
        }

        try {
            val owner = OverlayLifecycleOwner().apply {
                onCreate()
                onResume()
            }
            lifecycleOwner = owner

            val view = ComposeView(context).apply {
                setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)

                setViewTreeLifecycleOwner(owner)
                setViewTreeViewModelStoreOwner(owner)
                setViewTreeSavedStateRegistryOwner(owner)

                setContent {
                    val state by overlayStateFlow.collectAsState()

                    Box(
                        modifier = Modifier.fillMaxSize()
                    ) {
                        // Top Dynamic Island capsule
                        DynamicIsland(
                            state = state,
                            modifier = Modifier.align(Alignment.TopCenter)
                        )

                        // Floating Siri Halo Orb at lower third
                        AnimatedVisibility(
                            visible = state.isVisible,
                            enter = fadeIn() + scaleIn(initialScale = 0.7f),
                            exit = fadeOut() + scaleOut(targetScale = 0.7f),
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(bottom = 72.dp)
                        ) {
                            HaloOrb(
                                size = 150.dp,
                                state = state
                            )
                        }
                    }
                }
            }

            view.visibility = View.GONE
            windowManager.addView(view, layoutParams)
            composeView = view
            isAttached = true
            Log.i(TAG, "OverlayWindowManager attached successfully with custom lifecycle")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to attach overlay window: ${e.message}", e)
        }
    }

    /**
     * Update overlay presentation state with touch flag synchronization.
     */
    @Synchronized
    fun updateState(state: OverlayUiState) {
        overlayStateFlow.value = state

        if (!isAttached || composeView == null) {
            if (state.isVisible) {
                attach()
            } else {
                return
            }
        }

        val view = composeView ?: return

        try {
            if (state == OverlayUiState.Idle) {
                // Completely invisible and 100% touch-transparent
                layoutParams.flags = layoutParams.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                layoutParams.alpha = 0.0f
                view.visibility = View.GONE
                windowManager.updateViewLayout(view, layoutParams)
            } else {
                // Visible, active interaction
                layoutParams.flags = layoutParams.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                layoutParams.alpha = 1.0f
                view.visibility = View.VISIBLE
                windowManager.updateViewLayout(view, layoutParams)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error updating overlay layout: ${e.message}", e)
        }
    }

    /**
     * Remove overlay completely from the window hierarchy.
     */
    @Synchronized
    fun detach() {
        if (!isAttached) return
        try {
            lifecycleOwner?.onDestroy()
            lifecycleOwner = null
            composeView?.let { windowManager.removeView(it) }
            composeView = null
            isAttached = false
            Log.i(TAG, "OverlayWindowManager detached successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error detaching overlay: ${e.message}", e)
        }
    }

    companion object {
        private const val TAG = "OverlayWindowManager"
    }
}
