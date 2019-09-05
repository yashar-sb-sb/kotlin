// WITH_COROUTINES
// FILE: test.kt
fun test() {
    val result = Result.success("yes!")
    val other = Result.success("nope")
    if (result == other) println("==")
    if (result != other) println("!=")
    if (result.equals(other)) println("equals")
    if (!result.equals(other)) println("!equals")
}

// @TestKt.class:
// 0 INVOKESTATIC kotlin/Result.equals-impl0
