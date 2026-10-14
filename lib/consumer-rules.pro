# TwedMediaInfo - Consumer R8 rules
#
# These rules are packaged inside the AAR and applied by R8
# when an application consumes TwedMediaInfo.
#
# Do not disable shrinking, obfuscation, or optimization globally.
#
# La API pública (TwedMediaInfo, StreamKind, parameters.*, TwedMediaInfoError y FieldStatus)
# NO necesita reglas keep: las apps la usan por referencia directa y las constantes
# (public static final) las incrusta javac en el código del consumidor. Un keep sobre
# parameters.** obligaría a cada app a conservar ~1.500 campos que nunca se leen en runtime.
#
# Solo se conserva lo que la capa nativa enlaza por nombre.

# JNI bridge (las funciones C++ se enlazan por nombre de clase y de método).
-keep class com.twedmediainfo.android.internal.NativeBridge { *; }

# Native library loader.
-keep class com.twedmediainfo.android.internal.NativeLoader { *; }
