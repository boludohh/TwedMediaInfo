# TwedMediaInfo - Library R8 rules
#
# Solo se conserva lo que la capa nativa enlaza por nombre (ver consumer-rules.pro).

# JNI bridge
-keep class com.twedmediainfo.android.internal.NativeBridge { *; }
-keep class com.twedmediainfo.android.internal.NativeLoader { *; }