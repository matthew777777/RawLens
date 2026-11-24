package com.matthew.rawlens

import android.text.SpannableString
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.text.style.ForegroundColorSpan

/** Value on top, small caption underneath (same look as the lens switcher sublabels). */
internal fun chipCaption(value: String, caption: String, captionColor: Int): CharSequence {
    val s = SpannableString("$value\n$caption")
    s.setSpan(RelativeSizeSpan(0.72f), value.length + 1, s.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    s.setSpan(ForegroundColorSpan(captionColor), value.length + 1, s.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    return s
}
