# youtubedl-android lee la salida de yt-dlp con Jackson por reflexión.
-keep class com.yausername.** { *; }
-keep class com.fasterxml.jackson.** { *; }
-keepattributes Signature, *Annotation*, InnerClasses, EnclosingMethod
-dontwarn com.fasterxml.jackson.**
-dontwarn org.apache.commons.compress.**
-keep class org.apache.commons.compress.** { *; }
-dontwarn java.beans.**
-dontwarn org.slf4j.**
