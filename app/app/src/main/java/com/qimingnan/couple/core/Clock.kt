package com.qimingnan.couple.core

/** 可注入时钟，单测里替换为虚拟时间。 */
fun interface Clock {
    fun nowMillis(): Long

    companion object {
        val System: Clock = Clock { java.lang.System.currentTimeMillis() }
    }
}
