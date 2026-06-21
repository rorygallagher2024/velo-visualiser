# Fix Intro Jerkiness by Deferring Background Loading

The background loading of visualizers during the intro animation caused "jerkiness" because compiling shaders and linking programs on the GL thread can occasionally exceed the 16ms/8ms frame budget. We will modify the strategy to wait until the intro is completely finished before starting the stealth background warmup.

## Proposed Changes

### [GL Component]

#### [VisualizerRenderer.kt](file:///Users/rory/android-visualiser-low-latency/app/src/main/java/com/lowlatency/visualizer/gl/VisualizerRenderer.kt)

- **Defer Background Load**: Update `tryBackgroundLoad()` to check if the intro is still active.
- **Cadence Adjustment**: Keep the slow cadence (one scene every 8 frames) but only start it once `introActive` is false.

```diff
     private fun tryBackgroundLoad() {
-        if (scenesToLoad.isEmpty()) return
+        // NEW: Never load in the background while the intro is playing to ensure 0ms impact on animation
+        if (scenesToLoad.isEmpty() || introActive) return
+
         // Slow cadence: one scene every 8 frames
         if (++loadFrameCounter % 8 != 0) return
         ...
     }
```

## Verification Plan

### Manual Verification
- **Cold Boot**: Open the app and observe the "VELO" intro animation. It must be perfectly smooth with zero jerkiness.
- **Verification of Background Work**: After the intro finishes, wait about 2-3 seconds, then swipe through the scenes. They should still be pre-loaded and ready to go.
