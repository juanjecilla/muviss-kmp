# shimmer

A loading shimmer for Compose Multiplatform: one sweep shared by every placeholder under a `ShimmerHost`, so the highlight travels across a screen in turn instead of each placeholder animating on its own.

Compose only. It imports nothing outside Compose and the Kotlin standard library, and it takes its colours, duration and reduced-motion flag as plain parameters.

```kotlin
ShimmerHost(animate = !reduceMotion) {
    Box(Modifier.size(110.dp, 165.dp).clip(RoundedCornerShape(12.dp)).shimmer(base = surface, highlight = surfaceBright))
}
```

- `ShimmerHost(animate, durationMillis, bandFraction)` runs the sweep. `animate = false` draws a flat `base` fill and runs no animation.
- `Modifier.shimmer(base, highlight, animate)` paints one placeholder. Outside a host it sweeps across its own width.

The band is positioned in window coordinates, so two neighbouring placeholders share one edge of the highlight.

Tests: `./gradlew :lib:shimmer:jvmTest`.
