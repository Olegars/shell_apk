package ru.compclub.tvshell.ui

import android.text.Editable
import android.text.TextWatcher
import android.widget.EditText

/**
 * Mask like PC Shell: +7 (999) 999-99-99
 */
class RuPhoneMaskWatcher(private val edit: EditText) : TextWatcher {
    private var selfChange = false

    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit

    override fun afterTextChanged(s: Editable?) {
        if (selfChange) return
        selfChange = true
        val formatted = format(s?.toString().orEmpty())
        edit.setText(formatted)
        edit.setSelection(formatted.length)
        selfChange = false
    }

    companion object {
        const val EMPTY = "+7 ("

        fun digitsForApi(raw: String): String {
            var d = raw.filter { it.isDigit() }
            if (d.startsWith("8") && d.length >= 11) d = "7" + d.drop(1)
            if (!d.startsWith("7") && d.length == 10) d = "7$d"
            return d.take(11)
        }

        fun isComplete(raw: String): Boolean = digitsForApi(raw).length == 11

        fun format(raw: String): String {
            var d = raw.filter { it.isDigit() }
            when {
                d.startsWith("7") -> d = d.drop(1)
                d.startsWith("8") -> d = d.drop(1)
            }
            d = d.take(10)
            if (d.isEmpty()) return EMPTY

            val sb = StringBuilder("+7 (")
            sb.append(d.take(3))
            if (d.length < 3) return sb.toString()

            sb.append(") ")
            sb.append(d.drop(3).take(3))
            if (d.length < 6) return sb.toString()

            sb.append('-')
            sb.append(d.drop(6).take(2))
            if (d.length < 8) return sb.toString()

            sb.append('-')
            sb.append(d.drop(8).take(2))
            return sb.toString()
        }
    }
}
