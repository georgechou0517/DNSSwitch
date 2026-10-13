# DNSSwitch ProGuard / R8 规则
# OkHttp 官方建议的规则（其文档内附 consumer rules，这里显式声明以备不时之需）
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
