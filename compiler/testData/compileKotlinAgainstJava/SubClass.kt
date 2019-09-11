package test

interface Base {
    fun foo(): Int
}

fun test() {
    val b: Base = SubClass()
    b.foo()
}
