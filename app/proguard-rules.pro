# R8 rules for release builds.
#
# ONNX Runtime, OpenCV and LiteRT-LM call back into their Java classes from native code by
# name, and none of their AARs ship keep rules. Keep them whole; R8 still shrinks everything else.
-keep class ai.onnxruntime.** { *; }
-keep class org.opencv.** { *; }
-keep class com.google.ai.edge.litertlm.** { *; }

# Native methods must keep their names to bind to the .so symbols.
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}

# Stack traces in crash logs stay readable.
-keepattributes SourceFile,LineNumberTable
