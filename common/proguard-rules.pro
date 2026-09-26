# Add project specific ProGuard rules here.
# Keep protocol / transport model classes used by reflection.
-keep class com.inklink.common.protocol.** { *; }
-keep class com.inklink.common.transport.** { *; }
-dontwarn org.java_websocket.**
-dontwarn okio.**
