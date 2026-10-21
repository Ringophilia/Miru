# GeckoView and Android Components publish their required consumer rules.
# browser-engine-gecko 依赖的 service-nimbus 编译期引用 Glean；Miru 不含遥测，也不初始化 Nimbus。
-dontwarn mozilla.telemetry.glean.**
