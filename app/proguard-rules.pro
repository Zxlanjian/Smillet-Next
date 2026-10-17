-dontwarn io.github.libxposed.annotation.**
-adaptresourcefilecontents META-INF/xposed/java_init.list
-keep,allowoptimization,allowobfuscation public class * extends io.github.libxposed.api.XposedModule {
    public <init>();
}
-keep class com.sidesand.millet.hook.** { *; }
-keep class com.sidesand.millet.ipc.** { *; }
-keep class com.sidesand.millet.data.** { *; }
