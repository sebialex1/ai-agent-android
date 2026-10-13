-keepattributes *Annotation*, InnerClasses
-keep,includedescriptorclasses class com.sebialex.agent.**$$serializer { *; }
-keepclassmembers class com.sebialex.agent.** { *** Companion; }

# LiteRT-LM calls back into Kotlin from JNI and builds tool schemas with reflection.
-keep class com.google.ai.edge.litertlm.** { *; }
-dontwarn com.google.ai.edge.litertlm.**
