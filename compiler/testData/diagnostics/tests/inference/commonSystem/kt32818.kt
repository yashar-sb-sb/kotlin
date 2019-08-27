// !WITH_NEW_INFERENCE
// NI_EXPECTED_FILE

fun <T : Any> nullable(): T? = null

val value = nullable<Int>() ?: <!OI;TYPE_INFERENCE_EXPECTED_TYPE_MISMATCH!>nullable()<!>