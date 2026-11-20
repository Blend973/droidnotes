# =============================================================================
# DroidNotes - R8 / ProGuard configuration
# =============================================================================
# Goal: maximum R8 shrinking / optimization / obfuscation with no unnecessary
# keeps.
#
# Baseline (verified from the dependency artifacts and AGP 9.1.1's generated
# proguard-android-optimize.txt):
#   - R8 full mode is the default (AGP 8.0+).
#   - AGP 9.1+ repackages classes by default; do NOT add -repackageclasses.
#   - The default file already enables -allowaccessmodification and keeps
#     AnnotationDefault, EnclosingMethod, InnerClasses,
#     RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations,
#     RuntimeVisibleTypeAnnotations and Signature.
#   - Every library used here ships its own consumer rules: Room, Compose,
#     Coroutines, Lifecycle, Navigation, Activity, DataStore/Okio.
#
# App code uses no reflection, JNI, Parcelable, Serializable, WebView bridge,
# ServiceLoader or runtime annotation processing. Therefore this file is
# deliberately tiny: the only real keep is the Compose/R8 workaround below.
# =============================================================================

# -----------------------------------------------------------------------------
# Crash reporting (deliberate size / debuggability trade-off)
# -----------------------------------------------------------------------------
# Keep line numbers so release stack traces can be retraced with mapping.txt.
# Rename the source-file attribute so full source paths are not embedded.
# If you need the absolute smallest APK and don't need retracing, delete both
# lines.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# -----------------------------------------------------------------------------
# Compose Foundation / R8 9.1.x workaround
# -----------------------------------------------------------------------------
# R8 can fail with an internal NPE while optimizing
# ContentInViewNode.launchAnimation() with Compose 1.7.0 (BOM 2024.09.00).
# Keeping this single class out of optimization avoids that failure. Remove
# this rule after upgrading Compose to a version that no longer triggers it.
-keep class androidx.compose.foundation.gestures.ContentInViewNode { *; }

# =============================================================================
# Intentionally NO keep rules for the following
# =============================================================================
# MainActivity / DroidNotesApp
#   Declared in AndroidManifest.xml, so AAPT2 emits:
#     -keep class com.droidnotes.MainActivity { <init>(); }
#     -keep class com.droidnotes.DroidNotesApp { <init>(); }
#   onCreate() is kept because it overrides a framework method.
#
# Room 2.7.0
#   room-runtime ships:
#     -keep class * extends androidx.room.RoomDatabase { void <init>(); }
#   Generated DAO/database code references entities and DAOs directly.
#   @Entity / @Dao / @Fts4 are compile-time only.
#
# Compose 1.7.0 (ui, runtime, material3, foundation, icons)
#   ui/runtime ship consumer rules for Composer source info,
#   ViewLayerContainer, AndroidComposeView and ModifierNodeElement.
#   Compose Foundation needs no app-side rules.
#
# Kotlin Coroutines 1.10.2
#   coroutines-core / -android ship rules for MainDispatcherFactory,
#   CoroutineExceptionHandler, volatile fields and ServiceLoader entries.
#
# Lifecycle / ViewModel 2.8.7
#   lifecycle-viewmodel ships constructor rules; this app creates ViewModels
#   with explicit ViewModelProvider.Factory instances, not reflection.
#
# Navigation Compose 2.8.9
#   navigation-common ships NavArgs/Navigator rules. This app uses string
#   routes and NavType only.
#
# DataStore 1.1.7, Activity Compose 1.10.1, Material3
#   No runtime reflection or name-based lookup.
#
# Enums SortOrder / EditorViewMode
#   Persisted via Enum.name(). R8 preserves the declared name string when
#   optimizing/unboxing enums; name() is part of the enum contract.
#
# Parcelable / Serializable / JNI / WebView / reflection
#   None are used in app code.

# Build-level companion setting (not a ProGuard rule):
#   release { isShrinkResources = true }  // AGP 9.0+ uses optimized resource
#                                          // shrinking automatically.
