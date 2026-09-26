# Ably SDK 混淆规则（app release 开启 minify 时生效，反射/序列化所需）
-keep class io.ably.lib.types.** { *; }
-dontwarn io.ably.lib.**
-dontwarn org.slf4j.**
-dontwarn org.msgpack.**
