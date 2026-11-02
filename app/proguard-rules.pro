# GeckoView and Android Components publish their required consumer rules.
# browser-engine-gecko 依赖的 service-nimbus 编译期引用 Glean；Miru 不含遥测，也不初始化 Nimbus。
-dontwarn mozilla.telemetry.glean.**
# SnakeYAML 2.2 (Gecko DebugConfig) calls Class.getPackage().getName() in
# TypeDescription and PropertySubstitute static loggers. Keep those package
# identities while allowing class/member renaming, shrinking and optimization.
-keeppackagenames org.yaml.snakeyaml,org.yaml.snakeyaml.introspector
