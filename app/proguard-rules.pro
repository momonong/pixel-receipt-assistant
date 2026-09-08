# Project-specific R8 rules belong here when release shrinking is enabled.
# Persisted private JSON v1 uses these field names. A future change needs an explicit data migration.
-keep class com.momonong.pixelreceipt.domain.model.** { *; }
