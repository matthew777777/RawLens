package com.matthew.rawlens

import android.text.SpannableString
import android.text.Spanned
import android.text.style.RelativeSizeSpan

/** Value on top, small caption underneath (same look as the lens switcher sublabels). */
internal fun chipCaption(value: String, caption: String): CharSequence {
    val s = SpannableString("$value\n$caption")
    s.setSpan(RelativeSizeSpan(0.6f), value.length + 1, s.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    return s
}
