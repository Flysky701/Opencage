-keepattributes RuntimeVisibleAnnotations,RuntimeVisibleParameterAnnotations,InnerClasses,EnclosingMethod,Signature,Exceptions,AnnotationDefault

# JNI callbacks, Cage guest bridges, FreeReflection, and already-trimmed BlackBox core
# (BlackBox uses heavy reflection: Class.forName, getDeclaredClasses, getGenericSuperclass,
#  Proxy.newProxyInstance, Parcelable/Serializable across :black/:p* processes)
-keep class top.niunaijun.blackbox.** { *; }
-keep class top.niunaijun.jnihook.** { *; }
-keep class io.github.flysky701.opencage.** { *; }
-keep class me.weishu.reflection.** { *; }

# black-reflection runtime and conditional keep for referenced black.* proxy interfaces
-keep class top.niunaijun.blackreflection.** { *; }
-if class black.**BR*
-keep class black.<1>BR<2> { *; }
-if class black.**BR*
-keep interface black.<1><2>Static { *; }
-if class black.**BR*
-keep interface black.<1><2>Context { *; }
-if interface black.**
-keep interface black.<1> { *; }

# AIDL / Binder / Parcelable / framework compile stubs
-keep class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator *;
}
-keep class * extends android.os.Binder { *; }
-keep interface * extends android.os.IInterface { *; }
-keep,allowshrinking class android.** { *; }
-keep,allowshrinking class com.android.** { *; }

# Hidden Android / MIUI / JVM platform APIs referenced via reflection or stub classes
-dontwarn android.**
-dontwarn com.android.**
-dontwarn miui.**
-dontwarn dalvik.**
-dontwarn libcore.**
-dontwarn sun.**
-dontwarn java.lang.invoke.**
