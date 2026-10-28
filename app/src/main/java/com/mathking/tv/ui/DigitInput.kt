package com.mathking.tv.ui

/** 由 Activity 把遥控器数字键转发给当前答题页 */
interface DigitInput {
    fun appendDigit(d: String)
    fun backspace()
}
