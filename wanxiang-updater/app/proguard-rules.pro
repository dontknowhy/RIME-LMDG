# ---- Chaquopy Python 运行时 ----
# Chaquopy 通过反射加载 Java 桥接类，必须完整保留。
-keep class com.chaquo.python.** { *; }
-keep class com.chaquo.python.android.** { *; }
-dontwarn com.chaquo.python.**

# ---- AndroidX DocumentFile（SAF 目标读写）----
-keep class androidx.documentfile.** { *; }

# ---- Kotlin / 协程 ----
-dontwarn kotlinx.coroutines.**
-keepclassmembers class kotlinx.coroutines.** { volatile <fields>; }

# 保留注解与泛型签名，Compose 运行时依赖。
-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod

# 保留行号，便于崩溃栈定位（体积代价很小）。
-keepattributes SourceFile, LineNumberTable
-renamesourcefileattribute SourceFile
