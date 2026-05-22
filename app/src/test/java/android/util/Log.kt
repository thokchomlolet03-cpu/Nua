package android.util

object Log {
    @JvmStatic
    fun d(tag: String, msg: String): Int {
        println("D/$tag: $msg")
        return 0
    }

    @JvmStatic
    fun e(tag: String, msg: String): Int {
        println("E/$tag: $msg")
        return 0
    }

    @JvmStatic
    fun e(tag: String, msg: String, tr: Throwable): Int {
        println("E/$tag: $msg")
        tr.printStackTrace()
        return 0
    }

    @JvmStatic
    fun w(tag: String, msg: String): Int {
        println("W/$tag: $msg")
        return 0
    }

    @JvmStatic
    fun i(tag: String, msg: String): Int {
        println("I/$tag: $msg")
        return 0
    }

    @JvmStatic
    fun v(tag: String, msg: String): Int {
        println("V/$tag: $msg")
        return 0
    }
}
