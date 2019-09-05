// IGNORE_BACKEND: JVM
// IGNORE_BACKEND: JVM_IR
// WITH_COROUTINES
// FILE: test.kt
fun test() {
    val result = Result.success("yes!")
    val other = Result.success("nope")
    if (result.equals(other)) println("equals")
    if (!result.equals(other)) println("!equals")
}

// @TestKt.class:
// 0 INVOKESTATIC kotlin/Result.box-impl
// 0 INVOKEVIRTUAL kotlin/Result.unbox-impl
