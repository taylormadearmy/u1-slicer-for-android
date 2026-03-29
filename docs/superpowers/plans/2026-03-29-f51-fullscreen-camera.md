# F51: Fullscreen Printer Camera Feed Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a fullscreen button to the printer camera feed that opens the feed in a full-screen Compose Dialog, continuing the existing MJPEG polling loop uninterrupted.

**Architecture:** Add a `showFullscreen` boolean state to `PrinterScreen`. When true, render a `Dialog` that fills the screen showing the same `cameraFrame` bitmap. The MJPEG polling `LaunchedEffect` is unaffected — it keeps writing to `cameraFrame` regardless of dialog state.

**Tech Stack:** Kotlin, Jetpack Compose, Material3

---

### Task 1: Add fullscreen button and dialog to PrinterScreen

**Files:**
- Modify: `app/src/main/java/com/u1/slicer/ui/PrinterScreen.kt`

No new files needed. The change is entirely within `PrinterScreen`.

- [ ] **Step 1: Add `showFullscreen` state near the other `remember` vars**

Inside `PrinterScreen` composable (near `var cameraFrame` around line 64):

```kotlin
var showFullscreen by remember { mutableStateOf(false) }
```

- [ ] **Step 2: Add the fullscreen button overlay on the camera image**

Find where `cameraFrame` is displayed (around line 229). It currently renders something like:

```kotlin
val frame = cameraFrame
if (frame != null) {
    Image(
        bitmap = frame.asImageBitmap(),
        contentDescription = "Camera feed",
        modifier = Modifier.fillMaxWidth().aspectRatio(...)
    )
}
```

Wrap it in a `Box` and add the fullscreen button:

```kotlin
val frame = cameraFrame
if (frame != null) {
    Box {
        Image(
            bitmap = frame.asImageBitmap(),
            contentDescription = "Camera feed",
            modifier = Modifier.fillMaxWidth()
        )
        IconButton(
            onClick = { showFullscreen = true },
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(4.dp)
                .background(
                    color = Color.Black.copy(alpha = 0.4f),
                    shape = CircleShape
                )
                .size(32.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Fullscreen,
                contentDescription = "Fullscreen",
                tint = Color.White
            )
        }
    }
}
```

Required imports (add if not present):
```kotlin
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
```

- [ ] **Step 3: Add the fullscreen Dialog**

After the camera `Box` block (still inside `PrinterScreen`), add:

```kotlin
if (showFullscreen) {
    val fullFrame = cameraFrame
    Dialog(
        onDismissRequest = { showFullscreen = false },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            if (fullFrame != null) {
                Image(
                    bitmap = fullFrame.asImageBitmap(),
                    contentDescription = "Camera feed fullscreen",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize()
                )
            }
            IconButton(
                onClick = { showFullscreen = false },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp)
                    .background(
                        color = Color.Black.copy(alpha = 0.4f),
                        shape = CircleShape
                    )
            ) {
                Icon(
                    imageVector = Icons.Default.FullscreenExit,
                    contentDescription = "Exit fullscreen",
                    tint = Color.White
                )
            }
        }
    }
}
```

Additional import:
```kotlin
import androidx.compose.ui.layout.ContentScale
```

- [ ] **Step 4: Build and smoke-test**

```bash
./gradlew installDebug --no-daemon 2>&1 | tail -5
```

Navigate to the Printer tab with an active camera feed. Verify:
- Fullscreen button appears over the camera thumbnail
- Tapping it opens the fullscreen dialog showing the feed
- Feed continues updating in fullscreen
- Close button and tapping outside both dismiss the dialog

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/u1/slicer/ui/PrinterScreen.kt
git commit -m "feat(F51): add fullscreen camera feed dialog to Printer screen"
```
