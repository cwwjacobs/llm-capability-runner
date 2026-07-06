# LiteRT-LM exposes native-backed public APIs used directly by the app.
-keep class com.google.ai.edge.litertlm.** { *; }

# These annotations are compile-time metadata referenced by Tink. They are not
# present at runtime, and R8 otherwise treats the references as missing classes.
-dontwarn javax.annotation.Nullable
-dontwarn javax.annotation.concurrent.GuardedBy
