# Project-specific R8 rules belong here when release shrinking is enabled.
# Persisted private JSON v1/v2/v3 uses these field names. Changes need explicit payload migration.
-keep class com.momonong.pixelreceipt.domain.model.** { *; }
# Saved-state editor buffers use Gson; keep these raw DTOs stable across process recreation.
-keep class com.momonong.pixelreceipt.domain.usecase.ReviewInput { *; }
-keep class com.momonong.pixelreceipt.domain.usecase.ReviewLineInput { *; }
-keep class com.momonong.pixelreceipt.domain.usecase.ReviewAdjustmentInput { *; }
-keep class com.momonong.pixelreceipt.data.extraction.NanoReceiptOutput { *; }
-keep class com.momonong.pixelreceipt.data.extraction.NanoReceiptRow { *; }
