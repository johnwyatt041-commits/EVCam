# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

# JNI exports use the original VhalNative class and native method names.
-keep class com.kooo.evcam.VhalNative { *; }
-keepclasseswithmembernames,includedescriptorclasses class * { native <methods>; }
-keepattributes Signature,InnerClasses,EnclosingMethod,RuntimeVisibleAnnotations,AnnotationDefault,SourceFile,LineNumberTable

# Glide discovers the generated module by its class name.
-keep public class com.bumptech.glide.GeneratedAppGlideModuleImpl { public <init>(android.content.Context); }
-keep public class * extends com.bumptech.glide.module.AppGlideModule { public <init>(); }

# WorkManager constructs workers by their persisted class names after app upgrades.
-keep class com.kooo.evcam.KeepAliveWorker { public <init>(android.content.Context, androidx.work.WorkerParameters); }

# WorkManager 2.9 / Room 2.5 creates this implementation reflectively at cold startup.
# Its consumer rule retains the class name but full-mode R8 removes the no-arg constructor.
-keepclassmembers class androidx.work.impl.WorkDatabase_Impl { public <init>(); }

# Preserve NanoHTTPD entrypoints used by FileTransferServer.
-keepnames class fi.iki.elonen.NanoHTTPD** { *; }
